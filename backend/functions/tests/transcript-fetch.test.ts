/**
 * Unit + emulator tests for fetchTranscript().
 *
 * Mocking strategy:
 *   - auth/innertube.ts (getInnertubeClient) — vi.mock at module level.
 *     Each test configures the mock to return a specific response shape.
 *   - admin.storage() — vi.spyOn captures bucket/file/save/getSignedUrl calls.
 *     There is no GCS emulator so this is mock-only.
 *   - Firestore — emulator at 127.0.0.1:8080; clearFirestore() resets between tests.
 *     Pointer doc writes are asserted via admin.firestore().doc(…).get().
 *
 * The segment duck-type filter in fetch.ts checks for `start_ms: string` and
 * `snippet.text: string` — the test fixtures satisfy this without needing a
 * real YTNodes.TranscriptSegment instance.
 */

import {
  afterEach,
  beforeAll,
  beforeEach,
  describe,
  expect,
  it,
  vi,
} from "vitest";
import * as admin from "firebase-admin";
import { clearFirestore, initAdminEmulator } from "./helpers/admin";

// --- Mock innertube auth (hoisted; must appear before any src import) ---
vi.mock("../src/auth/innertube.js", () => ({
  getInnertubeClient: vi.fn(),
}));

// --- Suppress logger output in tests ---
vi.mock("firebase-functions/logger", () => ({
  info: vi.fn(),
  warn: vi.fn(),
  error: vi.fn(),
  debug: vi.fn(),
}));

process.env.TRANSCRIPT_BUCKET = "test-bucket";

import { getInnertubeClient } from "../src/auth/innertube.js";
import { fetchTranscript } from "../src/transcript/fetch.js";

// ---------------------------------------------------------------------------
// Fixture helpers
// ---------------------------------------------------------------------------

/** A minimal segment object that satisfies the duck-type filter in fetch.ts */
function seg(start_ms: string, text: string) {
  return { start_ms, snippet: { text } };
}

/**
 * Builds a mock TranscriptInfo-like object with real segments.
 * Sets `initial_segments` to an array of duck-typed segment objects.
 */
function transcriptWith(segments: ReturnType<typeof seg>[], language = "en") {
  return {
    selectedLanguage: language,
    transcript: {
      content: {
        body: { initial_segments: segments },
      },
    },
  };
}

/** Simulates a video with no captions — transcript.content is null */
function transcriptEmpty() {
  return {
    selectedLanguage: "en",
    transcript: { content: null },
  };
}

/** Installs GCS mocks on admin.storage(); returns handles for asserting calls */
function mockGcs({
  saveThrows,
  signedUrl = "https://signed.url/transcript",
}: {
  saveThrows?: boolean;
  signedUrl?: string;
} = {}) {
  const mockFile = {
    save: vi.fn(async () => {
      if (saveThrows) throw new Error("ECONNRESET");
    }),
    getSignedUrl: vi.fn(async () => [signedUrl]),
  };
  const mockBucket = { file: vi.fn(() => mockFile) };
  vi.spyOn(admin, "storage").mockReturnValue({
    bucket: vi.fn(() => mockBucket),
  } as never);
  return { mockFile, mockBucket };
}

/** Wires getInnertubeClient to return a client whose getInfo() returns the given transcript */
function mockInnertube(
  transcriptResult:
    | ReturnType<typeof transcriptWith>
    | ReturnType<typeof transcriptEmpty>
    | null,
  getInfoThrows?: string,
) {
  (getInnertubeClient as ReturnType<typeof vi.fn>).mockResolvedValueOnce({
    getInfo: vi.fn(async () => {
      if (getInfoThrows) throw new Error(getInfoThrows);
      return {
        getTranscript: vi.fn(async () => transcriptResult),
      };
    }),
  });
}

const VIDEO_ID = "yt-test-vid-1";

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

describe("fetchTranscript — emulator-backed", () => {
  beforeAll(() => {
    initAdminEmulator();
  });

  beforeEach(async () => {
    await clearFirestore();
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  // ---
  // AC1: available path
  // ---
  it("available path: writes GCS blob and pointer doc at status=available", async () => {
    const segments = [seg("0", "Hello world"), seg("2500", "Next sentence.")];
    mockInnertube(transcriptWith(segments, "fr"));
    const { mockFile } = mockGcs({
      signedUrl: "https://storage.goog/t/signed",
    });

    await fetchTranscript(VIDEO_ID);

    // GCS save called once; text contains both segment lines.
    expect(mockFile.save).toHaveBeenCalledOnce();
    const savedText = mockFile.save.mock.calls[0][0] as string;
    expect(savedText).toContain("Hello world");
    expect(savedText).toContain("Next sentence.");

    // Pointer doc is `available` with expected fields.
    const snap = await admin.firestore().doc(`transcripts/${VIDEO_ID}`).get();
    expect(snap.exists).toBe(true);
    const data = snap.data()!;
    expect(data.status).toBe("available");
    expect(data.source).toBe("youtubei");
    expect(data.language).toBe("fr");
    expect(data.gcsPath).toBe(`transcripts/${VIDEO_ID}.txt`);
    expect(data.signedUrl).toBe("https://storage.goog/t/signed");
    expect(data.segments).toHaveLength(2);
    // start in seconds: 0ms → 0.00s, 2500ms → 2.50s
    expect(data.segments[0]).toMatchObject({ start: 0, text: "Hello world" });
    expect(data.segments[1]).toMatchObject({
      start: 2.5,
      text: "Next sentence.",
    });
  });

  // ---
  // AC2: unavailable path
  // ---
  it("unavailable path: no GCS write; pointer doc at status=unavailable", async () => {
    mockInnertube(transcriptEmpty());
    const { mockFile } = mockGcs();

    await fetchTranscript(VIDEO_ID);

    expect(mockFile.save).not.toHaveBeenCalled();

    const snap = await admin.firestore().doc(`transcripts/${VIDEO_ID}`).get();
    expect(snap.exists).toBe(true);
    expect(snap.data()?.status).toBe("unavailable");
    // No gcsPath or signedUrl on an unavailable doc.
    expect(snap.data()?.gcsPath).toBeUndefined();
  });

  // ---
  // AC3: transient path — fetch failure
  // ---
  it("transient path (getInfo throws): no GCS write; pointer doc at status=transient", async () => {
    mockInnertube(null, "CONNECTION_RESET");
    const { mockFile } = mockGcs();

    await fetchTranscript(VIDEO_ID);

    expect(mockFile.save).not.toHaveBeenCalled();

    const snap = await admin.firestore().doc(`transcripts/${VIDEO_ID}`).get();
    expect(snap.exists).toBe(true);
    const data = snap.data()!;
    expect(data.status).toBe("transient");
    expect(data.errorCode).toContain("CONNECTION_RESET");
  });

  // AC3: transient path — GCS write failure
  it("transient path (GCS save throws): pointer doc at status=transient with GCS_WRITE prefix", async () => {
    mockInnertube(transcriptWith([seg("0", "text")]));
    const { mockFile } = mockGcs({ saveThrows: true });

    await fetchTranscript(VIDEO_ID);

    expect(mockFile.save).toHaveBeenCalledOnce();

    const snap = await admin.firestore().doc(`transcripts/${VIDEO_ID}`).get();
    expect(snap.data()?.status).toBe("transient");
    expect(snap.data()?.errorCode).toMatch(/^GCS_WRITE:/);
  });

  // ---
  // Idempotency: re-run on already-available doc is a no-op
  // ---
  it("idempotency: skips Innertube and GCS when pointer is already available", async () => {
    await admin
      .firestore()
      .doc(`transcripts/${VIDEO_ID}`)
      .set({
        videoId: VIDEO_ID,
        status: "available",
        gcsPath: `transcripts/${VIDEO_ID}.txt`,
        signedUrl: "https://existing.signed.url",
        createdAt: admin.firestore.FieldValue.serverTimestamp(),
        updatedAt: admin.firestore.FieldValue.serverTimestamp(),
      });

    const getInfoMock = vi.fn();
    (getInnertubeClient as ReturnType<typeof vi.fn>).mockResolvedValueOnce({
      getInfo: getInfoMock,
    });
    const { mockFile } = mockGcs();

    await fetchTranscript(VIDEO_ID);

    expect(getInfoMock).not.toHaveBeenCalled();
    expect(mockFile.save).not.toHaveBeenCalled();

    // Pointer doc must not have been overwritten.
    const snap = await admin.firestore().doc(`transcripts/${VIDEO_ID}`).get();
    expect(snap.data()?.signedUrl).toBe("https://existing.signed.url");
  });
});

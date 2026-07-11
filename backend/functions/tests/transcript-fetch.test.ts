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
import {
  classifyError,
  EmptyTimedtextError,
  fetchTranscript,
} from "../src/transcript/fetch.js";

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

/**
 * Inline json3 fixture body — byte-equivalent to timedtext-json3-sample.json.
 * Produces segments [{start:0, text:"Hello world"}, {start:2.5, text:"Next sentence."}].
 */
const JSON3_FIXTURE_BODY = JSON.stringify({
  wireMagic: "pb3",
  events: [
    { tStartMs: 0, dDurationMs: 2500, segs: [{ utf8: "Hello world" }] },
    { tStartMs: 2500, dDurationMs: 3000, segs: [{ utf8: "Next sentence." }] },
  ],
});

/**
 * The blob text that both the primary path and the json3 fallback path produce
 * for the two-segment Hello-world fixture.
 */
const EXPECTED_BLOB_TEXT = "0.00 Hello world\n2.50 Next sentence.";

/**
 * Mocks getInnertubeClient for the fallback scenario:
 *   First call  → primary client whose getInfo() throws primaryThrowsMessage.
 *   Second call → fallback client whose getBasicInfo() returns one "en" caption track.
 */
function mockInnertubeWithFallback(primaryThrowsMessage: string) {
  // Primary innertube call
  (getInnertubeClient as ReturnType<typeof vi.fn>).mockResolvedValueOnce({
    getInfo: vi.fn(async () => {
      throw new Error(primaryThrowsMessage);
    }),
  });
  // Fallback innertube call
  (getInnertubeClient as ReturnType<typeof vi.fn>).mockResolvedValueOnce({
    getBasicInfo: vi.fn(async () => ({
      captions: {
        caption_tracks: [
          {
            language_code: "en",
            base_url: "https://timedtext.example.com/api/timedtext",
            is_auto_generated: false,
          },
        ],
      },
    })),
  });
}

/**
 * Stubs the global fetch used by fetchViaAndroidTimedtext.
 * ok=false simulates a non-200 response; empty body triggers EmptyTimedtextError.
 */
function mockGlobalFetch(body: string, ok = true, status = 200) {
  vi.stubGlobal(
    "fetch",
    vi.fn(async () => ({
      ok,
      status,
      text: async () => body,
    })),
  );
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
    vi.unstubAllGlobals();
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

  // ---
  // AC3: ANDROID-client fallback engagement + blob byte-equivalence
  // ---
  it("AC3 fallback: primary 400 → fallback succeeds → available, source=android-timedtext, blob byte-equivalent", async () => {
    mockInnertubeWithFallback("Request failed with status 400");
    mockGlobalFetch(JSON3_FIXTURE_BODY);
    const { mockFile } = mockGcs({ signedUrl: "https://storage.goog/t/signed-fb" });

    await fetchTranscript(VIDEO_ID);

    // GCS save must have been called exactly once with the expected text.
    expect(mockFile.save).toHaveBeenCalledOnce();
    const savedText = mockFile.save.mock.calls[0][0] as string;
    expect(savedText).toBe(EXPECTED_BLOB_TEXT);

    // Pointer doc must be available with source=android-timedtext.
    const snap = await admin.firestore().doc(`transcripts/${VIDEO_ID}`).get();
    const data = snap.data()!;
    expect(data.status).toBe("available");
    expect(data.source).toBe("android-timedtext");
    expect(data.signedUrl).toBe("https://storage.goog/t/signed-fb");
  });

  it("AC3 fallback: primary 400, fallback returns empty body → transient, errorClass=EMPTY_TIMEDTEXT", async () => {
    mockInnertubeWithFallback("Request failed with status 400");
    mockGlobalFetch(""); // empty body → EmptyTimedtextError
    const { mockFile } = mockGcs();

    await fetchTranscript(VIDEO_ID);

    expect(mockFile.save).not.toHaveBeenCalled();

    const snap = await admin.firestore().doc(`transcripts/${VIDEO_ID}`).get();
    const data = snap.data()!;
    expect(data.status).toBe("transient");
    expect(data.errorClass).toBe("EMPTY_TIMEDTEXT");
  });

  // ---
  // AC5: panel-not-found counter guard
  // ---
  it("AC5 counter: first PANEL_NOT_FOUND hit (no prior pointer) → transient, panelNotFoundCount=1", async () => {
    mockInnertube(null, "Transcript panel not found");
    const { mockFile } = mockGcs();

    await fetchTranscript(VIDEO_ID);

    expect(mockFile.save).not.toHaveBeenCalled();

    const snap = await admin.firestore().doc(`transcripts/${VIDEO_ID}`).get();
    const data = snap.data()!;
    expect(data.status).toBe("transient");
    expect(data.errorClass).toBe("PANEL_NOT_FOUND");
    expect(data.panelNotFoundCount).toBe(1);
  });

  it("AC5 counter: third PANEL_NOT_FOUND hit (prior count=2) → unavailable, panelNotFoundCount=3", async () => {
    // Pre-seed the pointer doc to simulate two prior PANEL_NOT_FOUND hits.
    await admin
      .firestore()
      .doc(`transcripts/${VIDEO_ID}`)
      .set({
        videoId: VIDEO_ID,
        status: "transient",
        errorClass: "PANEL_NOT_FOUND",
        panelNotFoundCount: 2,
        createdAt: admin.firestore.FieldValue.serverTimestamp(),
        updatedAt: admin.firestore.FieldValue.serverTimestamp(),
      });

    mockInnertube(null, "Transcript panel not found");
    const { mockFile } = mockGcs();

    await fetchTranscript(VIDEO_ID);

    expect(mockFile.save).not.toHaveBeenCalled();

    const snap = await admin.firestore().doc(`transcripts/${VIDEO_ID}`).get();
    const data = snap.data()!;
    expect(data.status).toBe("unavailable");
    expect(data.errorClass).toBe("PANEL_NOT_FOUND");
    expect(data.panelNotFoundCount).toBe(3);
  });

  it("AC5 counter: successful fetch after PANEL_NOT_FOUND history → available, no panelNotFoundCount", async () => {
    // Pre-seed the pointer doc with a prior PANEL_NOT_FOUND count.
    await admin
      .firestore()
      .doc(`transcripts/${VIDEO_ID}`)
      .set({
        videoId: VIDEO_ID,
        status: "transient",
        errorClass: "PANEL_NOT_FOUND",
        panelNotFoundCount: 2,
        createdAt: admin.firestore.FieldValue.serverTimestamp(),
        updatedAt: admin.firestore.FieldValue.serverTimestamp(),
      });

    const segments = [seg("0", "Hello world"), seg("2500", "Next sentence.")];
    mockInnertube(transcriptWith(segments));
    const { mockFile } = mockGcs();

    await fetchTranscript(VIDEO_ID);

    expect(mockFile.save).toHaveBeenCalledOnce();

    const snap = await admin.firestore().doc(`transcripts/${VIDEO_ID}`).get();
    const data = snap.data()!;
    expect(data.status).toBe("available");
    // The available doc does not carry panelNotFoundCount.
    expect(data.panelNotFoundCount).toBeUndefined();
  });
});

// ---------------------------------------------------------------------------
// AC4: classification table — pure unit tests, no emulator needed
// ---------------------------------------------------------------------------

describe("classifyError — classification table", () => {
  it("PANEL_NOT_FOUND: 'Transcript panel not found' → transient, not fallback-eligible", () => {
    const result = classifyError(new Error("Transcript panel not found"));
    expect(result.errorClass).toBe("PANEL_NOT_FOUND");
    expect(result.status).toBe("transient");
    expect(result.fallbackEligible).toBe(false);
  });

  it("LOGIN_REQUIRED: 'This video requires sign in' → unavailable, not fallback-eligible", () => {
    const result = classifyError(new Error("This video requires sign in"));
    expect(result.errorClass).toBe("LOGIN_REQUIRED");
    expect(result.status).toBe("unavailable");
    expect(result.fallbackEligible).toBe(false);
  });

  it("INNERTUBE_4XX: 'Request failed with status 400' → transient, fallback-eligible, httpStatus=400", () => {
    const result = classifyError(
      new Error("Request failed with status 400"),
    );
    expect(result.errorClass).toBe("INNERTUBE_4XX");
    expect(result.status).toBe("transient");
    expect(result.fallbackEligible).toBe(true);
    expect(result.httpStatus).toBe(400);
  });

  it("EMPTY_TIMEDTEXT: EmptyTimedtextError instance → transient, not fallback-eligible", () => {
    const result = classifyError(new EmptyTimedtextError());
    expect(result.errorClass).toBe("EMPTY_TIMEDTEXT");
    expect(result.status).toBe("transient");
    expect(result.fallbackEligible).toBe(false);
  });

  it("PARSE_FAILURE: SyntaxError → transient, not fallback-eligible", () => {
    const result = classifyError(new SyntaxError("Unexpected token"));
    expect(result.errorClass).toBe("PARSE_FAILURE");
    expect(result.status).toBe("transient");
    expect(result.fallbackEligible).toBe(false);
  });

  it("UNKNOWN: unrecognized error → transient, not fallback-eligible", () => {
    const result = classifyError(new Error("network timeout"));
    expect(result.errorClass).toBe("UNKNOWN");
    expect(result.status).toBe("transient");
    expect(result.fallbackEligible).toBe(false);
  });
});

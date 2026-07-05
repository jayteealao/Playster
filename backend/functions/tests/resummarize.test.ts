/**
 * Unit + emulator tests for resummarizeFromTranscript().
 *
 * Mocking strategy:
 *   - admin.storage() — vi.spyOn captures bucket/file/download calls.
 *     There is no GCS emulator; download is mock-only.
 *   - global fetch — vi.spyOn intercepts the OpenRouter chat-completions call.
 *   - Firestore — emulator at 127.0.0.1:8080; clearFirestore() resets between tests.
 *     Pointer doc reads and summary doc writes are asserted via admin.firestore().
 *
 * Three test cases:
 *   1. available path: pointer at status=available → summary written at status=completed.
 *   2. no-transcript guard: no pointer doc → no GCS, no fetch, no summary doc.
 *   3. in-flight guard: summary at status=running → early return, doc unchanged.
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

// --- Suppress logger output in tests ---
vi.mock("firebase-functions/logger", () => ({
  info: vi.fn(),
  warn: vi.fn(),
  error: vi.fn(),
  debug: vi.fn(),
}));

process.env.SUMMARIZER_URL = "http://summarizer.local";
process.env.SUMMARIZER_API_KEY = "test-openrouter-key";
process.env.TRANSCRIPT_BUCKET = "test-transcript-bucket";

import { resummarizeFromTranscript } from "../src/summarizer/resummarize.js";

const VIDEO_ID = "resummarize-test-vid-1";

// ---------------------------------------------------------------------------
// Fixture helpers
// ---------------------------------------------------------------------------

/** Installs a GCS mock on admin.storage() that returns the given blob text */
function mockGcs(blobText: string) {
  const mockFile = {
    download: vi.fn(async () => [Buffer.from(blobText, "utf-8")]),
  };
  const mockBucket = { file: vi.fn(() => mockFile) };
  vi.spyOn(admin, "storage").mockReturnValue({
    bucket: vi.fn(() => mockBucket),
  } as never);
  return { mockFile, mockBucket };
}

/** Builds a stubbed fetch function returning an OpenRouter chat-completions response */
function stubbedOpenRouterFetch(content: string, status = 200): typeof fetch {
  return (async () =>
    new Response(
      JSON.stringify({
        choices: [{ message: { content } }],
      }),
      {
        status,
        headers: { "Content-Type": "application/json" },
      },
    )) as unknown as typeof fetch;
}

/** Builds a stubbed fetch that returns a non-2xx HTTP error */
function errorFetch(status: number): typeof fetch {
  return (async () =>
    new Response(JSON.stringify({ error: "bad" }), {
      status,
      headers: { "Content-Type": "application/json" },
    })) as unknown as typeof fetch;
}

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

describe("resummarizeFromTranscript — emulator-backed", () => {
  beforeAll(() => {
    initAdminEmulator();
  });

  beforeEach(async () => {
    await clearFirestore();
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  // -------------------------------------------------------------------------
  // AC1: available path
  // -------------------------------------------------------------------------
  it("available path: reads transcript from GCS, calls OpenRouter, writes summary at status=completed", async () => {
    // Seed pointer doc at status=available.
    await admin.firestore().doc(`transcripts/${VIDEO_ID}`).set({
      videoId: VIDEO_ID,
      status: "available",
      gcsPath: `transcripts/${VIDEO_ID}.txt`,
      signedUrl: "https://signed.url/transcript",
      segments: [
        { start: 0, text: "Hello world" },
        { start: 2.5, text: "This is a test transcript." },
      ],
      createdAt: admin.firestore.FieldValue.serverTimestamp(),
      updatedAt: admin.firestore.FieldValue.serverTimestamp(),
    });

    const transcriptContent = "0.00 Hello world\n2.50 This is a test transcript.";
    const { mockFile } = mockGcs(transcriptContent);
    const summaryContent = "A"
      .repeat(150); // well above MIN_SUMMARY_CONTENT_CHARS=120

    await resummarizeFromTranscript(VIDEO_ID, {
      fetchImpl: stubbedOpenRouterFetch(summaryContent),
    });

    // GCS download was called once.
    expect(mockFile.download).toHaveBeenCalledOnce();

    // Summary doc written at status=completed with non-empty content.
    const snap = await admin.firestore().doc(`summaries/${VIDEO_ID}`).get();
    expect(snap.exists).toBe(true);
    const data = snap.data()!;
    expect(data.status).toBe("completed");
    expect(data.content).toBe(summaryContent);
    expect(data.model).toBe("free");
    expect(data.completedAt).toBeDefined();
  });

  // -------------------------------------------------------------------------
  // AC2: no-transcript guard
  // -------------------------------------------------------------------------
  it("no-transcript guard: no pointer doc → no GCS, no fetch, no summary doc written", async () => {
    // No pointer doc seeded.
    const storageSpy = vi.spyOn(admin, "storage");
    const fetchSpy = vi.fn();

    await resummarizeFromTranscript(VIDEO_ID, {
      fetchImpl: fetchSpy as unknown as typeof fetch,
    });

    // No GCS read, no fetch call.
    expect(storageSpy).not.toHaveBeenCalled();
    expect(fetchSpy).not.toHaveBeenCalled();

    // No summary doc created.
    const snap = await admin.firestore().doc(`summaries/${VIDEO_ID}`).get();
    expect(snap.exists).toBe(false);
  });

  it("no-transcript guard: pointer at status=unavailable → no GCS, no fetch, no summary doc written", async () => {
    await admin.firestore().doc(`transcripts/${VIDEO_ID}`).set({
      videoId: VIDEO_ID,
      status: "unavailable",
      createdAt: admin.firestore.FieldValue.serverTimestamp(),
      updatedAt: admin.firestore.FieldValue.serverTimestamp(),
    });

    const storageSpy = vi.spyOn(admin, "storage");
    const fetchSpy = vi.fn();

    await resummarizeFromTranscript(VIDEO_ID, {
      fetchImpl: fetchSpy as unknown as typeof fetch,
    });

    expect(storageSpy).not.toHaveBeenCalled();
    expect(fetchSpy).not.toHaveBeenCalled();

    const snap = await admin.firestore().doc(`summaries/${VIDEO_ID}`).get();
    expect(snap.exists).toBe(false);
  });

  // -------------------------------------------------------------------------
  // AC3: in-flight guard
  // -------------------------------------------------------------------------
  it("in-flight guard: summary at status=running → returns without overwriting doc or calling GCS/fetch", async () => {
    // Seed pointer doc at status=available.
    await admin.firestore().doc(`transcripts/${VIDEO_ID}`).set({
      videoId: VIDEO_ID,
      status: "available",
      gcsPath: `transcripts/${VIDEO_ID}.txt`,
      segments: [],
      createdAt: admin.firestore.FieldValue.serverTimestamp(),
      updatedAt: admin.firestore.FieldValue.serverTimestamp(),
    });

    // Seed an existing in-flight summary doc.
    await admin.firestore().doc(`summaries/${VIDEO_ID}`).set({
      videoId: VIDEO_ID,
      status: "running",
      model: "free",
      requestedAt: admin.firestore.FieldValue.serverTimestamp(),
    });

    const storageSpy = vi.spyOn(admin, "storage");
    const fetchSpy = vi.fn();

    await resummarizeFromTranscript(VIDEO_ID, {
      fetchImpl: fetchSpy as unknown as typeof fetch,
    });

    expect(storageSpy).not.toHaveBeenCalled();
    expect(fetchSpy).not.toHaveBeenCalled();

    // Doc must still be at status=running (not overwritten).
    const snap = await admin.firestore().doc(`summaries/${VIDEO_ID}`).get();
    expect(snap.data()?.status).toBe("running");
  });

  // -------------------------------------------------------------------------
  // Error path: OpenRouter returns non-2xx → summary at failed-transient
  // -------------------------------------------------------------------------
  it("OpenRouter HTTP error → summary doc written at status=failed-transient", async () => {
    await admin.firestore().doc(`transcripts/${VIDEO_ID}`).set({
      videoId: VIDEO_ID,
      status: "available",
      gcsPath: `transcripts/${VIDEO_ID}.txt`,
      segments: [{ start: 0, text: "test" }],
      createdAt: admin.firestore.FieldValue.serverTimestamp(),
      updatedAt: admin.firestore.FieldValue.serverTimestamp(),
    });

    mockGcs("0.00 test");

    await resummarizeFromTranscript(VIDEO_ID, {
      fetchImpl: errorFetch(503),
    });

    const snap = await admin.firestore().doc(`summaries/${VIDEO_ID}`).get();
    expect(snap.exists).toBe(true);
    expect(snap.data()?.status).toBe("failed-transient");
    expect(snap.data()?.errorCode).toBe("resummarize_http");
  });
});

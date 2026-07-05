/**
 * Unit + emulator tests for the transcript automation slice.
 *
 * Test coverage:
 *   AC1 — fetch-after-sync: fetchTranscriptsSafe() calls fetchTranscript once per video.
 *   AC2 — capped batch: backfill processes exactly TRANSCRIPT_BACKFILL_BATCH_SIZE per tick.
 *   AC3 — terminal-skip: videos at available/unavailable status are never passed to fetchTranscript.
 *   AC4 — transient retry eligibility: transient and missing pointer docs are included in the batch.
 *
 * Mocking strategy:
 *   - transcript/fetch.ts (fetchTranscript) — vi.mock at module level; call counts
 *     are asserted on the mock, the real fetch is never executed.
 *   - Firestore — emulator at 127.0.0.1:8080; clearFirestore() resets between tests.
 *   - acquireCronLock / releaseCronLock — not mocked; the emulator hosts the lock doc.
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

// Mock fetchTranscript so tests never hit YouTube or GCS.
// The mock is hoisted before src imports so the module cache resolves the mock.
vi.mock("../src/transcript/fetch.js", () => ({
  fetchTranscript: vi.fn(async () => {
    // Simulate the happy path: no-op (pointer doc already written).
  }),
}));

// Suppress logger output in tests.
vi.mock("firebase-functions/logger", () => ({
  info: vi.fn(),
  warn: vi.fn(),
  error: vi.fn(),
  debug: vi.fn(),
}));

process.env.TRANSCRIPT_BUCKET = "test-bucket";

import { fetchTranscript } from "../src/transcript/fetch.js";
import { fetchTranscriptBackfill } from "../src/transcript/backfill.js";
import { fetchTranscriptsSafe } from "../src/index.js";
import { TRANSCRIPT_BACKFILL_BATCH_SIZE } from "../src/transcript/constants.js";

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

/**
 * Seeds a video doc at `playlists/{playlistId}/videos/{videoId}`.
 * Each video uses a distinct playlist to exercise collectionGroup across parents.
 */
async function seedVideo(
  videoId: string,
  playlistId = `playlist-${videoId}`,
): Promise<void> {
  await admin
    .firestore()
    .doc(`playlists/${playlistId}/videos/${videoId}`)
    .set({ videoId, title: `Video ${videoId}` });
}

/**
 * Seeds a transcript pointer doc for a video at the given status.
 * A missing pointer doc (no call) means "not yet fetched".
 */
async function seedTranscriptPointer(
  videoId: string,
  status: string,
): Promise<void> {
  await admin
    .firestore()
    .doc(`transcripts/${videoId}`)
    .set({ videoId, status });
}

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

describe("transcript automation — emulator-backed", () => {
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
  // AC1: fetch-after-sync fires per new video
  // ---
  it("AC1 (fetch-after-sync): fetchTranscriptsSafe calls fetchTranscript once per video", async () => {
    const videoIds = ["v-sync-1", "v-sync-2", "v-sync-3"];

    await fetchTranscriptsSafe(videoIds);

    expect(fetchTranscript).toHaveBeenCalledTimes(3);
    expect(fetchTranscript).toHaveBeenCalledWith("v-sync-1");
    expect(fetchTranscript).toHaveBeenCalledWith("v-sync-2");
    expect(fetchTranscript).toHaveBeenCalledWith("v-sync-3");
  });

  it("AC1 (fetch-after-sync): fetchTranscriptsSafe is a no-op for empty/undefined input", async () => {
    await fetchTranscriptsSafe([]);
    await fetchTranscriptsSafe(undefined);

    expect(fetchTranscript).not.toHaveBeenCalled();
  });

  // ---
  // AC2: capped batch — backfill processes exactly TRANSCRIPT_BACKFILL_BATCH_SIZE per tick
  // ---
  it("AC2 (capped batch): backfill processes exactly BATCH_SIZE videos when more exist", async () => {
    // Seed more videos than the batch cap. All have no pointer doc → all eligible.
    const totalVideos = TRANSCRIPT_BACKFILL_BATCH_SIZE + 30;
    for (let i = 0; i < totalVideos; i++) {
      await seedVideo(`v-cap-${String(i).padStart(3, "0")}`);
    }

    const result = await fetchTranscriptBackfill();

    // Exactly the cap number should have been attempted.
    expect(result.attempted).toBe(TRANSCRIPT_BACKFILL_BATCH_SIZE);
    expect(fetchTranscript).toHaveBeenCalledTimes(TRANSCRIPT_BACKFILL_BATCH_SIZE);
  });

  // ---
  // AC3: terminal-skip — available/unavailable docs are never passed to fetchTranscript
  // ---
  it("AC3 (terminal-skip): terminal-status videos are never passed to fetchTranscript", async () => {
    // Seed 10 videos: 3 available, 2 unavailable, 5 no pointer doc.
    for (let i = 0; i < 10; i++) {
      await seedVideo(`v-term-${i}`);
    }
    await seedTranscriptPointer("v-term-0", "available");
    await seedTranscriptPointer("v-term-1", "available");
    await seedTranscriptPointer("v-term-2", "available");
    await seedTranscriptPointer("v-term-3", "unavailable");
    await seedTranscriptPointer("v-term-4", "unavailable");
    // v-term-5 through v-term-9 have no pointer doc → eligible.

    const result = await fetchTranscriptBackfill();

    // 5 terminal videos skipped, 5 eligible attempted.
    expect(result.skipped).toBe(5);
    expect(result.attempted).toBe(5);
    expect(fetchTranscript).toHaveBeenCalledTimes(5);

    // None of the terminal videos should have been fetched.
    const terminalIds = [
      "v-term-0",
      "v-term-1",
      "v-term-2",
      "v-term-3",
      "v-term-4",
    ];
    for (const id of terminalIds) {
      expect(fetchTranscript).not.toHaveBeenCalledWith(id);
    }
  });

  // ---
  // AC4: transient retry eligibility — transient and missing pointer docs are eligible
  // ---
  it("AC4 (transient retry): transient and missing pointer docs are both eligible for fetch", async () => {
    // Seed 10 videos: 5 with transient pointer docs, 5 with no pointer doc.
    for (let i = 0; i < 10; i++) {
      await seedVideo(`v-retry-${i}`);
    }
    for (let i = 0; i < 5; i++) {
      await seedTranscriptPointer(`v-retry-${i}`, "transient");
    }
    // v-retry-5 through v-retry-9 have no pointer doc.

    const result = await fetchTranscriptBackfill();

    // All 10 should be eligible (none terminal).
    expect(result.skipped).toBe(0);
    expect(result.attempted).toBe(10);
    expect(fetchTranscript).toHaveBeenCalledTimes(10);
  });
});

import { beforeAll, beforeEach, describe, expect, it } from "vitest";
import * as admin from "firebase-admin";
import { clearFirestore, initAdminEmulator } from "./helpers/admin";

/**
 * Seeds a video doc at `playlists/{playlistId}/videos/{videoId}`.
 * Uses distinct playlist IDs per video to exercise the collectionGroup query
 * across multiple parent documents.
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
 * Pre-creates a summary doc for `videoId` so `enqueueAutoSummary` skips it.
 */
async function seedSummary(
  videoId: string,
  status = "completed",
): Promise<void> {
  await admin
    .firestore()
    .doc(`summaries/${videoId}`)
    .set({ videoId, status });
}

describe("reconcileAll — emulator-backed", () => {
  beforeAll(() => {
    initAdminEmulator();
  });

  beforeEach(async () => {
    await clearFirestore();
  });

  it("enqueues all videos when no summary docs exist", async () => {
    const { reconcileAll } = await import("../src/summarizer/reconcile.js");

    await seedVideo("v1", "playlist-alpha");
    await seedVideo("v2", "playlist-beta");
    await seedVideo("v3", "playlist-gamma");
    await seedVideo("v4", "playlist-delta");
    await seedVideo("v5", "playlist-epsilon");

    const result = await reconcileAll();

    expect(result.total).toBe(5);
    expect(result.enqueued).toBe(5);
    expect(result.skipped).toBe(0);

    // Spot-check one summary doc was created at status=queued.
    const snap = await admin.firestore().doc("summaries/v1").get();
    expect(snap.exists).toBe(true);
    expect(snap.data()?.status).toBe("queued");
  });

  it("enqueues only videos missing a summary doc", async () => {
    const { reconcileAll } = await import("../src/summarizer/reconcile.js");

    await seedVideo("v1");
    await seedVideo("v2");
    await seedVideo("v3");
    await seedVideo("v4");
    await seedVideo("v5");

    // Pre-seed summary docs for 3 of the 5 videos.
    await seedSummary("v1", "completed");
    await seedSummary("v2", "failed-transient");
    await seedSummary("v3", "queued");

    const result = await reconcileAll();

    expect(result.total).toBe(5);
    expect(result.enqueued).toBe(2);
    expect(result.skipped).toBe(3);

    // v4 and v5 should now have summary docs.
    const v4 = await admin.firestore().doc("summaries/v4").get();
    expect(v4.exists).toBe(true);
    expect(v4.data()?.status).toBe("queued");

    // v1 (completed) must not have been overwritten.
    const v1 = await admin.firestore().doc("summaries/v1").get();
    expect(v1.data()?.status).toBe("completed");
  });

  it("is idempotent: second run skips all", async () => {
    const { reconcileAll } = await import("../src/summarizer/reconcile.js");

    await seedVideo("v1");
    await seedVideo("v2");
    await seedVideo("v3");

    const first = await reconcileAll();
    expect(first.enqueued).toBe(3);
    expect(first.skipped).toBe(0);

    const second = await reconcileAll();
    expect(second.total).toBe(3);
    expect(second.enqueued).toBe(0);
    expect(second.skipped).toBe(3);
  });
});

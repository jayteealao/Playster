import { beforeAll, beforeEach, describe, expect, it, vi } from "vitest";
import * as admin from "firebase-admin";
import { clearFirestore, initAdminEmulator } from "./helpers/admin";
import {
  DISPATCHER_BATCH_SIZE,
  DISPATCHER_LOCK_TTL_MS,
} from "../src/summarizer/constants.js";

async function seedFailedTransient(videoIds: string[]): Promise<void> {
  const db = admin.firestore();
  const batch = db.batch();
  let i = 0;
  for (const id of videoIds) {
    // Parent video doc — dispatchSummary's collection-group videoExists()
    // check requires this to exist.
    batch.set(db.doc(`playlists/p1/videos/${id}`), { videoId: id });
    batch.set(db.doc(`summaries/${id}`), {
      videoId: id,
      status: "failed-transient",
      model: "free",
      errorCode: "dispatch_5xx",
      requestedAt: admin.firestore.Timestamp.fromMillis(Date.now() - i * 1_000),
    });
    i += 1;
  }
  await batch.commit();
}

function counting2xxFetch(): {
  fetchImpl: typeof fetch;
  spy: ReturnType<typeof vi.fn>;
} {
  const spy = vi.fn();
  const fetchImpl = (async (_u: RequestInfo | URL, _init?: RequestInit) => {
    spy();
    return new Response(
      JSON.stringify({ id: `job-${spy.mock.calls.length}` }),
      {
        status: 200,
        headers: { "Content-Type": "application/json" },
      },
    );
  }) as unknown as typeof fetch;
  return { fetchImpl, spy };
}

describe("summary retry cron — emulator-backed", () => {
  beforeAll(() => {
    initAdminEmulator();
    process.env.SUMMARIZER_URL = "http://summarizer.local";
    process.env.SUMMARIZER_API_KEY = "test-key";
  });

  beforeEach(async () => {
    await clearFirestore();
  });

  // AC-13: failed-transient → re-dispatched (pending → running).
  it("re-dispatches failed-transient docs and transitions them to running", async () => {
    const { retryFailedTransient } = await import("../src/summarizer/retry.js");
    await seedFailedTransient(["v1", "v2"]);
    const { fetchImpl, spy } = counting2xxFetch();
    const realFetch = globalThis.fetch;
    (globalThis as { fetch: typeof fetch }).fetch = fetchImpl;
    try {
      const result = await retryFailedTransient();
      expect(result.attempted).toBe(2);
      expect(result.dispatched).toBe(2);
      expect(result.quotaExhausted).toBe(false);
      expect(spy.mock.calls.length).toBe(2);
    } finally {
      (globalThis as { fetch: typeof fetch }).fetch = realFetch;
    }

    for (const id of ["v1", "v2"]) {
      const summary = await admin.firestore().doc(`summaries/${id}`).get();
      expect(summary.data()?.status).toBe("running");
      expect(summary.data()?.summarizerJobId).toBeTruthy();
      const secret = await admin.firestore().doc(`webhook_secrets/${id}`).get();
      expect(secret.exists).toBe(true);
      expect(typeof secret.data()?.secret).toBe("string");
      expect((secret.data()?.secret as string).length).toBeGreaterThan(0);
    }
  });

  // AC-15: quota awareness mid-batch.
  it("stops dispatching when daily quota is exhausted mid-batch", async () => {
    const { retryFailedTransient } = await import("../src/summarizer/retry.js");
    // Pre-seed quota with 3 daily slots left.
    const today = new Date().toISOString().slice(0, 10);
    await admin.firestore().doc("quota/openrouter").set({
      date: today,
      requestCount: 997,
      dailyLimit: 1000,
      perMinuteLimit: 20,
      recentTimestamps: [],
    });
    await seedFailedTransient(["v1", "v2", "v3", "v4", "v5"]);
    const { fetchImpl } = counting2xxFetch();
    const realFetch = globalThis.fetch;
    (globalThis as { fetch: typeof fetch }).fetch = fetchImpl;
    try {
      const result = await retryFailedTransient();
      expect(result.attempted).toBe(5);
      expect(result.dispatched).toBeGreaterThan(0);
      expect(result.dispatched).toBeLessThanOrEqual(3);
      expect(result.quotaExhausted).toBe(true);
    } finally {
      (globalThis as { fetch: typeof fetch }).fetch = realFetch;
    }

    const remaining = await admin
      .firestore()
      .collection("summaries")
      .where("status", "==", "failed-transient")
      .get();
    expect(remaining.docs.length).toBeGreaterThanOrEqual(2);

    // F-10(a): no doc must be stranded at status="pending"
    const pendingDocs = await admin
      .firestore()
      .collection("summaries")
      .where("status", "==", "pending")
      .get();
    expect(pendingDocs.docs.length).toBe(0);

    // F-10(b): rolled-back docs have errorCode="retry_quota_exhausted"
    for (const doc of remaining.docs) {
      expect(doc.data().errorCode).toBe("retry_quota_exhausted");
    }
  });

  // Batch cap: at most DISPATCHER_BATCH_SIZE docs per firing.
  it("caps a single firing at DISPATCHER_BATCH_SIZE attempts", async () => {
    const { retryFailedTransient } = await import("../src/summarizer/retry.js");
    const ids = Array.from(
      { length: DISPATCHER_BATCH_SIZE + 5 },
      (_, i) => `bulk-${i}`,
    );
    await seedFailedTransient(ids);
    // Pre-cap quota so retry HTTP attempts short-circuit cheaply — we only
    // care that the query selector caps at DISPATCHER_BATCH_SIZE.
    const today = new Date().toISOString().slice(0, 10);
    await admin.firestore().doc("quota/openrouter").set({
      date: today,
      requestCount: 1000,
      dailyLimit: 1000,
      perMinuteLimit: 20,
      recentTimestamps: [],
    });
    const result = await retryFailedTransient();
    expect(result.attempted).toBe(DISPATCHER_BATCH_SIZE);
    expect(result.quotaExhausted).toBe(true);
  }, 60_000);

  // AC-16 (retry side): stale lock reclaim.
  it("acquireRetryLock reclaims a lock whose TTL has expired", async () => {
    const { acquireRetryLock } = await import("../src/summarizer/retry.js");
    const staleAcquiredAt = Date.now() - (DISPATCHER_LOCK_TTL_MS + 1_000);
    await admin.firestore().doc("locks/summaryRetry").set({
      acquiredAt: staleAcquiredAt,
      holder: "stale-instance",
    });

    const acquired = await acquireRetryLock();
    expect(acquired).toBeTruthy();

    const snap = await admin.firestore().doc("locks/summaryRetry").get();
    const newAcquiredAt = snap.data()?.acquiredAt as number | undefined;
    expect(newAcquiredAt).toBeDefined();
    expect(newAcquiredAt!).toBeGreaterThan(staleAcquiredAt);
  });

  it("acquireRetryLock returns true once, false on overlap, true after release", async () => {
    const { acquireRetryLock, releaseRetryLock } =
      await import("../src/summarizer/retry.js");
    const token = await acquireRetryLock();
    expect(token).toBeTruthy();
    expect(await acquireRetryLock()).toBe(false);
    await releaseRetryLock(token as string);
    expect(await acquireRetryLock()).toBeTruthy();
  });

  // Negative companion to AC-13: completed docs are not in the retry set.
  it("does not re-dispatch docs that are already completed", async () => {
    const { retryFailedTransient } = await import("../src/summarizer/retry.js");
    // Seed a completed doc — should be ignored by the query selector.
    await admin
      .firestore()
      .doc("summaries/v-done")
      .set({
        videoId: "v-done",
        status: "completed",
        model: "free",
        summarizerJobId: "job-done",
        requestedAt: admin.firestore.Timestamp.fromMillis(Date.now()),
      });
    const spy = vi.fn();
    const fetchImpl = (async () => {
      spy();
      return new Response("{}", { status: 200 });
    }) as unknown as typeof fetch;
    const realFetch = globalThis.fetch;
    (globalThis as { fetch: typeof fetch }).fetch = fetchImpl;
    try {
      const result = await retryFailedTransient();
      expect(result).toEqual({
        attempted: 0,
        dispatched: 0,
        quotaExhausted: false,
      });
      expect(spy).not.toHaveBeenCalled();
    } finally {
      (globalThis as { fetch: typeof fetch }).fetch = realFetch;
    }

    const done = await admin.firestore().doc("summaries/v-done").get();
    expect(done.data()?.status).toBe("completed");
  });

  it("returns early when the lock is held by another instance", async () => {
    const { retryFailedTransient, acquireRetryLock } =
      await import("../src/summarizer/retry.js");
    expect(await acquireRetryLock()).toBeTruthy();
    const result = await retryFailedTransient();
    expect(result).toEqual({
      attempted: 0,
      dispatched: 0,
      quotaExhausted: false,
    });
  });
});

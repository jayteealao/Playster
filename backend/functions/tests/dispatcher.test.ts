import {
  beforeAll,
  beforeEach,
  describe,
  expect,
  it,
  vi,
} from "vitest";
import * as admin from "firebase-admin";
import { clearFirestore, initAdminEmulator } from "./helpers/admin";

async function seedQueued(videoIds: string[]): Promise<void> {
  const db = admin.firestore();
  const batch = db.batch();
  for (const id of videoIds) {
    batch.set(db.doc(`playlists/p1/videos/${id}`), { videoId: id });
    batch.set(db.doc(`summaries/${id}`), {
      videoId: id,
      status: "queued",
      model: "free",
      webhookSecret: "",
      requestedAt: admin.firestore.FieldValue.serverTimestamp(),
    });
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
    return new Response(JSON.stringify({ id: `job-${spy.mock.calls.length}` }), {
      status: 200,
      headers: { "Content-Type": "application/json" },
    });
  }) as unknown as typeof fetch;
  return { fetchImpl, spy };
}

describe("summary dispatcher — emulator-backed", () => {
  beforeAll(() => {
    initAdminEmulator();
    process.env.SUMMARIZER_URL = "http://summarizer.local";
    process.env.SUMMARIZER_API_KEY = "test-key";
  });

  beforeEach(async () => {
    await clearFirestore();
  });

  it("acquireDispatcherLock returns true once, false on overlap", async () => {
    const { acquireDispatcherLock, releaseDispatcherLock } = await import(
      "../src/summarizer/dispatcher.js"
    );
    expect(await acquireDispatcherLock()).toBe(true);
    expect(await acquireDispatcherLock()).toBe(false);
    await releaseDispatcherLock();
    expect(await acquireDispatcherLock()).toBe(true);
  });

  it("drainSummaryQueue respects per-minute cap", async () => {
    const { drainSummaryQueue } = await import("../src/summarizer/dispatcher.js");
    const dispatch = await import("../src/summarizer/dispatch.js");
    const { fetchImpl, spy } = counting2xxFetch();
    // Patch dispatchSummary's fetch by monkey-patching globalThis.fetch — the
    // dispatcher calls dispatchSummary which uses opts.fetchImpl ?? fetch.
    const realFetch = globalThis.fetch;
    (globalThis as { fetch: typeof fetch }).fetch = fetchImpl;
    try {
      // Pre-seed quota: 18 already used in the current minute, so only 2 slots
      // remain inside the per-minute cap of 20.
      const today = new Date().toISOString().slice(0, 10);
      const now = Date.now();
      await admin.firestore().doc("quota/openrouter").set({
        date: today,
        requestCount: 18,
        dailyLimit: 1000,
        perMinuteLimit: 20,
        recentTimestamps: Array.from({ length: 18 }, (_, i) => now - i * 100),
      });
      await seedQueued(["v1", "v2", "v3", "v4", "v5"]);
      const result = await drainSummaryQueue();
      expect(result.dispatched).toBeLessThanOrEqual(2);
      expect(spy.mock.calls.length).toBeLessThanOrEqual(2);
    } finally {
      (globalThis as { fetch: typeof fetch }).fetch = realFetch;
    }
    // Reference dispatch to keep the import meaningful.
    expect(typeof dispatch.dispatchSummary).toBe("function");
  });

  it("returns early when lock is held by another instance", async () => {
    const { drainSummaryQueue, acquireDispatcherLock } = await import(
      "../src/summarizer/dispatcher.js"
    );
    expect(await acquireDispatcherLock()).toBe(true);
    const result = await drainSummaryQueue();
    expect(result).toEqual({ attempted: 0, dispatched: 0 });
  });
});

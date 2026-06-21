import { beforeAll, beforeEach, describe, expect, it, vi } from "vitest";
import * as admin from "firebase-admin";
import { clearFirestore, initAdminEmulator } from "./helpers/admin";
import { DISPATCHER_LOCK_TTL_MS } from "../src/summarizer/constants.js";

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
    const { acquireDispatcherLock, releaseDispatcherLock } =
      await import("../src/summarizer/dispatcher-cron.js");
    const token = await acquireDispatcherLock();
    expect(token).toBeTruthy();
    expect(await acquireDispatcherLock()).toBe(false);
    await releaseDispatcherLock(token as string);
    expect(await acquireDispatcherLock()).toBeTruthy();
  });

  it("drainSummaryQueue respects per-minute cap", async () => {
    // M-12: use fetchImpl injection rather than globalThis.fetch monkey-patch.
    // drainSummaryQueue calls dispatchSummary internally without forwarding
    // fetchImpl, so we seed quota close to the per-minute cap and assert the
    // dispatcher honours the budget without needing to intercept HTTP.
    const { drainSummaryQueue } =
      await import("../src/summarizer/dispatcher-cron.js");
    const dispatch = await import("../src/summarizer/dispatch.js");
    const { fetchImpl, spy } = counting2xxFetch();

    // Temporarily wire the module-level fetch so dispatchSummary picks it up.
    // This is the only remaining place we do this — the test below replaces
    // the monkey-patch pattern with fetchImpl injection where possible.
    const realFetch = globalThis.fetch;
    (globalThis as { fetch: typeof fetch }).fetch = fetchImpl;
    try {
      // Pre-seed quota: 18 already used in the current minute, so only 2 slots
      // remain inside the per-minute cap of 20.
      const today = new Date().toISOString().slice(0, 10);
      const now = Date.now();
      await admin
        .firestore()
        .doc("quota/openrouter")
        .set({
          date: today,
          requestCount: 18,
          dailyLimit: 1000,
          perMinuteLimit: 20,
          recentTimestamps: Array.from({ length: 18 }, (_, i) => now - i * 100),
        });
      await seedQueued(["v1", "v2", "v3", "v4", "v5"]);
      const result = await drainSummaryQueue();
      // Lower bound guards against a regression where queued docs are treated
      // as non-redispatchable and the dispatcher silently no-ops (dispatched=0
      // would otherwise pass the <= 2 cap vacuously). With 2 of 20 per-minute
      // slots remaining, at least one — and at most two — queued docs go out.
      expect(result.dispatched).toBeGreaterThanOrEqual(1);
      expect(result.dispatched).toBeLessThanOrEqual(2);
      expect(spy.mock.calls.length).toBeGreaterThanOrEqual(1);
      expect(spy.mock.calls.length).toBeLessThanOrEqual(2);
    } finally {
      (globalThis as { fetch: typeof fetch }).fetch = realFetch;
    }
    // Reference dispatch to keep the import meaningful.
    expect(typeof dispatch.dispatchSummary).toBe("function");
  });

  // M-12: demonstrate fetchImpl injection path for dispatchSummary directly —
  // no globalThis.fetch mutation needed.
  it("dispatchSummary accepts fetchImpl without globalThis.fetch mutation", async () => {
    const { dispatchSummary } = await import("../src/summarizer/dispatch.js");
    const { fetchImpl, spy } = counting2xxFetch();
    await seedQueued(["v-inject"]);
    await dispatchSummary("v-inject", "free", { fetchImpl });
    expect(spy.mock.calls.length).toBe(1);
    const doc = await admin.firestore().doc("summaries/v-inject").get();
    expect(doc.data()?.status).toBe("running");
  });

  it("returns early when lock is held by another instance", async () => {
    const { drainSummaryQueue, acquireDispatcherLock } =
      await import("../src/summarizer/dispatcher-cron.js");
    expect(await acquireDispatcherLock()).toBeTruthy();
    const result = await drainSummaryQueue();
    expect(result).toEqual({ attempted: 0, dispatched: 0 });
  });

  // R-11: stale lock beyond TTL is reclaimed by a new instance.
  it("acquireDispatcherLock reclaims a lock whose TTL has expired", async () => {
    const { acquireDispatcherLock } =
      await import("../src/summarizer/dispatcher-cron.js");
    // Seed the lock doc with an acquiredAt that is beyond the TTL.
    const staleAcquiredAt = Date.now() - (DISPATCHER_LOCK_TTL_MS + 1_000);
    await admin.firestore().doc("locks/summaryDispatcher").set({
      acquiredAt: staleAcquiredAt,
      holder: "stale-instance",
    });

    const acquired = await acquireDispatcherLock();
    expect(acquired).toBeTruthy();

    // The new lock doc should carry a fresh acquiredAt (strictly after the
    // stale value — sanity-check with a generous 5-second window).
    const snap = await admin.firestore().doc("locks/summaryDispatcher").get();
    const newAcquiredAt = snap.data()?.acquiredAt as number | undefined;
    expect(newAcquiredAt).toBeDefined();
    expect(newAcquiredAt!).toBeGreaterThan(staleAcquiredAt);
  });
});

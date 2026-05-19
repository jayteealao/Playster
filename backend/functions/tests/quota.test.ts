import {
  beforeAll,
  beforeEach,
  describe,
  expect,
  it,
} from "vitest";
import * as admin from "firebase-admin";
import { clearFirestore, initAdminEmulator } from "./helpers/admin";

/**
 * Emulator-backed. Requires `firebase emulators:start --only firestore` (port
 * 8080) before running. The slice's verify stage handles boot.
 */
describe("quota module — emulator-backed", () => {
  beforeAll(() => {
    initAdminEmulator();
  });

  beforeEach(async () => {
    await clearFirestore();
  });

  it("reserves a slot and persists the increment", async () => {
    const { reserveOpenRouterQuotaSlot } = await import("../src/summarizer/quota.js");
    await reserveOpenRouterQuotaSlot();
    const snap = await admin.firestore().doc("quota/openrouter").get();
    expect(snap.exists).toBe(true);
    const data = snap.data();
    expect(data?.requestCount).toBe(1);
    expect((data?.recentTimestamps ?? []).length).toBe(1);
  });

  it("throws resource-exhausted when daily cap reached", async () => {
    const { reserveOpenRouterQuotaSlot } = await import("../src/summarizer/quota.js");
    const today = new Date().toISOString().slice(0, 10);
    await admin.firestore().doc("quota/openrouter").set({
      date: today,
      requestCount: 1000,
      dailyLimit: 1000,
      perMinuteLimit: 20,
      recentTimestamps: [],
    });
    await expect(reserveOpenRouterQuotaSlot()).rejects.toMatchObject({
      code: "resource-exhausted",
    });
  });

  it("throws resource-exhausted when per-minute cap reached", async () => {
    const { reserveOpenRouterQuotaSlot } = await import("../src/summarizer/quota.js");
    const today = new Date().toISOString().slice(0, 10);
    const now = Date.now();
    await admin.firestore().doc("quota/openrouter").set({
      date: today,
      requestCount: 5,
      dailyLimit: 1000,
      perMinuteLimit: 20,
      recentTimestamps: Array.from({ length: 20 }, (_, i) => now - i * 100),
    });
    await expect(reserveOpenRouterQuotaSlot()).rejects.toMatchObject({
      code: "resource-exhausted",
    });
  });

  it("resets counters on day-rollover atomically", async () => {
    const { reserveOpenRouterQuotaSlot } = await import("../src/summarizer/quota.js");
    await admin.firestore().doc("quota/openrouter").set({
      date: "2024-01-01",
      requestCount: 999,
      dailyLimit: 1000,
      perMinuteLimit: 20,
      recentTimestamps: [],
    });
    await reserveOpenRouterQuotaSlot();
    const data = (await admin.firestore().doc("quota/openrouter").get()).data();
    const today = new Date().toISOString().slice(0, 10);
    expect(data?.date).toBe(today);
    expect(data?.requestCount).toBe(1);
  });

  it("releaseOpenRouterQuotaSlot decrements the count", async () => {
    const { reserveOpenRouterQuotaSlot, releaseOpenRouterQuotaSlot } =
      await import("../src/summarizer/quota.js");
    await reserveOpenRouterQuotaSlot();
    await releaseOpenRouterQuotaSlot();
    const data = (await admin.firestore().doc("quota/openrouter").get()).data();
    expect(data?.requestCount).toBe(0);
  });

  it("N concurrent reservations honor the hard cap", async () => {
    const { reserveOpenRouterQuotaSlot } = await import("../src/summarizer/quota.js");
    const today = new Date().toISOString().slice(0, 10);
    await admin.firestore().doc("quota/openrouter").set({
      date: today,
      requestCount: 995,
      dailyLimit: 1000,
      perMinuteLimit: 100,
      recentTimestamps: [],
    });
    const results = await Promise.allSettled(
      Array.from({ length: 10 }, () => reserveOpenRouterQuotaSlot()),
    );
    const successes = results.filter((r) => r.status === "fulfilled").length;
    expect(successes).toBe(5);
    const data = (await admin.firestore().doc("quota/openrouter").get()).data();
    expect(data?.requestCount).toBe(1000);
  });
});

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

const VIDEO_ID = "video-1";

async function seedVideo(): Promise<void> {
  await admin
    .firestore()
    .doc(`playlists/p1/videos/${VIDEO_ID}`)
    .set({ videoId: VIDEO_ID, title: "test" });
}

function stubbedFetch(
  status: number,
  body: unknown = { id: "summarizer-job-1" },
): typeof fetch {
  return (async (_url: RequestInfo | URL, _init?: RequestInit) => {
    return new Response(JSON.stringify(body), {
      status,
      headers: { "Content-Type": "application/json" },
    });
  }) as unknown as typeof fetch;
}

function rejectingFetch(): typeof fetch {
  return (async () => {
    throw new Error("network down");
  }) as unknown as typeof fetch;
}

describe("dispatchSummary — emulator-backed", () => {
  beforeAll(() => {
    initAdminEmulator();
    // Set SUMMARIZER_URL/SUMMARIZER_API_KEY via env so defineSecret().value()
    // resolves locally. firebase-functions/params reads from process.env in
    // emulator/test mode.
    process.env.SUMMARIZER_URL = "http://summarizer.local";
    process.env.SUMMARIZER_API_KEY = "test-key";
  });

  beforeEach(async () => {
    await clearFirestore();
    await seedVideo();
  });

  it("happy path: pending → running with summarizerJobId", async () => {
    const { dispatchSummary } = await import("../src/summarizer/dispatch.js");
    const result = await dispatchSummary(VIDEO_ID, "free", {
      fetchImpl: stubbedFetch(200, { id: "job-xyz" }),
    });
    expect(result).toEqual({ summaryId: VIDEO_ID });
    const doc = await admin.firestore().doc(`summaries/${VIDEO_ID}`).get();
    expect(doc.data()?.status).toBe("running");
    expect(doc.data()?.summarizerJobId).toBe("job-xyz");
    const quota = await admin.firestore().doc("quota/openrouter").get();
    expect(quota.data()?.requestCount).toBe(1);
  });

  it("idempotency: in-flight doc returns without re-dispatch", async () => {
    const { dispatchSummary } = await import("../src/summarizer/dispatch.js");
    await admin.firestore().doc(`summaries/${VIDEO_ID}`).set({
      videoId: VIDEO_ID,
      status: "running",
      model: "free",
    });
    const fetchSpy = vi.fn();
    const result = await dispatchSummary(VIDEO_ID, "free", {
      fetchImpl: fetchSpy as unknown as typeof fetch,
    });
    expect(result).toEqual({ summaryId: VIDEO_ID });
    expect(fetchSpy).not.toHaveBeenCalled();
  });

  it("4xx response → failed-permanent + quota released", async () => {
    const { dispatchSummary } = await import("../src/summarizer/dispatch.js");
    await dispatchSummary(VIDEO_ID, "free", {
      fetchImpl: stubbedFetch(400, { error: "bad request" }),
    });
    const doc = await admin.firestore().doc(`summaries/${VIDEO_ID}`).get();
    expect(doc.data()?.status).toBe("failed-permanent");
    const quota = await admin.firestore().doc("quota/openrouter").get();
    expect(quota.data()?.requestCount).toBe(0);
  });

  it("5xx response → failed-transient + quota released", async () => {
    const { dispatchSummary } = await import("../src/summarizer/dispatch.js");
    await dispatchSummary(VIDEO_ID, "free", {
      fetchImpl: stubbedFetch(503),
    });
    const doc = await admin.firestore().doc(`summaries/${VIDEO_ID}`).get();
    expect(doc.data()?.status).toBe("failed-transient");
    const quota = await admin.firestore().doc("quota/openrouter").get();
    expect(quota.data()?.requestCount).toBe(0);
  });

  it("network error → failed-transient + quota released", async () => {
    const { dispatchSummary } = await import("../src/summarizer/dispatch.js");
    await dispatchSummary(VIDEO_ID, "free", {
      fetchImpl: rejectingFetch(),
    });
    const doc = await admin.firestore().doc(`summaries/${VIDEO_ID}`).get();
    expect(doc.data()?.status).toBe("failed-transient");
    const quota = await admin.firestore().doc("quota/openrouter").get();
    expect(quota.data()?.requestCount).toBe(0);
  });

  it("missing video → throws not-found", async () => {
    const { dispatchSummary } = await import("../src/summarizer/dispatch.js");
    await expect(
      dispatchSummary("does-not-exist", "free", {
        fetchImpl: stubbedFetch(200),
      }),
    ).rejects.toMatchObject({ code: "not-found" });
  });

  // M-09: quota at daily cap → resource-exhausted propagates; no summaries doc
  // created (or if the idempotency transaction already wrote a pending doc it
  // remains in the pre-dispatch state, not running).
  it("quota at daily cap → resource-exhausted + no running summary doc", async () => {
    const { dispatchSummary } = await import("../src/summarizer/dispatch.js");
    const today = new Date().toISOString().slice(0, 10);
    await admin.firestore().doc("quota/openrouter").set({
      date: today,
      requestCount: 1000,
      dailyLimit: 1000,
      perMinuteLimit: 20,
      recentTimestamps: [],
    });

    await expect(
      dispatchSummary(VIDEO_ID, "free", {
        fetchImpl: stubbedFetch(200, { id: "job-should-not-reach" }),
      }),
    ).rejects.toMatchObject({ code: "resource-exhausted" });

    // The summary doc should not have reached "running" status.
    const doc = await admin.firestore().doc(`summaries/${VIDEO_ID}`).get();
    if (doc.exists) {
      // An idempotency doc may have been written as "pending" before the quota
      // check, but it must not be "running".
      expect(doc.data()?.status).not.toBe("running");
    }
  });
});

import { beforeAll, beforeEach, describe, expect, it } from "vitest";
import * as admin from "firebase-admin";
import { clearFirestore, initAdminEmulator } from "./helpers/admin";

describe("enqueueAutoSummary — emulator-backed", () => {
  beforeAll(() => {
    initAdminEmulator();
  });

  beforeEach(async () => {
    await clearFirestore();
  });

  it("no-op on empty input", async () => {
    const { enqueueAutoSummary } =
      await import("../src/summarizer/autoEnqueue.js");
    const result = await enqueueAutoSummary([]);
    expect(result).toEqual({ enqueued: 0, skipped: 0 });
  });

  it("enqueues fresh videoIds with status=queued", async () => {
    const { enqueueAutoSummary } =
      await import("../src/summarizer/autoEnqueue.js");
    const result = await enqueueAutoSummary(["v1", "v2"]);
    expect(result).toEqual({ enqueued: 2, skipped: 0 });
    const v1 = await admin.firestore().doc("summaries/v1").get();
    expect(v1.exists).toBe(true);
    expect(v1.data()?.status).toBe("queued");
    expect(v1.data()?.videoId).toBe("v1");
  });

  it("skips ids whose summary already exists", async () => {
    const { enqueueAutoSummary } =
      await import("../src/summarizer/autoEnqueue.js");
    await admin.firestore().doc("summaries/v1").set({
      videoId: "v1",
      status: "completed",
    });
    const result = await enqueueAutoSummary(["v1", "v2"]);
    expect(result).toEqual({ enqueued: 1, skipped: 1 });
  });

  it("is idempotent across repeated invocations", async () => {
    const { enqueueAutoSummary } =
      await import("../src/summarizer/autoEnqueue.js");
    await enqueueAutoSummary(["v1", "v2"]);
    const result = await enqueueAutoSummary(["v1", "v2"]);
    expect(result).toEqual({ enqueued: 0, skipped: 2 });
  });

  it("dedupes within a single call", async () => {
    const { enqueueAutoSummary } =
      await import("../src/summarizer/autoEnqueue.js");
    const result = await enqueueAutoSummary(["v1", "v1", "v1"]);
    expect(result).toEqual({ enqueued: 1, skipped: 0 });
  });
});

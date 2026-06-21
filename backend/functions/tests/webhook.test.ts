import { beforeAll, beforeEach, describe, expect, it } from "vitest";
import * as admin from "firebase-admin";
import { clearFirestore, initAdminEmulator } from "./helpers/admin";
import { signWebhook } from "./helpers/signWebhook";

const VIDEO_ID = "abc123";
const SECRET = "test-webhook-secret-with-many-bytes";

async function seedRunningSummary(secret = SECRET): Promise<void> {
  await admin.firestore().doc(`summaries/${VIDEO_ID}`).set({
    videoId: VIDEO_ID,
    status: "running",
    model: "free",
    requestedAt: admin.firestore.FieldValue.serverTimestamp(),
  });
  await admin.firestore().doc(`webhook_secrets/${VIDEO_ID}`).set({
    secret,
    createdAt: admin.firestore.FieldValue.serverTimestamp(),
  });
}

describe("processSummaryWebhook — emulator-backed", () => {
  beforeAll(() => {
    initAdminEmulator();
  });

  beforeEach(async () => {
    await clearFirestore();
  });

  it("AC-7: valid signature + known job → 204 + status=completed", async () => {
    const { processSummaryWebhook } =
      await import("../src/summarizer/webhook.js");
    await seedRunningSummary();
    const payload = {
      client_job_id: VIDEO_ID,
      status: "completed",
      result: { summary: "hello", title: "t", model: "free" },
    };
    const { header, rawBody } = signWebhook(payload, SECRET);
    const result = await processSummaryWebhook({
      signatureHeader: header,
      rawBody: Buffer.from(rawBody, "utf8"),
    });
    expect(result.status).toBe(204);
    const doc = await admin.firestore().doc(`summaries/${VIDEO_ID}`).get();
    expect(doc.data()?.status).toBe("completed");
    expect(doc.data()?.content).toBe("hello");
  });

  it("AC-8: unknown client_job_id → 401, no write", async () => {
    // Per-job secrets live in `webhook_secrets/{jobId}`. An unknown job has no
    // secret, so signature verification — which runs before the existence
    // check — can never succeed: the unknown-job case is indistinguishable
    // from a bad signature and returns 401 (non-enumerable, harder to probe).
    // This is the intended contract; an unknown job is unreachable with a
    // valid signature, so a dedicated 404 path would be dead code.
    const { processSummaryWebhook } =
      await import("../src/summarizer/webhook.js");
    const payload = { client_job_id: "missing", status: "completed" };
    const { header, rawBody } = signWebhook(payload, SECRET);
    const result = await processSummaryWebhook({
      signatureHeader: header,
      rawBody: Buffer.from(rawBody, "utf8"),
    });
    expect(result.status).toBe(401);
    const doc = await admin.firestore().doc("summaries/missing").get();
    expect(doc.exists).toBe(false);
  });

  it("AC-9: stale timestamp (>300s) → 401, doc unchanged", async () => {
    const { processSummaryWebhook } =
      await import("../src/summarizer/webhook.js");
    await seedRunningSummary();
    const stale = Math.floor(Date.now() / 1000) - 301;
    const payload = { client_job_id: VIDEO_ID, status: "completed" };
    const { header, rawBody } = signWebhook(payload, SECRET, stale);
    const result = await processSummaryWebhook({
      signatureHeader: header,
      rawBody: Buffer.from(rawBody, "utf8"),
    });
    expect(result.status).toBe(401);
    const doc = await admin.firestore().doc(`summaries/${VIDEO_ID}`).get();
    expect(doc.data()?.status).toBe("running");
  });

  it("bad signature → 401", async () => {
    const { processSummaryWebhook } =
      await import("../src/summarizer/webhook.js");
    await seedRunningSummary();
    const payload = { client_job_id: VIDEO_ID, status: "completed" };
    const { rawBody, timestamp } = signWebhook(payload, "wrong-secret");
    // Recompute using the wrong secret — gives a sig that doesn't match.
    const result = await processSummaryWebhook({
      signatureHeader: `t=${timestamp},v1=${"00".repeat(32)}`,
      rawBody: Buffer.from(rawBody, "utf8"),
    });
    expect(result.status).toBe(401);
  });

  it("length-mismatch signature → 401, no throw", async () => {
    const { processSummaryWebhook } =
      await import("../src/summarizer/webhook.js");
    await seedRunningSummary();
    const payload = { client_job_id: VIDEO_ID, status: "completed" };
    const { rawBody, timestamp } = signWebhook(payload, SECRET);
    const result = await processSummaryWebhook({
      signatureHeader: `t=${timestamp},v1=deadbeef`,
      rawBody: Buffer.from(rawBody, "utf8"),
    });
    expect(result.status).toBe(401);
  });

  it("failed-permanent: error.code=transcript_impossible → failed-permanent", async () => {
    const { processSummaryWebhook } =
      await import("../src/summarizer/webhook.js");
    await seedRunningSummary();
    const payload = {
      client_job_id: VIDEO_ID,
      status: "failed",
      error: { code: "transcript_impossible", message: "no caption" },
    };
    const { header, rawBody } = signWebhook(payload, SECRET);
    const result = await processSummaryWebhook({
      signatureHeader: header,
      rawBody: Buffer.from(rawBody, "utf8"),
    });
    expect(result.status).toBe(204);
    const doc = await admin.firestore().doc(`summaries/${VIDEO_ID}`).get();
    expect(doc.data()?.status).toBe("failed-permanent");
    expect(doc.data()?.errorCode).toBe("transcript_impossible");
  });

  it("failed-transient: other error.code → failed-transient", async () => {
    const { processSummaryWebhook } =
      await import("../src/summarizer/webhook.js");
    await seedRunningSummary();
    const payload = {
      client_job_id: VIDEO_ID,
      status: "failed",
      error: { code: "openrouter_timeout", message: "timeout" },
    };
    const { header, rawBody } = signWebhook(payload, SECRET);
    const result = await processSummaryWebhook({
      signatureHeader: header,
      rawBody: Buffer.from(rawBody, "utf8"),
    });
    expect(result.status).toBe(204);
    const doc = await admin.firestore().doc(`summaries/${VIDEO_ID}`).get();
    expect(doc.data()?.status).toBe("failed-transient");
  });

  it("idempotent replay: terminal state + matching status → 204 no-op", async () => {
    const { processSummaryWebhook } =
      await import("../src/summarizer/webhook.js");
    await admin.firestore().doc(`summaries/${VIDEO_ID}`).set({
      videoId: VIDEO_ID,
      status: "completed",
      content: "first",
    });
    await admin.firestore().doc(`webhook_secrets/${VIDEO_ID}`).set({
      secret: SECRET,
      createdAt: admin.firestore.FieldValue.serverTimestamp(),
    });
    const payload = {
      client_job_id: VIDEO_ID,
      status: "completed",
      result: { summary: "second" },
    };
    const { header, rawBody } = signWebhook(payload, SECRET);
    const result = await processSummaryWebhook({
      signatureHeader: header,
      rawBody: Buffer.from(rawBody, "utf8"),
    });
    expect(result.status).toBe(204);
    const doc = await admin.firestore().doc(`summaries/${VIDEO_ID}`).get();
    expect(doc.data()?.content).toBe("first");
  });

  it("missing signature header → 400", async () => {
    const { processSummaryWebhook } =
      await import("../src/summarizer/webhook.js");
    const result = await processSummaryWebhook({
      signatureHeader: undefined,
      rawBody: Buffer.from("{}", "utf8"),
    });
    expect(result.status).toBe(400);
  });

  // M-11: terminal-state mismatch — doc is "completed", webhook delivers
  // "failure". Must return 200 (idempotent short-circuit) and leave the
  // existing "completed" doc untouched.
  it("M-11: completed doc + failure webhook → 200 short-circuit, doc unchanged", async () => {
    const { processSummaryWebhook } =
      await import("../src/summarizer/webhook.js");
    await admin.firestore().doc(`summaries/${VIDEO_ID}`).set({
      videoId: VIDEO_ID,
      status: "completed",
      content: "original-content",
    });
    await admin.firestore().doc(`webhook_secrets/${VIDEO_ID}`).set({
      secret: SECRET,
      createdAt: admin.firestore.FieldValue.serverTimestamp(),
    });

    const payload = {
      client_job_id: VIDEO_ID,
      status: "failed",
      error: { code: "openrouter_timeout", message: "late failure" },
    };
    const { header, rawBody } = signWebhook(payload, SECRET);
    const result = await processSummaryWebhook({
      signatureHeader: header,
      rawBody: Buffer.from(rawBody, "utf8"),
    });

    expect(result.status).toBe(200);
    const doc = await admin.firestore().doc(`summaries/${VIDEO_ID}`).get();
    expect(doc.data()?.status).toBe("completed");
    expect(doc.data()?.content).toBe("original-content");
  });
});

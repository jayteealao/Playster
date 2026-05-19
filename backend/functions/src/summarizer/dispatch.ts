import { randomBytes } from "node:crypto";
import * as admin from "firebase-admin";
import { HttpsError } from "firebase-functions/v2/https";
import * as logger from "firebase-functions/logger";
import { allowlistedCall } from "../auth/verify.js";
import type { SummaryDocument } from "../models/index.js";
import {
  reserveOpenRouterQuotaSlot,
  releaseOpenRouterQuotaSlot,
} from "./quota.js";
import {
  SUMMARIZER_URL,
  SUMMARIZER_API_KEY,
  summarizerSecrets,
} from "./secrets.js";

const FUNCTION_REGION = process.env.FUNCTION_REGION ?? "us-central1";
const TERMINAL_STATUSES: ReadonlyArray<SummaryDocument["status"]> = [
  "completed",
  "failed-transient",
  "failed-permanent",
];
const IN_FLIGHT_STATUSES: ReadonlyArray<SummaryDocument["status"]> = [
  "queued",
  "pending",
  "running",
  "completed",
];

interface RequestVideoSummaryInput {
  videoId: string;
  model?: string;
}

interface RequestVideoSummaryOutput {
  summaryId: string;
}

interface DispatchSummaryOptions {
  fetchImpl?: typeof fetch;
}

function webhookUrl(): string {
  const region = process.env.FUNCTION_REGION ?? FUNCTION_REGION;
  const project =
    process.env.GCLOUD_PROJECT ?? process.env.GCP_PROJECT ?? "demo-playster";
  return `https://${region}-${project}.cloudfunctions.net/summaryWebhook`;
}

async function videoExists(videoId: string): Promise<boolean> {
  const db = admin.firestore();
  // Videos live at `playlists/{playlistId}/videos/{videoId}` — use a
  // collection-group query to locate the doc without needing the parent id.
  const snap = await db
    .collectionGroup("videos")
    .where("videoId", "==", videoId)
    .limit(1)
    .get();
  return !snap.empty;
}

/**
 * Idempotently reserve `summaries/{videoId}` and dispatch a summarizer job.
 * Returns the existing `{summaryId}` if a non-terminal-or-completed doc
 * already exists; otherwise creates one, calls the summarizer, and stores
 * the resulting job id.
 *
 * Quota: pessimistic-pre-increment. Any non-2xx dispatch response releases
 * the reserved slot.
 */
export async function dispatchSummary(
  videoId: string,
  model: string,
  opts: DispatchSummaryOptions = {},
): Promise<RequestVideoSummaryOutput> {
  if (!videoId || typeof videoId !== "string") {
    throw new HttpsError("invalid-argument", "videoId is required.");
  }
  if (!(await videoExists(videoId))) {
    throw new HttpsError("not-found", `Video ${videoId} not found.`);
  }

  const db = admin.firestore();
  const summaryRef = db.doc(`summaries/${videoId}`);

  // Idempotency: if a non-failed doc already exists, return without
  // re-dispatching. Failed docs fall through to a fresh attempt.
  const existing = await summaryRef.get();
  if (existing.exists) {
    const data = existing.data() as Partial<SummaryDocument> | undefined;
    if (data?.status && IN_FLIGHT_STATUSES.includes(data.status)) {
      return { summaryId: videoId };
    }
  }

  // Pre-flight quota reservation. Throws HttpsError("resource-exhausted")
  // on cap breach; we let that bubble up to the callable.
  await reserveOpenRouterQuotaSlot();

  const webhookSecret = randomBytes(32).toString("hex");

  // Reserve doc: transactional read-then-set keeps auto-enqueue,
  // dispatcher, and the manual callable collision-safe.
  await db.runTransaction(async (tx) => {
    const snap = await tx.get(summaryRef);
    const prior = snap.exists ?
      (snap.data() as Partial<SummaryDocument> | undefined) :
      undefined;
    const next: SummaryDocument = {
      videoId,
      status: "pending",
      model,
      webhookSecret,
      requestedAt:
        prior?.requestedAt ?? admin.firestore.FieldValue.serverTimestamp(),
    };
    tx.set(summaryRef, next);
  });

  const doFetch = opts.fetchImpl ?? fetch;
  const body = {
    url: `https://www.youtube.com/watch?v=${videoId}`,
    options: { model, format: "markdown" as const },
    webhook_url: webhookUrl(),
    webhook_secret: webhookSecret,
    client_job_id: videoId,
  };

  let response: Response;
  try {
    response = await doFetch(`${SUMMARIZER_URL.value()}/v1/jobs`, {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        "X-API-Key": SUMMARIZER_API_KEY.value(),
      },
      body: JSON.stringify(body),
    });
  } catch (err) {
    const message = err instanceof Error ? err.message : String(err);
    logger.warn("summarizer dispatch network error", { videoId, message });
    await summaryRef.set(
      {
        status: "failed-transient",
        errorCode: "dispatch_network",
        errorMessage: message,
      },
      { merge: true },
    );
    await releaseOpenRouterQuotaSlot();
    return { summaryId: videoId };
  }

  if (!response.ok) {
    const errBody = await response.text().catch(() => "");
    const status =
      response.status >= 500 ?
        ("failed-transient" as const) :
        ("failed-permanent" as const);
    const errorCode =
      status === "failed-permanent" ? "dispatch_4xx" : "dispatch_5xx";
    logger.warn("summarizer dispatch failed", {
      videoId,
      httpStatus: response.status,
      status,
    });
    await summaryRef.set(
      {
        status,
        errorCode,
        errorMessage: `summarizer ${response.status}: ${errBody.slice(0, 256)}`,
      },
      { merge: true },
    );
    await releaseOpenRouterQuotaSlot();
    return { summaryId: videoId };
  }

  let summarizerJobId: string | undefined;
  try {
    const parsed = (await response.json()) as { id?: unknown };
    if (typeof parsed.id === "string") {
      summarizerJobId = parsed.id;
    }
  } catch {
    // Some implementations return 202 + empty body — tolerate.
  }

  await summaryRef.set(
    {
      status: "running",
      summarizerJobId: summarizerJobId ?? null,
      dispatchedAt: admin.firestore.FieldValue.serverTimestamp(),
    },
    { merge: true },
  );

  return { summaryId: videoId };
}

export const requestVideoSummary = allowlistedCall<
  RequestVideoSummaryInput,
  RequestVideoSummaryOutput
>(
  {
    memory: "256MiB",
    timeoutSeconds: 60,
    secrets: summarizerSecrets,
  },
  async (req) => {
    const videoId = req.data?.videoId;
    if (!videoId) {
      throw new HttpsError("invalid-argument", "videoId is required.");
    }
    const model = req.data?.model ?? "free";
    logger.info("requestVideoSummary", { videoId, model });
    return dispatchSummary(videoId, model);
  },
);

export const __dispatchInternals = { videoExists, TERMINAL_STATUSES };

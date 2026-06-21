import { randomBytes } from "node:crypto";
import * as admin from "firebase-admin";
import { HttpsError } from "firebase-functions/v2/https";
import * as logger from "firebase-functions/logger";
import { allowlistedCall } from "../auth/verify.js";
import type {
  SummaryDocument,
  WebhookSecretDocument,
} from "../models/index.js";
import {
  reserveOpenRouterQuotaSlot,
  releaseOpenRouterQuotaSlot,
} from "./quota.js";
import {
  SUMMARIZER_URL,
  SUMMARIZER_API_KEY,
  summarizerSecrets,
} from "./secrets.js";
import { TERMINAL_STATUSES } from "./constants.js";

const FUNCTION_REGION = process.env.FUNCTION_REGION ?? "us-central1";
// Statuses that must NOT be re-dispatched. `queued` is deliberately EXCLUDED:
// the dispatcher cron hands queued docs to dispatchSummary expecting them to
// go out, and the claim-transaction below atomically flips `queued → pending`,
// so a concurrent cron+manual race still dispatches exactly once (the loser
// reads `pending` and skips). `pending`/`running` guard an in-flight dispatch;
// `completed` is terminal.
const NON_REDISPATCHABLE_STATUSES: ReadonlyArray<SummaryDocument["status"]> = [
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
  /** Override the AbortController timeout in ms. Defaults to 15 000. */
  dispatchTimeoutMs?: number;
}

function webhookUrl(): string {
  const region = FUNCTION_REGION;
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
  _model: string,
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
  const secretRef = db.doc(`webhook_secrets/${videoId}`);

  // Always dispatch with model="free" — the app decides everything; client
  // model field is ignored.
  const dispatchModel = "free";

  const webhookSecret = randomBytes(32).toString("hex");

  // Idempotency check + doc reservation in a single transaction.
  // tx.get(summaryRef) is the contention point: two concurrent dispatches
  // will conflict here, so only one proceeds. Quota is reserved after the
  // transaction commits to avoid double-incrementing on Firestore retries
  // (quota.runTransaction is itself a separate Firestore transaction and
  // cannot be safely nested inside this one).
  let shouldSkipDispatch = false;
  await db.runTransaction(async (tx) => {
    const snap = await tx.get(summaryRef);
    if (snap.exists) {
      const data = snap.data() as Partial<SummaryDocument> | undefined;
      if (data?.status && NON_REDISPATCHABLE_STATUSES.includes(data.status)) {
        shouldSkipDispatch = true;
        return;
      }
    }

    const prior = snap.exists
      ? (snap.data() as Partial<SummaryDocument> | undefined)
      : undefined;
    const next: SummaryDocument = {
      videoId,
      status: "pending",
      model: dispatchModel,
      requestedAt:
        prior?.requestedAt ?? admin.firestore.FieldValue.serverTimestamp(),
    };
    // Reserve doc: transactional read-then-set keeps auto-enqueue,
    // dispatcher, and the manual callable collision-safe.
    // The webhook secret is stored in the server-only `webhook_secrets/`
    // collection (denied to Android client in firestore.rules).
    tx.set(summaryRef, next);
    const secretDoc: WebhookSecretDocument = {
      secret: webhookSecret,
      createdAt: admin.firestore.FieldValue.serverTimestamp(),
    };
    tx.set(secretRef, secretDoc);
  });

  if (shouldSkipDispatch) {
    return { summaryId: videoId };
  }

  // Pre-flight quota reservation. Throws HttpsError("resource-exhausted")
  // on cap breach; we let that bubble up to the callable.
  // This runs after the idempotency transaction commits so it is only called
  // once per genuine new dispatch.
  await reserveOpenRouterQuotaSlot();

  const doFetch = opts.fetchImpl ?? fetch;
  const body = {
    url: `https://www.youtube.com/watch?v=${videoId}`,
    options: { model: dispatchModel, format: "markdown" as const },
    webhook_url: webhookUrl(),
    webhook_secret: webhookSecret,
    client_job_id: videoId,
  };

  const dispatchTimeoutMs = opts.dispatchTimeoutMs ?? 15_000;
  const ctrl = new AbortController();
  const abortTimer = setTimeout(() => ctrl.abort(), dispatchTimeoutMs);

  let response: Response;
  try {
    response = await doFetch(`${SUMMARIZER_URL.value()}/v1/jobs`, {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        "X-API-Key": SUMMARIZER_API_KEY.value(),
      },
      body: JSON.stringify(body),
      signal: ctrl.signal,
    });
  } catch (err) {
    clearTimeout(abortTimer);
    const message = err instanceof Error ? err.message : String(err);
    const isAbort = err instanceof Error && err.name === "AbortError";
    logger.warn("summarizer dispatch network error", {
      videoId,
      message,
      aborted: isAbort,
    });
    await summaryRef.set(
      {
        status: "failed-transient",
        errorCode: isAbort ? "dispatch_timeout" : "dispatch_network",
        errorMessage: message,
      },
      { merge: true },
    );
    await releaseOpenRouterQuotaSlot();
    return { summaryId: videoId };
  }
  clearTimeout(abortTimer);

  if (!response.ok) {
    const errBody = await response.text().catch(() => "");
    const status =
      response.status >= 500
        ? ("failed-transient" as const)
        : ("failed-permanent" as const);
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
    // model is always "free"; client-supplied model field is intentionally ignored.
    logger.info("requestVideoSummary", { videoId });
    return dispatchSummary(videoId, "free");
  },
);

export const __dispatchInternals = {
  videoExists,
  TERMINAL_STATUSES,
  NON_REDISPATCHABLE_STATUSES,
};

import * as admin from "firebase-admin";
import { onSchedule } from "firebase-functions/v2/scheduler";
import { HttpsError } from "firebase-functions/v2/https";
import * as logger from "firebase-functions/logger";
import type { SummaryDocument } from "../models/index.js";
import { dispatchSummary } from "./dispatch.js";
import { summarizerSecrets } from "./secrets.js";
import {
  DISPATCHER_BATCH_SIZE,
  DISPATCHER_LOCK_TTL_MS,
  RETRY_LOCK_DOC_PATH,
} from "./constants.js";
import { acquireCronLock, releaseCronLock } from "./lock.js";

export async function acquireRetryLock(): Promise<string | false> {
  return acquireCronLock(RETRY_LOCK_DOC_PATH, DISPATCHER_LOCK_TTL_MS);
}

export async function releaseRetryLock(ownerToken: string): Promise<void> {
  return releaseCronLock(RETRY_LOCK_DOC_PATH, "releaseRetryLock", ownerToken);
}

/**
 * Re-dispatch every status="failed-transient" summary, subject to current
 * OpenRouter quota. Calls into the shared `dispatchSummary` path — which
 * already owns the per-videoId idempotency transaction, the pessimistic
 * quota reservation, and the webhook-secret rotation. Quota exhaustion
 * mid-batch surfaces as an HttpsError("resource-exhausted") on individual
 * dispatch calls; remaining failed-transient docs stay put for the next
 * daily run.
 *
 * Promise.allSettled fan-out mirrors `drainSummaryQueue` — full attempt
 * across the batch rather than a quota pre-check, because the
 * transactional reserve inside dispatchSummary is the single source of
 * truth and a pre-check can be raced.
 */
export async function retryFailedTransient(): Promise<{
  attempted: number;
  dispatched: number;
  quotaExhausted: boolean;
}> {
  const acquired = await acquireRetryLock();
  if (!acquired) {
    logger.info("summaryRetryCron: lock held by another instance");
    return { attempted: 0, dispatched: 0, quotaExhausted: false };
  }
  const ownerToken = acquired;

  let attempted = 0;
  let dispatched = 0;
  let quotaExhausted = false;
  try {
    const db = admin.firestore();
    const failed = await db
      .collection("summaries")
      .where("status", "==", "failed-transient")
      .orderBy("requestedAt", "asc")
      .limit(DISPATCHER_BATCH_SIZE)
      .get();

    attempted = failed.docs.length;
    const results = await Promise.allSettled(
      failed.docs.map((doc) => {
        const data = doc.data() as Partial<SummaryDocument> | undefined;
        const videoId = data?.videoId ?? doc.id;
        const model = data?.model ?? "free";
        return dispatchSummary(videoId, model);
      }),
    );
    for (let i = 0; i < results.length; i += 1) {
      const result = results[i];
      if (result.status === "fulfilled") {
        dispatched += 1;
      } else {
        const err = result.reason;
        const data = failed.docs[i].data() as
          | Partial<SummaryDocument>
          | undefined;
        const videoId = data?.videoId ?? failed.docs[i].id;
        if (err instanceof HttpsError && err.code === "resource-exhausted") {
          quotaExhausted = true;
          // dispatchSummary's idempotency tx flips the doc to "pending"
          // before the quota reserve throws. "pending" is in
          // NON_REDISPATCHABLE_STATUSES, so without this rollback the doc
          // is stranded — the next retry-cron firing's query selector
          // (status == "failed-transient") would skip it. Restore it so
          // the next run picks it up.
          //
          // F-01: wrap rollback in a transaction that re-reads and only
          // writes failed-transient when status is still "pending", to
          // avoid clobbering a concurrent webhook completion (completed →
          // clobbered) or a race with another writer.
          const docRef = failed.docs[i].ref;
          try {
            await db.runTransaction(async (tx) => {
              const snap = await tx.get(docRef);
              if (!snap.exists) return;
              const current = snap.data() as Partial<SummaryDocument> | undefined;
              if (current?.status !== "pending") return;
              tx.set(
                docRef,
                {
                  status: "failed-transient",
                  errorCode: "retry_quota_exhausted",
                },
                { merge: true },
              );
            });
          } catch (rollbackErr) {
            logger.warn("summaryRetryCron: rollback to failed-transient failed", {
              videoId,
              error:
                rollbackErr instanceof Error ?
                  rollbackErr.message :
                  String(rollbackErr),
            });
          }
          logger.info("summaryRetryCron: budget exhausted for item", {
            videoId,
          });
        } else {
          logger.warn("summaryRetryCron: dispatch error", {
            videoId,
            error: err instanceof Error ? err.message : String(err),
          });
        }
      }
    }
  } finally {
    await releaseRetryLock(ownerToken);
  }

  return { attempted, dispatched, quotaExhausted };
}

export const summaryRetryCron = onSchedule(
  {
    schedule: "0 4 * * *",
    timeZone: "UTC",
    memory: "256MiB",
    timeoutSeconds: 540,
    // F-05: belt-and-suspenders against concurrent Cloud Scheduler instances.
    maxInstances: 1,
    secrets: summarizerSecrets,
    retryCount: 3,
    minBackoffSeconds: 60,
  },
  async () => {
    const result = await retryFailedTransient();
    logger.info("summaryRetryCron: complete", result);
  },
);

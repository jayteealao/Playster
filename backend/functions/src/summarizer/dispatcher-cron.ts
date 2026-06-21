import * as admin from "firebase-admin";
import { onSchedule } from "firebase-functions/v2/scheduler";
import { HttpsError } from "firebase-functions/v2/https";
import * as logger from "firebase-functions/logger";
import type { SummaryDocument } from "../models/index.js";
import { dispatchSummary } from "./dispatch.js";
import { getQuotaBudget } from "./quota.js";
import { summarizerSecrets } from "./secrets.js";
import {
  DISPATCHER_LOCK_DOC_PATH,
  DISPATCHER_LOCK_TTL_MS,
} from "./constants.js";
import { acquireCronLock, releaseCronLock } from "./lock.js";

export async function acquireDispatcherLock(): Promise<string | false> {
  return acquireCronLock(DISPATCHER_LOCK_DOC_PATH, DISPATCHER_LOCK_TTL_MS);
}

export async function releaseDispatcherLock(ownerToken: string): Promise<void> {
  return releaseCronLock(
    DISPATCHER_LOCK_DOC_PATH,
    "releaseDispatcherLock",
    ownerToken,
  );
}

export async function drainSummaryQueue(): Promise<{
  attempted: number;
  dispatched: number;
}> {
  const acquired = await acquireDispatcherLock();
  if (!acquired) {
    logger.info("summaryDispatcher: lock held by another instance");
    return { attempted: 0, dispatched: 0 };
  }

  let attempted = 0;
  let dispatched = 0;
  try {
    const budget = await getQuotaBudget();
    // F-09: Cap the batch to the daily budget only. The per-minute budget
    // is NOT used to size the batch because concurrent requestVideoSummary
    // callables (not gated by this lock) can drain it between here and the
    // fan-out, causing spurious failed-transient rollbacks at the per-minute
    // boundary. Instead we rely on the transactional quota reservation inside
    // dispatchSummary (reserveOpenRouterQuotaSlot) as the single source of
    // truth: if the per-minute window is full it throws HttpsError
    // "resource-exhausted" with message "Rate limit; try again in a moment."
    // — we catch that below and leave the doc queued so the next dispatcher
    // tick retries it rather than rolling it to failed-transient.
    const remaining = budget.remainingDaily;
    if (remaining <= 0) {
      logger.info("summaryDispatcher: no daily budget remaining", budget);
      return { attempted: 0, dispatched: 0 };
    }

    const db = admin.firestore();
    const queued = await db
      .collection("summaries")
      .where("status", "==", "queued")
      .orderBy("requestedAt", "asc")
      .limit(remaining)
      .get();

    attempted = queued.docs.length;
    const results = await Promise.allSettled(
      queued.docs.map((doc) => {
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
        const data = queued.docs[i].data() as
          | Partial<SummaryDocument>
          | undefined;
        const videoId = data?.videoId ?? queued.docs[i].id;
        if (err instanceof HttpsError && err.code === "resource-exhausted") {
          // F-09: distinguish per-minute exhaustion from daily exhaustion.
          // The per-minute error message is "Rate limit; try again in a
          // moment." (from quota.ts). For per-minute throttling we leave the
          // doc alone (it stays queued/pending) so the next tick picks it up
          // rather than rolling it to failed-transient and requiring a
          // separate recovery path.
          const isPerMinute = err.message.includes("Rate limit");
          if (isPerMinute) {
            logger.info(
              "summaryDispatcher: per-minute rate limit hit, leaving doc queued",
              { videoId },
            );
          } else {
            logger.info("summaryDispatcher: daily budget exhausted for item", {
              videoId,
            });
          }
        } else {
          logger.warn("summaryDispatcher: dispatch error", {
            videoId,
            error: err instanceof Error ? err.message : String(err),
          });
        }
      }
    }
  } finally {
    await releaseDispatcherLock(acquired);
  }

  return { attempted, dispatched };
}

export const summaryDispatcher = onSchedule(
  {
    schedule: "every 5 minutes",
    memory: "256MiB",
    timeoutSeconds: 540,
    // F-05: belt-and-suspenders against concurrent Cloud Scheduler instances.
    // The distributed lock is the primary guard; maxInstances: 1 is the
    // secondary guard to prevent overlapping cron executions entirely.
    maxInstances: 1,
    secrets: summarizerSecrets,
  },
  async () => {
    const result = await drainSummaryQueue();
    logger.info("summaryDispatcher: complete", result);
  },
);

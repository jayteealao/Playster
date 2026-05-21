import * as admin from "firebase-admin";
import { onSchedule } from "firebase-functions/v2/scheduler";
import { HttpsError } from "firebase-functions/v2/https";
import * as logger from "firebase-functions/logger";
import type { SummaryDocument } from "../models/index.js";
import { dispatchSummary } from "./dispatch.js";
import { getQuotaBudget } from "./quota.js";
import { summarizerSecrets } from "./secrets.js";
import { DISPATCHER_LOCK_TTL_MS } from "./constants.js";

const LOCK_DOC_PATH = "locks/summaryDispatcher";
const LOCK_TTL_MS = DISPATCHER_LOCK_TTL_MS;

export async function acquireDispatcherLock(): Promise<boolean> {
  const db = admin.firestore();
  const ref = db.doc(LOCK_DOC_PATH);
  return db.runTransaction(async (tx) => {
    const snap = await tx.get(ref);
    const now = Date.now();
    if (snap.exists) {
      const data = snap.data() as { acquiredAt?: number } | undefined;
      const acquiredAt = data?.acquiredAt ?? 0;
      if (now - acquiredAt < LOCK_TTL_MS) {
        return false;
      }
    }
    tx.set(ref, { acquiredAt: now, ttlMs: LOCK_TTL_MS });
    return true;
  });
}

export async function releaseDispatcherLock(): Promise<void> {
  const db = admin.firestore();
  try {
    await db.doc(LOCK_DOC_PATH).set({ acquiredAt: 0 }, { merge: true });
  } catch (err) {
    logger.warn("releaseDispatcherLock failed (best-effort)", {
      error: err instanceof Error ? err.message : String(err),
    });
  }
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
    const remaining = Math.min(budget.remainingDaily, budget.remainingPerMinute);
    if (remaining <= 0) {
      logger.info("summaryDispatcher: no budget remaining", budget);
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
        const data = queued.docs[i].data() as Partial<SummaryDocument> | undefined;
        const videoId = data?.videoId ?? queued.docs[i].id;
        if (err instanceof HttpsError && err.code === "resource-exhausted") {
          logger.info("summaryDispatcher: budget exhausted for item", { videoId });
        } else {
          logger.warn("summaryDispatcher: dispatch error", {
            videoId,
            error: err instanceof Error ? err.message : String(err),
          });
        }
      }
    }
  } finally {
    await releaseDispatcherLock();
  }

  return { attempted, dispatched };
}

export const summaryDispatcher = onSchedule(
  {
    schedule: "every 5 minutes",
    memory: "256MiB",
    timeoutSeconds: 540,
    secrets: summarizerSecrets,
  },
  async () => {
    const result = await drainSummaryQueue();
    logger.info("summaryDispatcher: complete", result);
  },
);

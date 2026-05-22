import * as admin from "firebase-admin";
import { onSchedule } from "firebase-functions/v2/scheduler";
import * as logger from "firebase-functions/logger";
import type { SummaryDocument } from "../models/index.js";
import {
  DISPATCHER_LOCK_TTL_MS,
  STUCK_TIMEOUT_MS,
  SWEEPER_LOCK_DOC_PATH,
} from "./constants.js";

const LOCK_DOC_PATH = SWEEPER_LOCK_DOC_PATH;
const LOCK_TTL_MS = DISPATCHER_LOCK_TTL_MS;

export async function acquireSweeperLock(): Promise<boolean> {
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

export async function releaseSweeperLock(): Promise<void> {
  const db = admin.firestore();
  try {
    await db.doc(LOCK_DOC_PATH).set({ acquiredAt: 0 }, { merge: true });
  } catch (err) {
    logger.warn("releaseSweeperLock failed (best-effort)", {
      error: err instanceof Error ? err.message : String(err),
    });
  }
}

/**
 * Scan for summary docs stuck at status="running" beyond STUCK_TIMEOUT_MS and
 * transition them to failed-transient. Per-doc transaction re-checks status
 * before writing, so a concurrent webhook delivery or completion landing
 * between the query and the flip is a no-op for that doc.
 *
 * No outbound HTTP, no quota interaction — purely a Firestore status flip.
 */
export async function sweepStuckRunning(): Promise<{
  scanned: number;
  flipped: number;
}> {
  const acquired = await acquireSweeperLock();
  if (!acquired) {
    logger.info("summarySweeper: lock held by another instance");
    return { scanned: 0, flipped: 0 };
  }

  let flipped = 0;
  let scanned = 0;
  try {
    const db = admin.firestore();
    const cutoffMs = Date.now() - STUCK_TIMEOUT_MS;
    const cutoffTs = admin.firestore.Timestamp.fromMillis(cutoffMs);
    const stuck = await db
      .collection("summaries")
      .where("status", "==", "running")
      .where("requestedAt", "<", cutoffTs)
      .get();

    scanned = stuck.docs.length;
    for (const doc of stuck.docs) {
      try {
        await db.runTransaction(async (tx) => {
          const snap = await tx.get(doc.ref);
          if (!snap.exists) return;
          const data = snap.data() as Partial<SummaryDocument> | undefined;
          if (data?.status !== "running") return;
          tx.set(
            doc.ref,
            {
              status: "failed-transient",
              errorCode: "stuck_running_timeout",
              errorMessage: `No webhook within ${STUCK_TIMEOUT_MS / 1000}s.`,
            },
            { merge: true },
          );
          flipped += 1;
        });
      } catch (err) {
        logger.warn("summarySweeper: per-doc tx error", {
          videoId: doc.id,
          error: err instanceof Error ? err.message : String(err),
        });
      }
    }
  } finally {
    await releaseSweeperLock();
  }

  return { scanned, flipped };
}

export const summarySweeper = onSchedule(
  {
    schedule: "every 1 hours",
    timeZone: "UTC",
    memory: "256MiB",
    timeoutSeconds: 540,
    retryCount: 1,
    minBackoffSeconds: 60,
  },
  async () => {
    const result = await sweepStuckRunning();
    logger.info("summarySweeper: complete", result);
  },
);

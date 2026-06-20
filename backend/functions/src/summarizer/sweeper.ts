import * as admin from "firebase-admin";
import { onSchedule } from "firebase-functions/v2/scheduler";
import * as logger from "firebase-functions/logger";
import type { SummaryDocument } from "../models/index.js";
import {
  DISPATCHER_LOCK_TTL_MS,
  PENDING_STUCK_TIMEOUT_MS,
  STUCK_TIMEOUT_MS,
  SWEEPER_LOCK_DOC_PATH,
} from "./constants.js";
import { acquireCronLock, releaseCronLock } from "./lock.js";

export async function acquireSweeperLock(): Promise<string | false> {
  return acquireCronLock(SWEEPER_LOCK_DOC_PATH, DISPATCHER_LOCK_TTL_MS);
}

export async function releaseSweeperLock(ownerToken: string): Promise<void> {
  return releaseCronLock(SWEEPER_LOCK_DOC_PATH, "releaseSweeperLock", ownerToken);
}

/**
 * Run a single parallel pass: for each doc in `docs`, run a transaction that
 * re-reads the doc and only writes `fields` (merged) when `expectedStatus`
 * still matches. Returns the number of docs actually flipped.
 *
 * Uses Promise.allSettled so every doc is attempted regardless of individual
 * failures (mirrors the dispatcher and retry cron pattern — F-07).
 */
async function sweepPass(
  db: admin.firestore.Firestore,
  docs: admin.firestore.QueryDocumentSnapshot[],
  expectedStatus: string,
  fields: Record<string, unknown>,
  logLabel: string,
): Promise<number> {
  // Each element resolves to true if the doc was flipped, false if no-op, or
  // rejects on transaction error.
  const results = await Promise.allSettled(
    docs.map(async (doc): Promise<boolean> => {
      let didFlip = false;
      await db.runTransaction(async (tx) => {
        const snap = await tx.get(doc.ref);
        if (!snap.exists) return;
        const data = snap.data() as Partial<SummaryDocument> | undefined;
        if (data?.status !== expectedStatus) return;
        tx.set(doc.ref, fields, { merge: true });
        didFlip = true;
      });
      return didFlip;
    }),
  );

  let flipped = 0;
  for (let i = 0; i < results.length; i += 1) {
    const r = results[i];
    if (r.status === "fulfilled") {
      if (r.value) flipped += 1;
    } else {
      logger.warn(`summarySweeper: per-doc tx error (${logLabel})`, {
        videoId: docs[i].id,
        error: r.reason instanceof Error ? r.reason.message : String(r.reason),
      });
    }
  }
  return flipped;
}

/**
 * Scan for summary docs stuck at status="running" beyond STUCK_TIMEOUT_MS and
 * transition them to failed-transient. Per-doc transaction re-checks status
 * before writing, so a concurrent webhook delivery or completion landing
 * between the query and the flip is a no-op for that doc.
 *
 * Also scans for docs stuck at status="pending" beyond PENDING_STUCK_TIMEOUT_MS
 * and transitions them to failed-transient (F-06). This recovers docs where
 * dispatchSummary committed the idempotency tx (queued → pending) but crashed
 * before the outbound HTTP call, leaving the doc permanently pending.
 *
 * Both passes use Promise.allSettled fan-out — each doc's transaction is
 * independent (F-07). No outbound HTTP, no quota interaction.
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
  const ownerToken = acquired;

  let flipped = 0;
  let scanned = 0;
  try {
    const db = admin.firestore();
    const now = Date.now();

    // --- Pass 1: stuck-running docs ---
    const runningCutoffTs = admin.firestore.Timestamp.fromMillis(
      now - STUCK_TIMEOUT_MS,
    );
    const stuckRunning = await db
      .collection("summaries")
      .where("status", "==", "running")
      .where("requestedAt", "<", runningCutoffTs)
      .get();

    scanned += stuckRunning.docs.length;
    flipped += await sweepPass(
      db,
      stuckRunning.docs,
      "running",
      {
        status: "failed-transient",
        errorCode: "stuck_running_timeout",
        errorMessage: `No webhook within ${STUCK_TIMEOUT_MS / 1000}s.`,
      },
      "running pass",
    );

    // --- Pass 2: stuck-pending docs (F-06) ---
    // Recovers docs where dispatchSummary committed the idempotency tx
    // (queued → pending) but then crashed before the outbound HTTP call,
    // leaving the doc permanently at status="pending".
    const pendingCutoffTs = admin.firestore.Timestamp.fromMillis(
      now - PENDING_STUCK_TIMEOUT_MS,
    );
    const stuckPending = await db
      .collection("summaries")
      .where("status", "==", "pending")
      .where("requestedAt", "<", pendingCutoffTs)
      .get();

    scanned += stuckPending.docs.length;
    flipped += await sweepPass(
      db,
      stuckPending.docs,
      "pending",
      {
        status: "failed-transient",
        errorCode: "stuck_pending_timeout",
        errorMessage: `Still pending after ${PENDING_STUCK_TIMEOUT_MS / 1000}s.`,
      },
      "pending pass",
    );
  } finally {
    await releaseSweeperLock(ownerToken);
  }

  return { scanned, flipped };
}

export const summarySweeper = onSchedule(
  {
    schedule: "every 1 hours",
    timeZone: "UTC",
    memory: "256MiB",
    timeoutSeconds: 540,
    // F-05: belt-and-suspenders against concurrent Cloud Scheduler instances.
    maxInstances: 1,
    retryCount: 1,
    minBackoffSeconds: 60,
  },
  async () => {
    const result = await sweepStuckRunning();
    logger.info("summarySweeper: complete", result);
  },
);

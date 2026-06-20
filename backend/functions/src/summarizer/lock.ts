import * as admin from "firebase-admin";
import * as logger from "firebase-functions/logger";
import { DISPATCHER_LOCK_TTL_MS } from "./constants.js";

/**
 * Shared distributed-lock helpers used by all three summarizer crons
 * (dispatcher, sweeper, retry). The lock is a Firestore document whose
 * `acquiredAt` field (epoch ms) acts as the sentinel.
 *
 * Acquire: transactional check-and-set — returns false if the lock is
 * currently held and within TTL; sets acquiredAt=now and returns true
 * otherwise (including stale-lock reclaim).
 *
 * Release: best-effort set acquiredAt=0; logs a warning on failure but
 * never throws so the caller's finally-block always completes.
 */
export async function acquireCronLock(
  docPath: string,
  ttlMs: number = DISPATCHER_LOCK_TTL_MS,
): Promise<boolean> {
  const db = admin.firestore();
  const ref = db.doc(docPath);
  return db.runTransaction(async (tx) => {
    const snap = await tx.get(ref);
    const now = Date.now();
    if (snap.exists) {
      const data = snap.data() as { acquiredAt?: number } | undefined;
      const acquiredAt = data?.acquiredAt ?? 0;
      if (now - acquiredAt < ttlMs) {
        return false;
      }
    }
    tx.set(ref, { acquiredAt: now, ttlMs });
    return true;
  });
}

export async function releaseCronLock(
  docPath: string,
  label: string,
): Promise<void> {
  const db = admin.firestore();
  try {
    await db.doc(docPath).set({ acquiredAt: 0 }, { merge: true });
  } catch (err) {
    logger.warn(`${label} failed (best-effort)`, {
      error: err instanceof Error ? err.message : String(err),
    });
  }
}

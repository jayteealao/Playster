import { randomUUID } from "node:crypto";
import * as admin from "firebase-admin";
import * as logger from "firebase-functions/logger";
import { DISPATCHER_LOCK_TTL_MS } from "./constants.js";

/**
 * Shared distributed-lock helpers used by all three summarizer crons
 * (dispatcher, sweeper, retry). The lock is a Firestore document whose
 * `acquiredAt` field (epoch ms) acts as the sentinel.
 *
 * Acquire: transactional check-and-set — returns false if the lock is
 * currently held and within TTL; generates a unique owner token, writes it
 * alongside acquiredAt, and returns the token string otherwise (including
 * stale-lock reclaim).
 *
 * Release: ownership-checked transactional clear — reads the current owner
 * field and only clears the lock when it still matches the caller's token.
 * If the owner has changed (e.g. a slow instance whose TTL expired and a new
 * instance reclaimed the lock) the release is a safe no-op. Logs a warning
 * on any Firestore error but never throws so the caller's finally-block
 * always completes.
 */
export async function acquireCronLock(
  docPath: string,
  ttlMs: number = DISPATCHER_LOCK_TTL_MS,
): Promise<string | false> {
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
    const owner = randomUUID();
    tx.set(ref, { acquiredAt: now, ttlMs, owner });
    return owner;
  });
}

export async function releaseCronLock(
  docPath: string,
  label: string,
  ownerToken: string,
): Promise<void> {
  const db = admin.firestore();
  const ref = db.doc(docPath);
  try {
    await db.runTransaction(async (tx) => {
      const snap = await tx.get(ref);
      if (!snap.exists) return;
      const data = snap.data() as { owner?: string } | undefined;
      if (data?.owner !== ownerToken) {
        // Another instance has reclaimed the lock; do not clobber it.
        logger.warn(`${label}: owner mismatch — skipping release`, {
          expected: ownerToken,
          found: data?.owner ?? "(none)",
        });
        return;
      }
      tx.set(ref, { acquiredAt: 0, owner: "" }, { merge: true });
    });
  } catch (err) {
    logger.warn(`${label} failed (best-effort)`, {
      error: err instanceof Error ? err.message : String(err),
    });
  }
}

import * as admin from "firebase-admin";
import { HttpsError } from "firebase-functions/v2/https";
import * as logger from "firebase-functions/logger";
import type { QuotaDocument } from "../models/index.js";

const QUOTA_DOC_PATH = "quota/openrouter";
const DEFAULT_DAILY_LIMIT = 1000;
const DEFAULT_PER_MINUTE_LIMIT = 20;
const WINDOW_MS = 60_000;

export function todayUTC(now: number = Date.now()): string {
  return new Date(now).toISOString().slice(0, 10);
}

interface QuotaSnapshot {
  date: string;
  requestCount: number;
  dailyLimit: number;
  perMinuteLimit: number;
  recentTimestamps: number[];
}

function trimWindow(timestamps: number[], now: number): number[] {
  const cutoff = now - WINDOW_MS;
  return timestamps.filter((t) => t > cutoff);
}

function readQuota(
  data: Partial<QuotaDocument> | undefined,
  now: number,
): QuotaSnapshot {
  const today = todayUTC(now);
  const date = data?.date ?? today;
  const rolledOver = date !== today;
  const dailyLimit = data?.dailyLimit ?? DEFAULT_DAILY_LIMIT;
  const perMinuteLimit = data?.perMinuteLimit ?? DEFAULT_PER_MINUTE_LIMIT;
  const requestCount = rolledOver ? 0 : data?.requestCount ?? 0;
  const recentTimestamps = rolledOver ?
    [] :
    trimWindow(data?.recentTimestamps ?? [], now);
  return {
    date: today,
    requestCount,
    dailyLimit,
    perMinuteLimit,
    recentTimestamps,
  };
}

/**
 * Atomically check + reserve a quota slot. Performs the day-rollover reset,
 * the sliding-window trim, and the increment in a single transaction so
 * concurrent dispatches cannot collectively exceed the daily cap.
 *
 * Pessimistic-pre-increment: increment happens before the outbound HTTP
 * call. If the dispatch fails, the caller fires a best-effort decrement.
 * Over-counting by 1-2 is acceptable; under-counting (burning past the
 * hard cap) is not.
 */
export async function reserveOpenRouterQuotaSlot(): Promise<void> {
  const db = admin.firestore();
  const ref = db.doc(QUOTA_DOC_PATH);
  await db.runTransaction(async (tx) => {
    const now = Date.now();
    const snap = await tx.get(ref);
    const current = readQuota(snap.data() as Partial<QuotaDocument> | undefined, now);
    if (current.requestCount >= current.dailyLimit) {
      throw new HttpsError(
        "resource-exhausted",
        "Daily summary limit reached. Resets at midnight UTC.",
      );
    }
    if (current.recentTimestamps.length >= current.perMinuteLimit) {
      throw new HttpsError(
        "resource-exhausted",
        "Rate limit; try again in a moment.",
      );
    }
    const next: QuotaDocument = {
      date: current.date,
      requestCount: current.requestCount + 1,
      dailyLimit: current.dailyLimit,
      perMinuteLimit: current.perMinuteLimit,
      recentTimestamps: [...current.recentTimestamps, now],
      updatedAt: admin.firestore.FieldValue.serverTimestamp(),
    };
    tx.set(ref, next);
  });
}

/**
 * Read-only quota snapshot for the scheduled dispatcher's budget computation.
 * Trims the sliding window in memory but does not write.
 */
export async function getQuotaBudget(): Promise<{
  remainingDaily: number;
  remainingPerMinute: number;
}> {
  const db = admin.firestore();
  const ref = db.doc(QUOTA_DOC_PATH);
  const snap = await ref.get();
  const now = Date.now();
  const current = readQuota(
    snap.data() as Partial<QuotaDocument> | undefined,
    now,
  );
  return {
    remainingDaily: Math.max(0, current.dailyLimit - current.requestCount),
    remainingPerMinute: Math.max(
      0,
      current.perMinuteLimit - current.recentTimestamps.length,
    ),
  };
}

/**
 * Best-effort rollback when the outbound dispatch HTTP call fails after the
 * slot was reserved. Never throws — over-counting on a failed decrement is
 * preferable to letting the caller's error path explode.
 */
export async function releaseOpenRouterQuotaSlot(): Promise<void> {
  const db = admin.firestore();
  const ref = db.doc(QUOTA_DOC_PATH);
  try {
    await db.runTransaction(async (tx) => {
      const snap = await tx.get(ref);
      if (!snap.exists) return;
      const now = Date.now();
      const data = snap.data() as Partial<QuotaDocument> | undefined;
      const today = todayUTC(now);
      // If a day rollover happened between reserve and release, there is
      // nothing to refund — the new day starts at zero.
      if (data?.date !== today) return;
      const requestCount = Math.max(0, (data.requestCount ?? 0) - 1);
      const recentTimestamps = trimWindow(data.recentTimestamps ?? [], now);
      // Drop the newest entry (we appended last). If multiple racers
      // dropped at once it still trends correct over time.
      if (recentTimestamps.length > 0) recentTimestamps.pop();
      tx.set(
        ref,
        {
          date: data.date,
          requestCount,
          dailyLimit: data.dailyLimit ?? DEFAULT_DAILY_LIMIT,
          perMinuteLimit: data.perMinuteLimit ?? DEFAULT_PER_MINUTE_LIMIT,
          recentTimestamps,
          updatedAt: admin.firestore.FieldValue.serverTimestamp(),
        },
        { merge: false },
      );
    });
  } catch (err) {
    logger.warn("releaseOpenRouterQuotaSlot failed (best-effort)", {
      error: err instanceof Error ? err.message : String(err),
    });
  }
}

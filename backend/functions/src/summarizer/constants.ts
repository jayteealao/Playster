/**
 * Summarizer quota and dispatcher constants.
 *
 * These values are server-owned. The authoritative copy lives here.
 * The Android mirror is at:
 *   android/app/src/main/java/com/github/jayteealao/playster/screens/common/state/QuotaPolicy.kt
 * Keep both files in sync when any value changes.
 *
 * Sweeper/retry constants (STUCK_TIMEOUT_MS, SWEEPER_LOCK_DOC_PATH,
 * RETRY_LOCK_DOC_PATH) are server-only — Android does not consume them.
 */

/** Maximum summary requests per calendar day (UTC). */
export const OPENROUTER_DAILY_LIMIT = 1_000;

/** Maximum summary requests per rolling 60-second window. */
export const OPENROUTER_PER_MINUTE_LIMIT = 20;

/** Rolling window duration in milliseconds (matches per-minute limit). */
export const OPENROUTER_WINDOW_MS = 60_000;

/** Maximum age of an inbound webhook signature timestamp before it is rejected (seconds). */
export const WEBHOOK_REPLAY_WINDOW_SECONDS = 300;

/** Dispatcher distributed-lock TTL in milliseconds. */
export const DISPATCHER_LOCK_TTL_MS = 240_000;

/** Maximum number of queued summaries processed per dispatcher invocation. */
export const DISPATCHER_BATCH_SIZE = 200;

/** Maximum age of a status="running" summary doc before the sweeper flips it
 *  to failed-transient. One hour in milliseconds. */
export const STUCK_TIMEOUT_MS = 60 * 60 * 1000;

/** Firestore doc path for the sweeper cron's distributed lock. */
export const SWEEPER_LOCK_DOC_PATH = "locks/summarySweeper";

/** Firestore doc path for the retry cron's distributed lock. */
export const RETRY_LOCK_DOC_PATH = "locks/summaryRetry";

/**
 * Terminal summary statuses — docs in these states have reached a final
 * outcome and will not transition further without an explicit retry action.
 * Used as the idempotency guard in the webhook and as the sweeper filter.
 *
 * Note: this set differs from NON_REDISPATCHABLE_STATUSES in dispatch.ts,
 * which guards the in-flight states (pending/running) plus completed to
 * prevent duplicate dispatches. `queued` is intentionally NOT in that guard
 * set, so the dispatcher cron can claim queued docs (its claim-transaction
 * flips queued → pending atomically). Keep both; they serve different purposes.
 */
export const TERMINAL_STATUSES = [
  "completed",
  "failed-transient",
  "failed-permanent",
] as const;
export type TerminalStatus = (typeof TERMINAL_STATUSES)[number];

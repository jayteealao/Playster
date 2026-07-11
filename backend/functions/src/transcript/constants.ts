/**
 * Transcript backfill constants.
 *
 * Kept separate from the summarizer's constants.ts so the transcript
 * module is self-contained and changes here do not touch summarizer code.
 */

/**
 * Maximum number of videos fetched per backfill cron tick.
 * Conservative cap to avoid triggering YouTube egress detection.
 * At hourly ticks: 50/tick × 24 ticks/day = 1,200 videos/day.
 * At ~8k total videos: ~6.7 days to drain on first pass.
 * Raise to 100 if zero YouTube blocks are observed in practice — one constant change.
 */
export const TRANSCRIPT_BACKFILL_BATCH_SIZE = 50;

/**
 * Firestore doc path for the transcript backfill cron's distributed lock.
 * Follows the "locks/<name>" convention used by all summarizer crons.
 */
export const TRANSCRIPT_BACKFILL_LOCK_DOC_PATH = "locks/transcriptBackfill";

/**
 * Terminal transcript statuses — docs in these states have reached a final
 * outcome for transcript capture and will not be re-fetched by the backfill cron.
 *   - "available": transcript was fetched and stored successfully.
 *   - "unavailable": video has no caption track (permanent; re-fetching changes nothing).
 */
export const TRANSCRIPT_TERMINAL_STATUSES = [
  "available",
  "unavailable",
] as const;
export type TranscriptTerminalStatus =
  (typeof TRANSCRIPT_TERMINAL_STATUSES)[number];

/**
 * After this many consecutive PANEL_NOT_FOUND outcomes for the same video,
 * the pointer doc flips from `transient` to terminal `unavailable`.
 * Three hits defend against YouTube transiently omitting the panel on videos
 * that genuinely have captions, while preventing infinite retry on videos that
 * never will.
 */
export const PANEL_NOT_FOUND_TERMINAL_COUNT = 3;

/**
 * Maximum time in milliseconds to wait for the ANDROID-client timedtext HTTP
 * fetch to complete. The fetch() in fetchViaAndroidTimedtext calls an external
 * caption-track URL; without a deadline the function could stall until the
 * Cloud Functions request timeout is hit (up to 9 minutes for gen-2).
 * 15 s is generous for a JSON caption file while still bounding total latency.
 */
export const TIMEDTEXT_FETCH_TIMEOUT_MS = 15_000;

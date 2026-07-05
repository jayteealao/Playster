import { onSchedule } from "firebase-functions/v2/scheduler";
import { HttpsError } from "firebase-functions/v2/https";
import * as logger from "firebase-functions/logger";
import { oauthSecrets } from "./auth/oauth";
import { allowlistedCall } from "./auth/verify";
import { enqueueAutoSummary } from "./summarizer/autoEnqueue";
import { reconcileAll } from "./summarizer/reconcile";
import { fetchTranscript } from "./transcript/index";

// --- Auth functions ---
export {
  authRedirect,
  authCallback,
  setCookies,
  setTvOauthCredentials,
} from "./auth/handlers";

// --- Sync engine ---
import {
  syncAll,
  syncPlaylistById,
  syncWatchLater as runSyncWatchLater,
  type SyncAllResult,
  type WatchLaterSyncResult,
} from "./youtube";

// --- Summarizer (slice 3) ---
export { requestVideoSummary } from "./summarizer/dispatch";
export { summaryWebhook } from "./summarizer/webhook";
export { summaryDispatcher } from "./summarizer/dispatcher-cron";
export { summarySweeper } from "./summarizer/sweeper";
export { summaryRetryCron } from "./summarizer/retry";

// --- Transcript fetch + backfill cron ---
export { invokeTranscriptFetch, transcriptBackfillCron } from "./transcript/index";

async function autoEnqueueSafe(videoIds: string[] | undefined): Promise<void> {
  if (!videoIds || !videoIds.length) return;
  try {
    await enqueueAutoSummary(videoIds);
  } catch (err) {
    // Never fail the sync because of auto-enqueue.
    logger.warn("autoEnqueueSafe failed (non-fatal)", {
      count: videoIds.length,
      error: err instanceof Error ? err.message : String(err),
    });
  }
}

/**
 * Fetch transcripts for newly synced videos after a sync completes.
 * Non-fatal — a transcript fetch failure must never fail the sync.
 * Uses Promise.allSettled (parallel) because new-video counts per sync
 * are small (1–10) and latency within a sync is user-visible.
 *
 * Exported so the AC1 fetch-after-sync test can drive it directly without
 * going through a full sync callable invocation.
 */
export async function fetchTranscriptsSafe(
  videoIds: string[] | undefined,
): Promise<void> {
  if (!videoIds || !videoIds.length) return;
  try {
    await Promise.allSettled(videoIds.map((id) => fetchTranscript(id)));
  } catch (err) {
    // Promise.allSettled itself should never throw, but guard anyway.
    logger.warn("fetchTranscriptsSafe failed (non-fatal)", {
      count: videoIds.length,
      error: err instanceof Error ? err.message : String(err),
    });
  }
}

// External-facing return shape for syncAllPlaylists / scheduledSync. The
// internal SyncAllResult also carries `videoIds`, but the wire response
// stays compact.
type SyncAllResponse = Omit<SyncAllResult, "videoIds">;

/**
 * Syncs all playlists and Watch Later on demand. Allowlisted operator only.
 */
export const syncAllPlaylists = allowlistedCall<
  Record<string, never>,
  SyncAllResponse
>(
  { memory: "512MiB", timeoutSeconds: 540, secrets: oauthSecrets },
  async () => {
    logger.info("syncAllPlaylists: starting full sync");
    const { videoIds, ...result } = await syncAll();
    logger.info("syncAllPlaylists: completed", result);
    await autoEnqueueSafe(videoIds);
    await fetchTranscriptsSafe(videoIds);
    return result;
  },
);

/**
 * Syncs a single playlist by ID. Allowlisted operator only.
 */
export const syncPlaylist = allowlistedCall<
  { playlistId: string },
  { videoCount: number }
>(
  { memory: "512MiB", timeoutSeconds: 300, secrets: oauthSecrets },
  async (req) => {
    const { playlistId } = req.data;
    if (!playlistId) {
      throw new HttpsError("invalid-argument", "Missing playlistId");
    }
    logger.info("syncPlaylist: syncing playlist", { playlistId });
    const { videoIds, ...result } = await syncPlaylistById(playlistId);
    logger.info("syncPlaylist: completed", result);
    await autoEnqueueSafe(videoIds);
    await fetchTranscriptsSafe(videoIds);
    return result;
  },
);

/**
 * Syncs only the Watch Later playlist. Allowlisted operator only.
 */
export const syncWatchLater = allowlistedCall<
  { reset?: boolean },
  Omit<WatchLaterSyncResult, "videoIds">
>(
  { memory: "512MiB", timeoutSeconds: 540, secrets: oauthSecrets },
  async (req) => {
    const reset = req.data.reset === true;
    logger.info("syncWatchLater: starting", { reset });
    const { videoIds, ...result } = await runSyncWatchLater({ reset });
    logger.info("syncWatchLater: completed", result);
    await autoEnqueueSafe(videoIds);
    await fetchTranscriptsSafe(videoIds);
    return result;
  },
);

/**
 * One-time idempotent backfill: enqueues every video that has no summary doc.
 * Safe to run multiple times — videos with an existing summary doc (any status)
 * are skipped. Allowlisted operator only.
 */
export const reconcileVideoSummaries = allowlistedCall<
  Record<string, never>,
  { total: number; enqueued: number; skipped: number }
>(
  { memory: "512MiB", timeoutSeconds: 540 },
  async () => {
    logger.info("reconcileVideoSummaries: starting");
    const result = await reconcileAll();
    logger.info("reconcileVideoSummaries: complete", result);
    return result;
  },
);

/**
 * Scheduled sync — runs every 6 hours via Cloud Scheduler. No auth context.
 */
export const scheduledSync = onSchedule(
  {
    schedule: "every 6 hours",
    memory: "512MiB",
    timeoutSeconds: 540,
    secrets: oauthSecrets,
  },
  async (_event) => {
    try {
      logger.info("scheduledSync: starting full sync");
      const { videoIds, ...result } = await syncAll();
      logger.info("scheduledSync: completed", result);
      await autoEnqueueSafe(videoIds);
      await fetchTranscriptsSafe(videoIds);
    } catch (error) {
      logger.error("scheduledSync failed", error);
    }
  },
);

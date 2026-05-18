import { onSchedule } from "firebase-functions/v2/scheduler";
import { HttpsError } from "firebase-functions/v2/https";
import * as logger from "firebase-functions/logger";
import { oauthSecrets } from "./auth/oauth";
import { allowlistedCall } from "./auth/verify";

// --- Auth functions ---
export { authRedirect, authCallback, setCookies, setTvOauthCredentials } from "./auth/handlers";

// --- Sync engine ---
import {
  syncAll,
  syncPlaylistById,
  syncWatchLater as runSyncWatchLater,
  type WatchLaterSyncResult,
} from "./youtube";

interface SyncAllResult {
  regular: { playlistCount: number; videoCount: number };
  watchLater: WatchLaterSyncResult | { error: string };
}

/**
 * Syncs all playlists and Watch Later on demand. Allowlisted operator only.
 */
export const syncAllPlaylists = allowlistedCall<Record<string, never>, SyncAllResult>(
  { memory: "512MiB", timeoutSeconds: 540, secrets: oauthSecrets },
  async () => {
    logger.info("syncAllPlaylists: starting full sync");
    const result = await syncAll();
    logger.info("syncAllPlaylists: completed", result);
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
    const result = await syncPlaylistById(playlistId);
    logger.info("syncPlaylist: completed", result);
    return result;
  },
);

/**
 * Syncs only the Watch Later playlist. Allowlisted operator only.
 */
export const syncWatchLater = allowlistedCall<
  { reset?: boolean },
  WatchLaterSyncResult
>(
  { memory: "512MiB", timeoutSeconds: 540, secrets: oauthSecrets },
  async (req) => {
    const reset = req.data.reset === true;
    logger.info("syncWatchLater: starting", { reset });
    const result = await runSyncWatchLater({ reset });
    logger.info("syncWatchLater: completed", result);
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
      const result = await syncAll();
      logger.info("scheduledSync: completed", result);
    } catch (error) {
      logger.error("scheduledSync failed", error);
    }
  },
);

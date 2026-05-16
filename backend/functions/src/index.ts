import { onRequest } from "firebase-functions/v2/https";
import { onSchedule } from "firebase-functions/v2/scheduler";
import * as logger from "firebase-functions/logger";
import { oauthSecrets } from "./auth/oauth";

// --- Auth functions ---
export { authRedirect, authCallback, setCookies } from "./auth/handlers";

// --- Sync engine ---
import {
  syncAll,
  syncPlaylistById,
  syncWatchLater as runSyncWatchLater,
} from "./youtube";

/**
 * Syncs all playlists and Watch Later on demand.
 */
export const syncAllPlaylists = onRequest(
  { memory: "512MiB", timeoutSeconds: 540, secrets: oauthSecrets },
  async (_req, res) => {
    try {
      logger.info("syncAllPlaylists: starting full sync");
      const result = await syncAll();
      logger.info("syncAllPlaylists: completed", result);
      res.json(result);
    } catch (error) {
      logger.error("syncAllPlaylists failed", error);
      res.status(500).json({ error: "Sync failed" });
    }
  },
);

/**
 * Syncs a single playlist by ID (passed as ?playlistId=...).
 */
export const syncPlaylist = onRequest(
  { memory: "512MiB", timeoutSeconds: 300, secrets: oauthSecrets },
  async (req, res) => {
    const playlistId = req.query.playlistId as string | undefined;
    if (!playlistId) {
      res.status(400).json({ error: "Missing playlistId query parameter" });
      return;
    }

    try {
      logger.info("syncPlaylist: syncing playlist", { playlistId });
      const result = await syncPlaylistById(playlistId);
      logger.info("syncPlaylist: completed", result);
      res.json(result);
    } catch (error) {
      logger.error("syncPlaylist failed", { playlistId, error });
      res.status(500).json({ error: "Sync failed" });
    }
  },
);

/**
 * Syncs only the Watch Later playlist.
 */
export const syncWatchLater = onRequest(
  { memory: "512MiB", timeoutSeconds: 300, secrets: oauthSecrets },
  async (_req, res) => {
    try {
      logger.info("syncWatchLater: starting");
      const result = await runSyncWatchLater();
      logger.info("syncWatchLater: completed", result);
      res.json(result);
    } catch (error) {
      logger.error("syncWatchLater failed", error);
      res.status(500).json({ error: "Sync failed" });
    }
  },
);

/**
 * Scheduled sync — runs every 6 hours via Cloud Scheduler.
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

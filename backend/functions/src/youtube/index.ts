import { syncRegularPlaylists } from "./api-sync.js";
import { syncWatchLater, type WatchLaterSyncResult } from "./innertube-sync.js";

export { syncRegularPlaylists, fetchAllPlaylists, fetchPlaylistVideos, syncPlaylistById } from "./api-sync.js";
export { syncWatchLater, type WatchLaterSyncResult } from "./innertube-sync.js";

/**
 * Run both regular playlist sync and Watch Later sync.
 * Watch Later sync is resumable across calls — a single invocation may only
 * read part of the playlist before rate-limit forces a stop; the cursor is
 * persisted and the next call continues.
 */
export async function syncAll(): Promise<{
  regular: { playlistCount: number; videoCount: number };
  watchLater: WatchLaterSyncResult | { error: string };
}> {
  const regular = await syncRegularPlaylists();

  let watchLater: WatchLaterSyncResult | { error: string };
  try {
    watchLater = await syncWatchLater();
  } catch (err) {
    const message = err instanceof Error ? err.message : String(err);
    console.error("Watch Later sync failed:", message);
    watchLater = { error: message };
  }

  return { regular, watchLater };
}

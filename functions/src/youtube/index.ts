import { syncRegularPlaylists } from "./api-sync.js";
import { syncWatchLater } from "./innertube-sync.js";

export { syncRegularPlaylists, fetchAllPlaylists, fetchPlaylistVideos, syncPlaylistById } from "./api-sync.js";
export { syncWatchLater } from "./innertube-sync.js";

/**
 * Run both regular playlist sync and Watch Later sync.
 * If Watch Later fails (e.g. expired cookies), the error is logged
 * but the regular sync results are still returned.
 */
export async function syncAll(): Promise<{
  regular: { playlistCount: number; videoCount: number };
  watchLater: { videoCount: number } | { error: string };
}> {
  const regular = await syncRegularPlaylists();

  let watchLater: { videoCount: number } | { error: string };
  try {
    watchLater = await syncWatchLater();
  } catch (err) {
    const message = err instanceof Error ? err.message : String(err);
    console.error("Watch Later sync failed:", message);
    watchLater = { error: message };
  }

  return { regular, watchLater };
}

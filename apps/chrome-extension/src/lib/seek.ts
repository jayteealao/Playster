export type SeekResponse = { ok: true } | { ok: false; error: string };

export function seekToSecondsInDocument(doc: Document, seconds: number): SeekResponse {
  if (!Number.isFinite(seconds) || seconds < 0) {
    return { ok: false, error: "Invalid timestamp" };
  }
  const media = doc.querySelector("video, audio") as HTMLMediaElement | null;
  if (media) {
    try {
      media.currentTime = seconds;
      void media.play().catch(() => {});
      return { ok: true };
    } catch (error) {
      return { ok: false, error: error instanceof Error ? error.message : "Seek failed" };
    }
  }

  const player = doc.getElementById("movie_player") as
    | ((HTMLElement & { seekTo?: (time: number, allowSeekAhead?: boolean) => void }) & {
        getPlayerState?: () => number;
        pauseVideo?: () => void;
        playVideo?: () => void;
      })
    | null;
  if (player?.seekTo) {
    try {
      player.seekTo(seconds, true);
      if (typeof player.playVideo === "function") {
        player.playVideo();
      }
      return { ok: true };
    } catch (error) {
      return { ok: false, error: error instanceof Error ? error.message : "Seek failed" };
    }
  }

  return { ok: false, error: "No media element found" };
}

import { shouldPreferUrlMode } from "@steipete/summarize-core/content/url";
import type { UiState } from "./types";

export function shouldSeedPlannedSlidesForRun({
  durationSeconds,
  inputMode,
  media,
  mediaAvailable,
  runUrl,
  slidesEnabled,
}: {
  durationSeconds: number | null | undefined;
  inputMode: "page" | "video";
  media: UiState["media"] | null | undefined;
  mediaAvailable: boolean;
  runUrl: string;
  slidesEnabled: boolean;
}) {
  if (!slidesEnabled) return false;
  if (!durationSeconds || !Number.isFinite(durationSeconds) || durationSeconds <= 0) return false;
  if (inputMode === "video") return true;
  if (mediaAvailable) return true;
  if (media?.hasVideo || media?.hasAudio) return true;
  return shouldPreferUrlMode(runUrl);
}

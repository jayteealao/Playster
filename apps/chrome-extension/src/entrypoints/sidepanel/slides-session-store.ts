import type { SlidesLayout } from "../../lib/settings";
import type { RunStart } from "./types";

type InputMode = "page" | "video";

export type SlidesSessionState = {
  slidesEnabled: boolean;
  slidesParallel: boolean;
  slidesOcrEnabled: boolean;
  inputMode: InputMode;
  inputModeOverride: InputMode | null;
  mediaAvailable: boolean;
  summarizeVideoLabel: string;
  summarizePageWords: number | null;
  summarizeVideoDurationSeconds: number | null;
  slidesBusy: boolean;
  slidesExpanded: boolean;
  slidesLayout: SlidesLayout;
  slidesContextRequestId: number;
  slidesContextPending: boolean;
  slidesContextUrl: string | null;
  slidesSeededSourceId: string | null;
  slidesAppliedRunId: string | null;
  pendingRunForPlannedSlides: RunStart | null;
};

export function createSlidesSessionStore(options: {
  slidesEnabled: boolean;
  slidesParallel: boolean;
  slidesOcrEnabled: boolean;
  slidesLayout: SlidesLayout;
}) {
  const state: SlidesSessionState = {
    slidesEnabled: options.slidesEnabled,
    slidesParallel: options.slidesParallel,
    slidesOcrEnabled: options.slidesOcrEnabled,
    inputMode: "page",
    inputModeOverride: null,
    mediaAvailable: false,
    summarizeVideoLabel: "Video",
    summarizePageWords: null,
    summarizeVideoDurationSeconds: null,
    slidesBusy: false,
    slidesExpanded: true,
    slidesLayout: options.slidesLayout,
    slidesContextRequestId: 0,
    slidesContextPending: false,
    slidesContextUrl: null,
    slidesSeededSourceId: null,
    slidesAppliedRunId: null,
    pendingRunForPlannedSlides: null,
  };

  return {
    state,
    resolveInputMode(): InputMode {
      return state.inputModeOverride ?? state.inputMode;
    },
    nextSlidesContextRequestId(): number {
      state.slidesContextRequestId += 1;
      return state.slidesContextRequestId;
    },
  };
}

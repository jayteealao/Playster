import type { SlidesLayout } from "../../lib/settings";

export function resolveSlidesRenderLayout({
  preferredLayout,
  slidesEnabled,
  inputMode,
}: {
  preferredLayout: SlidesLayout;
  slidesEnabled: boolean;
  inputMode: "page" | "video";
}): SlidesLayout {
  if (slidesEnabled && inputMode === "video") return "gallery";
  return preferredLayout;
}

export function shouldHideSummaryForSlides({
  slidesEnabled,
  inputMode,
  hasSlides,
}: {
  slidesEnabled: boolean;
  inputMode: "page" | "video";
  hasSlides: boolean;
}): boolean {
  return slidesEnabled && inputMode === "video" && hasSlides;
}

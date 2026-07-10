export type UrlProgressStatusState = {
  summaryText: string | null;
  slidesActive: boolean;
  slidesText: string | null;
  lastSlideRenderAt: number;
};

export function createUrlProgressStatusState(): UrlProgressStatusState {
  return {
    summaryText: null,
    slidesActive: false,
    slidesText: null,
    lastSlideRenderAt: 0,
  };
}

export function applySummaryText(
  state: UrlProgressStatusState,
  text: string,
): { renderText: string | null } {
  state.summaryText = text;
  return { renderText: state.slidesActive ? null : text };
}

export function applySlidesText(
  state: UrlProgressStatusState,
  text: string,
  nowMs: number,
): { renderText: string | null } {
  const previousSlidesText = state.slidesText;
  state.slidesActive = true;
  state.slidesText = text;
  if (previousSlidesText == null || nowMs - state.lastSlideRenderAt >= 100) {
    state.lastSlideRenderAt = nowMs;
    return { renderText: text };
  }
  return { renderText: null };
}

export function clearSlidesText(state: UrlProgressStatusState): {
  renderText: string | null;
  summaryText: string | null;
} {
  state.slidesActive = false;
  state.slidesText = null;
  return { renderText: state.summaryText, summaryText: state.summaryText };
}

type SlidesSnapshot = {
  sourceId: string;
  slides: Array<{ imageUrl?: string | null }>;
};

export function hasResolvedSlidesPayload(
  slides: SlidesSnapshot | null | undefined,
  _seededSourceId: string | null | undefined,
): boolean {
  if (!slides || slides.slides.length === 0) return false;
  return slides.slides.some((slide) => (slide.imageUrl ?? "").trim().length > 0);
}

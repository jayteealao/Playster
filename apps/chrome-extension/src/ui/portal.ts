let overlayRoot: HTMLElement | null = null;

export function getOverlayRoot(): HTMLElement | null {
  if (typeof document === "undefined") return null;
  if (overlayRoot && document.body.contains(overlayRoot)) return overlayRoot;
  const existing = document.getElementById("summarize-overlay-root");
  if (existing instanceof HTMLElement) {
    overlayRoot = existing;
    return overlayRoot;
  }
  overlayRoot = document.createElement("div");
  overlayRoot.id = "summarize-overlay-root";
  document.body.append(overlayRoot);
  return overlayRoot;
}

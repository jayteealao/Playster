import { describe, expect, it } from "vitest";
import { hasResolvedSlidesPayload } from "../apps/chrome-extension/src/entrypoints/sidepanel/slides-pending.js";

describe("sidepanel pending slides", () => {
  it("treats seeded placeholder slides as unresolved", () => {
    expect(
      hasResolvedSlidesPayload(
        {
          sourceId: "youtube-abc123",
          slides: [{ imageUrl: "" }, { imageUrl: null }],
        },
        "youtube-abc123",
      ),
    ).toBe(false);
  });

  it("treats blank-image slides as unresolved even after the seed marker is gone", () => {
    expect(
      hasResolvedSlidesPayload(
        {
          sourceId: "real-source",
          slides: [{ imageUrl: "" }],
        },
        "seeded-source",
      ),
    ).toBe(false);
  });

  it("treats seeded slides with a real image as resolved", () => {
    expect(
      hasResolvedSlidesPayload(
        {
          sourceId: "youtube-abc123",
          slides: [{ imageUrl: "http://127.0.0.1:8787/v1/slides/youtube-abc123/1?v=1" }],
        },
        "youtube-abc123",
      ),
    ).toBe(true);
  });
});

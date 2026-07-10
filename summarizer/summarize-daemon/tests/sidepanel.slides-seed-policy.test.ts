import { describe, expect, it } from "vitest";
import { shouldSeedPlannedSlidesForRun } from "../apps/chrome-extension/src/entrypoints/sidepanel/slides-seed-policy.js";

describe("sidepanel slides seed policy", () => {
  it("seeds planned slides for explicit video mode", () => {
    expect(
      shouldSeedPlannedSlidesForRun({
        durationSeconds: 120,
        inputMode: "video",
        media: null,
        mediaAvailable: false,
        runUrl: "https://example.com/video",
        slidesEnabled: true,
      }),
    ).toBe(true);
  });

  it("seeds planned slides when media arrives before mode flips", () => {
    expect(
      shouldSeedPlannedSlidesForRun({
        durationSeconds: 120,
        inputMode: "page",
        media: { hasVideo: true, hasAudio: true, hasCaptions: false },
        mediaAvailable: false,
        runUrl: "https://example.com/video",
        slidesEnabled: true,
      }),
    ).toBe(true);
  });

  it("seeds planned slides for youtube urls even before media state lands", () => {
    expect(
      shouldSeedPlannedSlidesForRun({
        durationSeconds: 120,
        inputMode: "page",
        media: null,
        mediaAvailable: false,
        runUrl: "https://www.youtube.com/watch?v=abc123",
        slidesEnabled: true,
      }),
    ).toBe(true);
  });

  it("does not seed planned slides without usable duration", () => {
    expect(
      shouldSeedPlannedSlidesForRun({
        durationSeconds: 0,
        inputMode: "video",
        media: { hasVideo: true, hasAudio: true, hasCaptions: false },
        mediaAvailable: true,
        runUrl: "https://www.youtube.com/watch?v=abc123",
        slidesEnabled: true,
      }),
    ).toBe(false);
  });

  it("does not seed planned slides when slides are disabled", () => {
    expect(
      shouldSeedPlannedSlidesForRun({
        durationSeconds: 120,
        inputMode: "video",
        media: { hasVideo: true, hasAudio: true, hasCaptions: false },
        mediaAvailable: true,
        runUrl: "https://www.youtube.com/watch?v=abc123",
        slidesEnabled: false,
      }),
    ).toBe(false);
  });
});

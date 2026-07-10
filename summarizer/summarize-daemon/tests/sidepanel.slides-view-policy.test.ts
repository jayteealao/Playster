import { describe, expect, it } from "vitest";
import {
  resolveSlidesRenderLayout,
  shouldHideSummaryForSlides,
} from "../apps/chrome-extension/src/entrypoints/sidepanel/slides-view-policy.js";

describe("sidepanel slides view policy", () => {
  it("forces gallery layout in video slides mode", () => {
    expect(
      resolveSlidesRenderLayout({
        preferredLayout: "strip",
        slidesEnabled: true,
        inputMode: "video",
      }),
    ).toBe("gallery");
  });

  it("keeps the preferred layout outside slide mode", () => {
    expect(
      resolveSlidesRenderLayout({
        preferredLayout: "strip",
        slidesEnabled: false,
        inputMode: "page",
      }),
    ).toBe("strip");
  });

  it("hides the big summary block once slides are present", () => {
    expect(
      shouldHideSummaryForSlides({
        slidesEnabled: true,
        inputMode: "video",
        hasSlides: true,
      }),
    ).toBe(true);
    expect(
      shouldHideSummaryForSlides({
        slidesEnabled: true,
        inputMode: "video",
        hasSlides: false,
      }),
    ).toBe(false);
  });
});

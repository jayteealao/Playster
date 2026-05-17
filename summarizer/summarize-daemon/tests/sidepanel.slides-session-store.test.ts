import { describe, expect, it } from "vitest";
import { createSlidesSessionStore } from "../apps/chrome-extension/src/entrypoints/sidepanel/slides-session-store.js";

describe("sidepanel slides session store", () => {
  it("resolves effective input mode and increments context ids", () => {
    const store = createSlidesSessionStore({
      slidesEnabled: true,
      slidesParallel: false,
      slidesOcrEnabled: true,
      slidesLayout: "gallery",
    });

    expect(store.resolveInputMode()).toBe("page");
    store.state.inputMode = "video";
    expect(store.resolveInputMode()).toBe("video");
    store.state.inputModeOverride = "page";
    expect(store.resolveInputMode()).toBe("page");

    expect(store.nextSlidesContextRequestId()).toBe(1);
    expect(store.nextSlidesContextRequestId()).toBe(2);
  });
});

import { describe, expect, it } from "vitest";
import { buildIdleSubtitle } from "../apps/chrome-extension/src/lib/header.js";

describe("chrome/header", () => {
  it("keeps only the input summary", () => {
    expect(buildIdleSubtitle({ inputSummary: "1.2k words · 12k chars", modelLabel: "free" })).toBe(
      "1.2k words · 12k chars",
    );
  });

  it("ignores model fallback", () => {
    expect(buildIdleSubtitle({ inputSummary: "12k chars", model: "openrouter/x" })).toBe(
      "12k chars",
    );
  });

  it("trims and skips empty summary", () => {
    expect(buildIdleSubtitle({ inputSummary: "  ", modelLabel: "  free  " })).toBe("");
    expect(buildIdleSubtitle({ inputSummary: null, modelLabel: null, model: null })).toBe("");
  });
});

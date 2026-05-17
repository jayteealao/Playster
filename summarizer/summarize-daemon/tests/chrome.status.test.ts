import { describe, expect, it } from "vitest";
import { splitStatusPercent } from "../apps/chrome-extension/src/lib/status.js";

describe("chrome/status", () => {
  it("splits a trailing percent", () => {
    expect(splitStatusPercent("podcast: transcribing… 12%")).toEqual({
      text: "podcast: transcribing…",
      percent: "12%",
    });
  });

  it("supports percent in parentheses", () => {
    expect(splitStatusPercent("youtube: downloading audio… (34%)")).toEqual({
      text: "youtube: downloading audio…",
      percent: "34%",
    });
  });

  it("does not split when percent is the whole string", () => {
    expect(splitStatusPercent("50%")).toEqual({ text: "50%", percent: null });
  });

  it("ignores invalid percent values", () => {
    expect(splitStatusPercent("transcribing… 120%")).toEqual({
      text: "transcribing… 120%",
      percent: null,
    });
    expect(splitStatusPercent("transcribing… -1%")).toEqual({
      text: "transcribing… -1%",
      percent: null,
    });
  });
});

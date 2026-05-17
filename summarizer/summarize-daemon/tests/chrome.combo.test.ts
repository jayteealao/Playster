import { describe, expect, it } from "vitest";
import {
  readPresetOrCustomValue,
  resolvePresetOrCustom,
} from "../apps/chrome-extension/src/lib/combo.js";

describe("chrome/combo", () => {
  it("uses preset when value matches (case-insensitive)", () => {
    expect(resolvePresetOrCustom({ value: " XL ", presets: ["xl", "short"] })).toEqual({
      presetValue: "xl",
      customValue: "",
      isCustom: false,
    });
  });

  it("uses custom when value is not in presets", () => {
    expect(resolvePresetOrCustom({ value: "20k", presets: ["xl", "short"] })).toEqual({
      presetValue: "custom",
      customValue: "20k",
      isCustom: true,
    });
  });

  it("reads custom value with fallback to default", () => {
    expect(
      readPresetOrCustomValue({ presetValue: "custom", customValue: "  ", defaultValue: "xl" }),
    ).toBe("xl");
    expect(
      readPresetOrCustomValue({ presetValue: "custom", customValue: " 20k ", defaultValue: "xl" }),
    ).toBe("20k");
  });
});

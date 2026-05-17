import { describe, expect, it } from "vitest";
import {
  chooseSlideDescription,
  sanitizeSlideSummaryTitle,
} from "../apps/chrome-extension/src/entrypoints/sidepanel/slide-text-policy.js";

describe("sidepanel slide text policy", () => {
  it("drops generic summary titles", () => {
    expect(sanitizeSlideSummaryTitle("Summary")).toBe("");
    expect(sanitizeSlideSummaryTitle(" slide   summary ")).toBe("");
    expect(sanitizeSlideSummaryTitle("Explosion in FTL")).toBe("Explosion in FTL");
  });

  it("prefers transcript text over summary-era filler", () => {
    expect(
      chooseSlideDescription({
        summaryText: "",
        transcriptText: "Destiny drops out of FTL.",
        ocrText: "OCR text",
        preferOcr: false,
        allowOcrFallback: false,
      }),
    ).toBe("Destiny drops out of FTL.");
  });

  it("returns empty when no transcript or OCR fallback exists", () => {
    expect(
      chooseSlideDescription({
        summaryText: "",
        transcriptText: "",
        ocrText: "ignored",
        preferOcr: false,
        allowOcrFallback: false,
      }),
    ).toBe("");
  });

  it("uses OCR only when explicitly preferred or allowed as fallback", () => {
    expect(
      chooseSlideDescription({
        summaryText: "",
        transcriptText: "",
        ocrText: "Visible slide text",
        preferOcr: true,
        allowOcrFallback: false,
      }),
    ).toBe("Visible slide text");

    expect(
      chooseSlideDescription({
        summaryText: "",
        transcriptText: "",
        ocrText: "Visible slide text",
        preferOcr: false,
        allowOcrFallback: true,
      }),
    ).toBe("Visible slide text");
  });

  it("prefers summary text over transcript text when available", () => {
    expect(
      chooseSlideDescription({
        summaryText: "Londo realizes the room is a setup.",
        transcriptText: "we have a drink lord refa yes thank you",
        ocrText: "OCR text",
        preferOcr: false,
        allowOcrFallback: false,
      }),
    ).toBe("Londo realizes the room is a setup.");
  });
});

import { describe, expect, it } from "vitest";
import { parseChapters } from "../src/summarizer/chapters";

// Fixtures mirror the daemon's guaranteed emit format (its
// ensureSummaryKeyMoments appends `### Key moments` + `- [m:ss] label`
// lines; sanitize tolerates bare timestamps and h:mm:ss).

const PROSE = [
  "# The Future of Design Systems",
  "",
  "Design systems plateau when treated as a library instead of a contract.",
  "",
  "- Component libraries are a means, not the end.",
  "- Tokens should resolve at runtime.",
].join("\n");

describe("parseChapters — daemon emit format", () => {
  it("parses bracketed bullets and strips the section", () => {
    const content = [
      PROSE,
      "",
      "### Key moments",
      "- [0:00] Cold open: the plateau",
      "- [2:14] Why libraries stop scaling",
      "- [6:22] Tokens as a runtime contract",
    ].join("\n");
    const { chapters, strippedContent } = parseChapters(content, 1394);
    expect(chapters).toEqual([
      { t: 0, label: "Cold open: the plateau", dur: 134 },
      { t: 134, label: "Why libraries stop scaling", dur: 248 },
      { t: 382, label: "Tokens as a runtime contract", dur: 1012 },
    ]);
    expect(strippedContent).toBe(PROSE);
    expect(strippedContent).not.toContain("Key moments");
  });

  it("parses bare timestamps and h:mm:ss values", () => {
    const content = [
      PROSE,
      "",
      "## Key moments:",
      "* 0:45 - Opening remarks",
      "- [1:02:03] Deep dive",
    ].join("\n");
    const { chapters } = parseChapters(content, null);
    expect(chapters).toEqual([
      { t: 45, label: "Opening remarks", dur: 3723 - 45 },
      { t: 3723, label: "Deep dive", dur: null },
    ]);
  });

  it("accepts heading variants (case, no hashes, trailing colon)", () => {
    for (const heading of [
      "### Key moments",
      "Key moments",
      "KEY MOMENTS:",
      "## key moments :",
    ]) {
      const content = `${PROSE}\n\n${heading}\n- [1:00] Only moment`;
      const { chapters } = parseChapters(content, null);
      expect(chapters, `heading variant: ${heading}`).toEqual([
        { t: 60, label: "Only moment", dur: null },
      ]);
    }
  });

  it("bounds the final chapter with the transcript max when available", () => {
    const content = `${PROSE}\n\n### Key moments\n- [1:00] A\n- [2:00] B`;
    expect(parseChapters(content, 180).chapters).toEqual([
      { t: 60, label: "A", dur: 60 },
      { t: 120, label: "B", dur: 60 },
    ]);
    // Transcript shorter than the last chapter start: no bound.
    expect(parseChapters(content, 90).chapters?.[1].dur).toBeNull();
    // No transcript at all.
    expect(parseChapters(content, null).chapters?.[1].dur).toBeNull();
  });

  it("only strips up to the next markdown heading", () => {
    const content = [
      PROSE,
      "",
      "### Key moments",
      "- [0:30] The one moment",
      "",
      "### Epilogue",
      "Closing prose that must survive.",
    ].join("\n");
    const { chapters, strippedContent } = parseChapters(content, null);
    expect(chapters).toHaveLength(1);
    expect(strippedContent).toContain("### Epilogue");
    expect(strippedContent).toContain("Closing prose that must survive.");
    expect(strippedContent).not.toContain("Key moments");
  });

  it("sorts a disordered model output ascending", () => {
    const content = `${PROSE}\n\n### Key moments\n- [5:00] Later\n- [1:00] Earlier`;
    const { chapters } = parseChapters(content, null);
    expect(chapters?.map((c) => c.t)).toEqual([60, 300]);
    expect(chapters?.[0].dur).toBe(240);
  });

  it("returns null chapters and untouched content when no section exists", () => {
    const { chapters, strippedContent } = parseChapters(PROSE, 1394);
    expect(chapters).toBeNull();
    expect(strippedContent).toBe(PROSE);
  });

  it("leaves a section with no parseable lines in place", () => {
    const content = [
      PROSE,
      "",
      "### Key moments",
      "No timestamps here, just prose.",
      "- [99:99] malformed seconds",
    ].join("\n");
    const { chapters, strippedContent } = parseChapters(content, null);
    expect(chapters).toBeNull();
    expect(strippedContent).toBe(content);
  });

  it("skips unparseable lines inside an otherwise valid section", () => {
    const content = [
      PROSE,
      "",
      "### Key moments",
      "- [1:00] Valid",
      "- not a moment line",
      "- [2:75] invalid seconds",
      "- [3:00]", // timestamp with no label
      "- [4:00] Also valid",
    ].join("\n");
    const { chapters } = parseChapters(content, null);
    expect(chapters?.map((c) => c.label)).toEqual(["Valid", "Also valid"]);
  });

  it("handles a moments-only degenerate summary (empty stripped prose)", () => {
    const content = "### Key moments\n- [0:10] Everything is a moment";
    const { chapters, strippedContent } = parseChapters(content, null);
    expect(chapters).toEqual([
      { t: 10, label: "Everything is a moment", dur: null },
    ]);
    expect(strippedContent).toBe("");
  });

  it("handles empty input without throwing", () => {
    expect(parseChapters("", 100)).toEqual({
      chapters: null,
      strippedContent: "",
    });
  });

  it("collapses the blank-line gap the stripped section leaves behind", () => {
    const content = [
      "Intro prose.",
      "",
      "### Key moments",
      "- [0:10] Moment",
      "",
      "",
      "### After",
      "More prose.",
    ].join("\n");
    const { strippedContent } = parseChapters(content, null);
    expect(strippedContent).toBe("Intro prose.\n\n### After\nMore prose.");
  });
});

import type { SummaryChapter } from "../models/index.js";

/**
 * Parser for the summarizer's "Key moments" section.
 *
 * The emit grammar is mirrored from the summarizer daemon's own regexes
 * (source: summarizer/summarize-daemon/src/run/flows/url/summary-timestamps.ts —
 * KEY_MOMENTS_HEADING_RE / KEY_MOMENT_LINE_RE / MARKDOWN_HEADING_RE), so the
 * parser accepts exactly what the daemon guarantees on its normalized output:
 * a `Key moments` heading followed by `- [m:ss] label` bullets (bare
 * timestamps and `h:mm:ss` also occur). Parsing NEVER throws — a summary
 * without a parseable section yields `chapters: null` and untouched content.
 */

const KEY_MOMENTS_HEADING_RE = /^\s{0,3}(?:#{1,6}\s*)?Key moments\s*:?\s*$/i;
const MARKDOWN_HEADING_RE = /^\s{0,3}#{1,6}\s+\S/;
const KEY_MOMENT_LINE_RE =
  /^\s*(?:[-*+]\s+)?(?:\[(\d{1,2}:\d{2}(?::\d{2})?)\]|(\d{1,2}:\d{2}(?::\d{2})?))(?=\s|[-:–—])/;

export interface ParsedChapters {
  /** `null` when no Key moments section with parseable lines was found. */
  chapters: SummaryChapter[] | null;
  /**
   * The summary with the Key moments section removed. Identical to the
   * input when `chapters` is `null`.
   */
  strippedContent: string;
}

/** Parse `m:ss` / `h:mm:ss` to seconds; null on malformed values. */
function parseTimestampSeconds(value: string): number | null {
  const rawParts = value.split(":").map((item) => item.trim());
  if (rawParts.length !== 2 && rawParts.length !== 3) return null;
  if (rawParts.some((item) => !/^\d+$/.test(item))) return null;
  const parts = rawParts.map((item) => Number(item));
  if (parts.some((item) => !Number.isFinite(item))) return null;
  if (parts.length === 2) {
    const [minutes, seconds] = parts;
    if (seconds >= 60) return null;
    return minutes * 60 + seconds;
  }
  const [hours, minutes, seconds] = parts;
  if (minutes >= 60 || seconds >= 60) return null;
  return hours * 3600 + minutes * 60 + seconds;
}

/** Strip the timestamp prefix and leading separators to get the label. */
function extractLabel(line: string): string {
  const match = line.match(KEY_MOMENT_LINE_RE);
  if (!match) return "";
  return line
    .slice((match.index ?? 0) + match[0].length)
    .replace(/^[\s\-–—:·]+/, "")
    .trim();
}

/**
 * Locate and parse the Key moments section of a summary.
 *
 * @param content the summary markdown as delivered by the summarizer.
 * @param transcriptMaxSeconds the transcript's last segment start (seconds),
 *   used to bound the final chapter's duration. Pass `null` when no
 *   transcript is available — the final chapter's `dur` becomes `null`.
 */
export function parseChapters(
  content: string,
  transcriptMaxSeconds: number | null = null,
): ParsedChapters {
  if (!content) {
    return { chapters: null, strippedContent: content };
  }

  const lines = content.split("\n");
  const moments: { t: number; label: string }[] = [];
  const keptLines: string[] = [];
  let sawParseableSection = false;

  for (let index = 0; index < lines.length; index += 1) {
    const line = lines[index] ?? "";
    if (!KEY_MOMENTS_HEADING_RE.test(line.trim())) {
      keptLines.push(line);
      continue;
    }

    // Section body runs until the next markdown heading (daemon convention).
    let sectionEnd = index + 1;
    while (sectionEnd < lines.length) {
      const candidate = lines[sectionEnd] ?? "";
      if (MARKDOWN_HEADING_RE.test(candidate.trim())) break;
      sectionEnd += 1;
    }

    const sectionLines = lines.slice(index + 1, sectionEnd);
    const sectionMoments: { t: number; label: string }[] = [];
    for (const sectionLine of sectionLines) {
      const match = sectionLine.match(KEY_MOMENT_LINE_RE);
      if (!match) continue;
      const raw = match[1] ?? match[2] ?? "";
      const t = parseTimestampSeconds(raw);
      if (t == null) continue;
      const label = extractLabel(sectionLine);
      if (!label) continue;
      sectionMoments.push({ t, label });
    }

    if (sectionMoments.length > 0) {
      sawParseableSection = true;
      moments.push(...sectionMoments);
      // Drop the heading + body from the stripped content.
    } else {
      // A heading with no parseable lines is left in place — we only strip
      // what we successfully converted to structured data.
      keptLines.push(line);
      keptLines.push(...sectionLines);
    }
    index = sectionEnd - 1;
  }

  if (!sawParseableSection) {
    return { chapters: null, strippedContent: content };
  }

  // Deterministic order: ascending start time (the daemon emits ascending;
  // sorting shields consumers from a disordered model output).
  moments.sort((a, b) => a.t - b.t);

  const chapters: SummaryChapter[] = moments.map((moment, i) => {
    const next = moments[i + 1];
    if (next) {
      return { t: moment.t, label: moment.label, dur: next.t - moment.t };
    }
    const bounded =
      transcriptMaxSeconds != null && transcriptMaxSeconds >= moment.t
        ? transcriptMaxSeconds - moment.t
        : null;
    return { t: moment.t, label: moment.label, dur: bounded };
  });

  const strippedContent = keptLines
    .join("\n")
    .replace(/\n{3,}/g, "\n\n")
    .trim();

  return { chapters, strippedContent };
}

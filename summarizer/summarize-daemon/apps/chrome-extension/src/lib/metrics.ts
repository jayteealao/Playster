export type MetricsToken =
  | { kind: "text"; text: string }
  | { kind: "link"; text: string; href: string }
  | { kind: "media"; before: string; label: string; after: string; href: string };

const isHttpUrl = (value: string) => /^https?:\/\//i.test(value);
const isLikelyDomain = (value: string) =>
  /^[a-z0-9.-]+\.[a-z]{2,}$/i.test(value) && !value.includes("..");

const normalize = (value: string) => value.replaceAll(/\s+/g, " ").trim().toLowerCase();

function resolveInputParts(inputSummary: string | null | undefined): {
  normalized: Set<string>;
  hasWords: boolean;
  hasMediaDuration: boolean;
} {
  const input = typeof inputSummary === "string" ? inputSummary.trim() : "";
  const parts = input
    ? input
        .split(" · ")
        .map((part) => part.trim())
        .filter(Boolean)
    : [];

  const hasWords = parts.some((part) => /\bwords\b/i.test(part));
  const hasMediaDuration = parts.some((part) => {
    if (!/\b(YouTube|podcast|video)\b/i.test(part)) return false;
    return /\bmin\b/i.test(part) || /\b\d+m\b/i.test(part) || /\b\d+s\b/i.test(part);
  });

  return { normalized: new Set(parts.map(normalize)), hasWords, hasMediaDuration };
}

function shouldOmitPart(
  raw: string,
  input: { normalized: Set<string>; hasWords: boolean; hasMediaDuration: boolean },
): boolean {
  const trimmed = raw.trim();
  if (!trimmed) return true;
  if (input.normalized.has(normalize(trimmed))) return true;
  if (input.hasWords && /\bwords\b/i.test(trimmed)) return true;
  if (
    input.hasMediaDuration &&
    /\b(YouTube|podcast|video)\b/i.test(trimmed) &&
    (/\bmin\b/i.test(trimmed) || /\b\d+m\b/i.test(trimmed) || /\b\d+s\b/i.test(trimmed))
  ) {
    return true;
  }
  return false;
}

export function buildMetricsParts({
  summary,
  inputSummary,
  shortenOpenRouter = false,
}: {
  summary: string;
  inputSummary?: string | null;
  shortenOpenRouter?: boolean;
}): string[] {
  const input = resolveInputParts(inputSummary);
  return summary
    .split(" · ")
    .filter((part) => !shouldOmitPart(part, input))
    .map((part) => {
      if (!shortenOpenRouter) return part;
      const trimmed = part.trim();
      if (!/^openrouter\//i.test(trimmed)) return part;
      return trimmed.replace(/^openrouter\//i, "or/");
    });
}

export function buildMetricsTokens({
  summary,
  inputSummary,
  sourceUrl,
  shortenOpenRouter = false,
}: {
  summary: string;
  inputSummary?: string | null;
  sourceUrl?: string | null;
  shortenOpenRouter?: boolean;
}): MetricsToken[] {
  const parts = buildMetricsParts({ summary, inputSummary, shortenOpenRouter });
  const tokens: MetricsToken[] = [];

  for (const part of parts) {
    const trimmed = part.trim();
    if (!trimmed) continue;
    if (isHttpUrl(trimmed) || isLikelyDomain(trimmed)) {
      tokens.push({
        kind: "link",
        text: trimmed,
        href: isHttpUrl(trimmed) ? trimmed : `https://${trimmed}`,
      });
      continue;
    }
    if (sourceUrl && isHttpUrl(sourceUrl)) {
      const sourceMatch = part.match(/\b(YouTube|podcast|video)\b/i);
      if (sourceMatch?.index != null) {
        const before = part.slice(0, sourceMatch.index);
        const label = sourceMatch[0];
        const after = part.slice(sourceMatch.index + label.length);
        tokens.push({ kind: "media", before, label, after, href: sourceUrl });
        continue;
      }
    }
    tokens.push({ kind: "text", text: part });
  }

  return tokens;
}

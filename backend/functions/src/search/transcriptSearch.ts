import * as admin from "firebase-admin";
import { HttpsError } from "firebase-functions/v2/https";
import * as logger from "firebase-functions/logger";
import { allowlistedCall } from "../auth/verify.js";
import type { TranscriptSegment } from "../models/index.js";

/**
 * Full-text transcript search.
 *
 * Firestore has no native full-text search; at this app's personal-corpus
 * scale the mechanism is an in-function scan over the transcript segments
 * stored INLINE on `transcripts/{videoId}` pointer docs (status ==
 * "available"), behind a per-instance corpus cache.
 *
 * Cost model: a cold search reads every available transcript doc (N reads,
 * low hundreds at this corpus scale, each bounded by the 1 MiB doc cap);
 * warm searches hit the instance cache (~0 reads, 5-minute TTL). The
 * function runs with 512MiB to hold a cached corpus in the tens of MB.
 * sdlc-debt: linear scan over the whole corpus per cold search; ceiling is
 *   corpus growth beyond personal scale — upgrade path is a real search
 *   index (external search service), a scope change routed through design,
 *   not a quiet tweak here.
 */

const MIN_QUERY_CHARS = 2;
const MAX_QUERY_CHARS = 200;
const MAX_HITS_PER_VIDEO = 3;
const MAX_TOTAL_RESULTS = 20;
const SNIPPET_TARGET_CHARS = 200;
const SNIPPET_HARD_CAP_CHARS = 240;
const CORPUS_CACHE_TTL_MS = 5 * 60_000;

// Scoring weights: term frequency is the base signal; a segment containing
// EVERY query term outranks scattered single-term hits; an exact phrase
// match outranks both.
const ALL_TERMS_BOOST = 5;
const PHRASE_BOOST = 10;

// Documented error contract (the Android search repository logs failures by
// `code` — these messages are the human halves of that contract).
const ERR_QUERY_TYPE = "Search query must be a string.";
const ERR_QUERY_LENGTH = `Search query must be ${MIN_QUERY_CHARS}-${MAX_QUERY_CHARS} characters after trimming.`;
const ERR_QUERY_EMPTY = "Search query contains no searchable terms.";
const ERR_LIMIT = `limit must be an integer between 1 and ${MAX_TOTAL_RESULTS}.`;

export interface SearchTranscriptsInput {
  query?: unknown;
  limit?: unknown;
}

export interface TranscriptSearchHit {
  videoId: string;
  /** Start time (seconds) of the best-matching transcript segment. */
  start: number;
  /** ~200-char window around the best-matching segment, ellipsized. */
  snippet: string;
  score: number;
}

export interface SearchTranscriptsOutput {
  /** Ranked best-first: score desc, then videoId asc, then start asc. */
  results: TranscriptSearchHit[];
  /** Available transcripts scanned (docs with inline segments). */
  scannedVideos: number;
  /** True when per-video/total caps cut candidate hits. */
  truncated: boolean;
}

// --- pure core (unit-testable without an emulator) ---

/** Lowercase alphanumeric terms, deduplicated, in first-seen order. */
export function tokenize(query: string): string[] {
  const seen = new Set<string>();
  for (const raw of query.toLowerCase().split(/[^\p{L}\p{N}]+/u)) {
    if (raw.length > 0) seen.add(raw);
  }
  return [...seen];
}

function countOccurrences(haystack: string, needle: string): number {
  let count = 0;
  let index = haystack.indexOf(needle);
  while (index !== -1) {
    count += 1;
    index = haystack.indexOf(needle, index + needle.length);
  }
  return count;
}

/**
 * Score one segment against the query. 0 means no match. Deterministic:
 * term frequency + all-terms-present boost + exact-phrase boost.
 */
export function scoreSegment(
  segmentText: string,
  tokens: string[],
  phrase: string,
): number {
  const text = segmentText.toLowerCase();
  let termFrequency = 0;
  let termsPresent = 0;
  for (const token of tokens) {
    const occurrences = countOccurrences(text, token);
    if (occurrences > 0) {
      termsPresent += 1;
      termFrequency += occurrences;
    }
  }
  if (termFrequency === 0) return 0;
  let score = termFrequency;
  if (termsPresent === tokens.length && tokens.length > 1) {
    score += ALL_TERMS_BOOST;
  }
  if (phrase.length >= MIN_QUERY_CHARS && text.includes(phrase)) {
    score += PHRASE_BOOST;
  }
  return score;
}

/**
 * Build a ~200-char snippet centered on the best-matching segment,
 * extended with neighboring segments, ellipsized at cut edges.
 */
export function buildSnippet(
  segments: TranscriptSegment[],
  bestIndex: number,
): string {
  let lo = bestIndex;
  let hi = bestIndex;
  let text = (segments[bestIndex]?.text ?? "").trim();
  while (text.length < SNIPPET_TARGET_CHARS && hi < segments.length - 1) {
    hi += 1;
    text = `${text} ${(segments[hi]?.text ?? "").trim()}`;
  }
  while (text.length < SNIPPET_TARGET_CHARS && lo > 0) {
    lo -= 1;
    text = `${(segments[lo]?.text ?? "").trim()} ${text}`;
  }
  if (text.length > SNIPPET_HARD_CAP_CHARS) {
    text = `${text.slice(0, SNIPPET_HARD_CAP_CHARS).replace(/\s+\S*$/, "")}…`;
  } else if (hi < segments.length - 1) {
    text = `${text}…`;
  }
  if (lo > 0) {
    text = `…${text}`;
  }
  return text;
}

export interface CorpusEntry {
  videoId: string;
  segments: TranscriptSegment[];
}

/** Search the corpus. Pure — no Firestore access. */
export function searchCorpus(
  corpus: CorpusEntry[],
  query: string,
  limit: number,
): { results: TranscriptSearchHit[]; truncated: boolean } {
  const tokens = tokenize(query);
  const phrase = query.toLowerCase().replace(/\s+/g, " ").trim();
  if (tokens.length === 0) {
    return { results: [], truncated: false };
  }

  const hits: TranscriptSearchHit[] = [];
  let candidateCount = 0;
  for (const entry of corpus) {
    const perVideo: TranscriptSearchHit[] = [];
    for (let i = 0; i < entry.segments.length; i += 1) {
      const segment = entry.segments[i];
      if (!segment || typeof segment.text !== "string") continue;
      const score = scoreSegment(segment.text, tokens, phrase);
      if (score <= 0) continue;
      perVideo.push({
        videoId: entry.videoId,
        start: typeof segment.start === "number" ? segment.start : 0,
        snippet: buildSnippet(entry.segments, i),
        score,
      });
    }
    candidateCount += perVideo.length;
    perVideo.sort((a, b) => b.score - a.score || a.start - b.start);
    hits.push(...perVideo.slice(0, MAX_HITS_PER_VIDEO));
  }

  hits.sort(
    (a, b) =>
      b.score - a.score ||
      a.videoId.localeCompare(b.videoId) ||
      a.start - b.start,
  );
  const results = hits.slice(0, limit);
  return { results, truncated: candidateCount > results.length };
}

// --- corpus loader (per-instance cache) ---

interface CorpusCache {
  entries: CorpusEntry[];
  scannedVideos: number;
  skippedVideos: number;
  loadedAt: number;
}

let corpusCache: CorpusCache | null = null;

async function loadCorpus(now: number): Promise<CorpusCache> {
  if (corpusCache && now - corpusCache.loadedAt < CORPUS_CACHE_TTL_MS) {
    return corpusCache;
  }
  const snap = await admin
    .firestore()
    .collection("transcripts")
    .where("status", "==", "available")
    .get();
  const entries: CorpusEntry[] = [];
  let skippedVideos = 0;
  for (const doc of snap.docs) {
    const data = doc.data() as {
      videoId?: unknown;
      segments?: unknown;
    };
    const segments = Array.isArray(data.segments)
      ? (data.segments as TranscriptSegment[])
      : [];
    if (segments.length === 0) {
      // Available transcript without inline segments (blob-only) — nothing
      // to scan; counted separately from the scanned corpus.
      skippedVideos += 1;
      continue;
    }
    entries.push({
      videoId: typeof data.videoId === "string" ? data.videoId : doc.id,
      segments,
    });
  }
  corpusCache = {
    entries,
    scannedVideos: entries.length,
    skippedVideos,
    loadedAt: now,
  };
  return corpusCache;
}

function resetCorpusCache(): void {
  corpusCache = null;
}

// --- validation + callable ---

function validateQuery(raw: unknown): string {
  if (typeof raw !== "string") {
    throw new HttpsError("invalid-argument", ERR_QUERY_TYPE);
  }
  const query = raw.trim();
  if (query.length < MIN_QUERY_CHARS || query.length > MAX_QUERY_CHARS) {
    throw new HttpsError("invalid-argument", ERR_QUERY_LENGTH);
  }
  if (tokenize(query).length === 0) {
    throw new HttpsError("invalid-argument", ERR_QUERY_EMPTY);
  }
  return query;
}

function validateLimit(raw: unknown): number {
  if (raw === undefined || raw === null) return MAX_TOTAL_RESULTS;
  if (
    typeof raw !== "number" ||
    !Number.isInteger(raw) ||
    raw < 1 ||
    raw > MAX_TOTAL_RESULTS
  ) {
    throw new HttpsError("invalid-argument", ERR_LIMIT);
  }
  return raw;
}

/**
 * Search all available transcripts. Allowlisted operator only —
 * `unauthenticated` / `permission-denied` come from the shared gate;
 * `invalid-argument` for a malformed query or limit.
 */
export const searchTranscripts = allowlistedCall<
  SearchTranscriptsInput,
  SearchTranscriptsOutput
>({ memory: "512MiB", timeoutSeconds: 60 }, async (req) => {
  const query = validateQuery(req.data?.query);
  const limit = validateLimit(req.data?.limit);

  const startedAt = Date.now();
  try {
    const corpus = await loadCorpus(startedAt);
    const { results, truncated } = searchCorpus(corpus.entries, query, limit);

    // Structured completion line — the query TEXT is never logged (PII rule).
    logger.info("searchTranscripts: completed", {
      tokens: tokenize(query).length,
      scannedVideos: corpus.scannedVideos,
      skippedVideos: corpus.skippedVideos,
      results: results.length,
      ms: Date.now() - startedAt,
    });

    return { results, scannedVideos: corpus.scannedVideos, truncated };
  } catch (err) {
    // Structured failure line — mirrors webhook.ts/dispatch.ts. The query
    // TEXT and transcript content are never logged (PII rule); a token
    // count is fine, matching the completion line above.
    logger.error("searchTranscripts: failed", {
      errorCode: err instanceof HttpsError ? err.code : "internal",
      errorMessage: err instanceof Error ? err.message : String(err),
      tokens: tokenize(query).length,
      ms: Date.now() - startedAt,
    });
    throw err;
  }
});

/** Test seams. */
export const __searchInternals = {
  resetCorpusCache,
  CORPUS_CACHE_TTL_MS,
  MAX_HITS_PER_VIDEO,
  MAX_TOTAL_RESULTS,
  ERR_QUERY_TYPE,
  ERR_QUERY_LENGTH,
  ERR_QUERY_EMPTY,
  ERR_LIMIT,
};

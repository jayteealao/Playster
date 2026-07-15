import { beforeAll, beforeEach, describe, expect, it } from "vitest";
import * as admin from "firebase-admin";
import { clearFirestore, initAdminEmulator } from "./helpers/admin";

const ALLOWLISTED_UID = "ALLOWLISTED_UID_FOR_TESTS";
// Must be set before importing src/ — defineString() resolves at module load.
process.env.ALLOWED_UID = ALLOWLISTED_UID;

import functionsTestFactory from "firebase-functions-test";
import {
  buildSnippet,
  scoreSegment,
  searchCorpus,
  tokenize,
  __searchInternals,
  type CorpusEntry,
} from "../src/search/transcriptSearch";

// --- pure core (no emulator needed) ---

describe("search pure core", () => {
  it("tokenize lowercases, splits on non-alphanumerics, dedupes", () => {
    expect(tokenize("Design Tokens, design-tokens!")).toEqual([
      "design",
      "tokens",
    ]);
    expect(tokenize("  ...  ")).toEqual([]);
    expect(tokenize("a11y is the SYSTEM")).toEqual([
      "a11y",
      "is",
      "the",
      "system",
    ]);
  });

  it("scoreSegment ranks phrase > all-terms > scattered frequency", () => {
    const tokens = tokenize("design contract");
    const phrase = "design contract";
    const phraseHit = scoreSegment(
      "the design contract is a promise",
      tokens,
      phrase,
    );
    const allTerms = scoreSegment(
      "a contract about design behavior",
      tokens,
      phrase,
    );
    const oneTerm = scoreSegment("the contract said so", tokens, phrase);
    const noMatch = scoreSegment("nothing relevant here", tokens, phrase);
    expect(phraseHit).toBeGreaterThan(allTerms);
    expect(allTerms).toBeGreaterThan(oneTerm);
    expect(oneTerm).toBeGreaterThan(0);
    expect(noMatch).toBe(0);
  });

  it("buildSnippet windows around the best segment and ellipsizes cut edges", () => {
    const segments = Array.from({ length: 10 }, (_, i) => ({
      start: i * 10,
      text: `segment ${i} carries some words of ordinary transcript prose here`,
    }));
    const snippet = buildSnippet(segments, 5);
    expect(snippet).toContain("segment 5");
    expect(snippet.startsWith("…")).toBe(true);
    expect(snippet.length).toBeLessThanOrEqual(260);
  });

  it("searchCorpus applies deterministic tiebreak and caps", () => {
    const mkSegments = (text: string, n: number) =>
      Array.from({ length: n }, (_, i) => ({ start: i, text }));
    const corpus: CorpusEntry[] = [
      { videoId: "vid-b", segments: mkSegments("needle in prose", 5) },
      { videoId: "vid-a", segments: mkSegments("needle in prose", 5) },
    ];
    const { results, truncated } = searchCorpus(corpus, "needle", 20);
    // Per-video cap: 3 of 5 matching segments each.
    expect(results).toHaveLength(6);
    expect(truncated).toBe(true);
    // Equal scores: videoId asc, then start asc.
    expect(results.map((r) => r.videoId)).toEqual([
      "vid-a",
      "vid-a",
      "vid-a",
      "vid-b",
      "vid-b",
      "vid-b",
    ]);
    expect(results.slice(0, 3).map((r) => r.start)).toEqual([0, 1, 2]);
    // Repeated call: byte-identical.
    expect(searchCorpus(corpus, "needle", 20)).toEqual({
      results,
      truncated,
    });
  });
});

// --- callable integration (Firestore emulator) ---

const LONG_TRANSCRIPT_PARAGRAPHS = 2000;

function fillerSegment(i: number): { start: number; text: string } {
  return {
    start: i * 8,
    text:
      `Paragraph ${i} of the long fixture talks about ordinary production ` +
      "matters, release cadence, and the usual episode housekeeping.",
  };
}

async function seedCorpus(): Promise<void> {
  const db = admin.firestore();
  // The known-best match: exact phrase, twice in one segment's neighborhood.
  await db.doc("transcripts/vid-best").set({
    videoId: "vid-best",
    status: "available",
    language: "en",
    segments: [
      { start: 0, text: "Alright, let's just get into it." },
      {
        start: 78,
        text: "Tokens stop being a paint bucket and start being the API your product reads from at runtime.",
      },
      { start: 88, text: "Components stop being a destination." },
    ],
    createdAt: new Date(),
    updatedAt: new Date(),
  });
  // A weaker match: one term only.
  await db.doc("transcripts/vid-weak").set({
    videoId: "vid-weak",
    status: "available",
    language: "en",
    segments: [
      { start: 12, text: "We repainted the studio with an actual paint roller." },
    ],
    createdAt: new Date(),
    updatedAt: new Date(),
  });
  // Non-available doc: must never be scanned.
  await db.doc("transcripts/vid-pending").set({
    videoId: "vid-pending",
    status: "pending",
    createdAt: new Date(),
    updatedAt: new Date(),
  });
  // Available but segment-less (blob-only): skipped, not scanned.
  await db.doc("transcripts/vid-blob-only").set({
    videoId: "vid-blob-only",
    status: "available",
    gcsPath: "transcripts/vid-blob-only.txt",
    createdAt: new Date(),
    updatedAt: new Date(),
  });
  // The 2,000-paragraph stressor with one needle deep inside.
  const longSegments = Array.from(
    { length: LONG_TRANSCRIPT_PARAGRAPHS },
    (_, i) => fillerSegment(i),
  );
  longSegments[1500] = {
    start: 1500 * 8,
    text: "And buried right here sits the zebra-crossing anecdote nobody expects.",
  };
  await db.doc("transcripts/vid-long").set({
    videoId: "vid-long",
    status: "available",
    language: "en",
    segments: longSegments,
    createdAt: new Date(),
    updatedAt: new Date(),
  });
}

describe("searchTranscripts callable — emulator-backed (AC2)", () => {
  let functionsTest: ReturnType<typeof functionsTestFactory>;
  let mod: typeof import("../src/search/transcriptSearch");

  beforeAll(async () => {
    initAdminEmulator();
    functionsTest = functionsTestFactory({ projectId: "demo-playster" });
    mod = await import("../src/search/transcriptSearch");
    await clearFirestore();
    await seedCorpus();
  });

  beforeEach(() => {
    // Each test sees a fresh corpus read (the TTL cache is per-instance).
    __searchInternals.resetCorpusCache();
  });

  function wrapped() {
    return functionsTest.wrap(mod.searchTranscripts);
  }

  function asOwner(data: unknown) {
    return wrapped()({
      data,
      auth: { uid: ALLOWLISTED_UID, token: {} },
    } as never);
  }

  it("(a) matching query → ranked results with snippet + videoId + start, best first", async () => {
    const res = (await asOwner({
      query: "paint bucket",
    })) as import("../src/search/transcriptSearch").SearchTranscriptsOutput;

    expect(res.results.length).toBeGreaterThanOrEqual(2);
    // Exact-phrase segment wins over the single-term match.
    expect(res.results[0].videoId).toBe("vid-best");
    expect(res.results[0].start).toBe(78);
    expect(res.results[0].snippet).toContain("paint bucket");
    for (const hit of res.results) {
      expect(typeof hit.videoId).toBe("string");
      expect(typeof hit.start).toBe("number");
      expect(hit.snippet.length).toBeGreaterThan(0);
      expect(hit.score).toBeGreaterThan(0);
    }
    // vid-best, vid-weak, vid-long scanned; pending + blob-only excluded.
    expect(res.scannedVideos).toBe(3);
  });

  it("(a) finds the needle in the 2,000-paragraph transcript within a sane wall clock", async () => {
    const t0 = Date.now();
    const res = (await asOwner({
      query: "zebra-crossing anecdote",
    })) as import("../src/search/transcriptSearch").SearchTranscriptsOutput;
    const elapsed = Date.now() - t0;

    expect(res.results[0].videoId).toBe("vid-long");
    expect(res.results[0].start).toBe(1500 * 8);
    expect(res.results[0].snippet).toContain("zebra-crossing");
    // Includes the cold corpus read of all seeded docs; the scan itself is
    // in-memory. Bound is generous but far below the 30s test timeout.
    expect(elapsed).toBeLessThan(10_000);
  });

  it("(b) no-match query → empty result set, not an error", async () => {
    const res = (await asOwner({
      query: "quetzalcoatlus flight dynamics",
    })) as import("../src/search/transcriptSearch").SearchTranscriptsOutput;
    expect(res.results).toEqual([]);
    expect(res.truncated).toBe(false);
    expect(res.scannedVideos).toBe(3);
  });

  it("(c) invalid inputs → documented invalid-argument contract", async () => {
    await expect(asOwner({})).rejects.toMatchObject({
      code: "invalid-argument",
      message: __searchInternals.ERR_QUERY_TYPE,
    });
    await expect(asOwner({ query: 42 })).rejects.toMatchObject({
      code: "invalid-argument",
      message: __searchInternals.ERR_QUERY_TYPE,
    });
    await expect(asOwner({ query: "x" })).rejects.toMatchObject({
      code: "invalid-argument",
      message: __searchInternals.ERR_QUERY_LENGTH,
    });
    await expect(asOwner({ query: "   x   " })).rejects.toMatchObject({
      code: "invalid-argument",
      message: __searchInternals.ERR_QUERY_LENGTH,
    });
    await expect(
      asOwner({ query: "y".repeat(201) }),
    ).rejects.toMatchObject({
      code: "invalid-argument",
      message: __searchInternals.ERR_QUERY_LENGTH,
    });
    await expect(asOwner({ query: "?! ..." })).rejects.toMatchObject({
      code: "invalid-argument",
      message: __searchInternals.ERR_QUERY_EMPTY,
    });
    await expect(
      asOwner({ query: "valid query", limit: 0 }),
    ).rejects.toMatchObject({
      code: "invalid-argument",
      message: __searchInternals.ERR_LIMIT,
    });
    await expect(
      asOwner({ query: "valid query", limit: 3.5 }),
    ).rejects.toMatchObject({
      code: "invalid-argument",
      message: __searchInternals.ERR_LIMIT,
    });
  });

  it("auth gate: unauthenticated and stranger calls are rejected", async () => {
    await expect(
      wrapped()({ data: { query: "paint bucket" }, auth: undefined } as never),
    ).rejects.toMatchObject({ code: "unauthenticated" });
    await expect(
      wrapped()({
        data: { query: "paint bucket" },
        auth: { uid: "stranger", token: {} },
      } as never),
    ).rejects.toMatchObject({ code: "permission-denied" });
  });

  it("determinism: repeated identical calls return identical payloads", async () => {
    const first = await asOwner({ query: "paint" });
    __searchInternals.resetCorpusCache();
    const second = await asOwner({ query: "paint" });
    expect(second).toEqual(first);
  });

  it("corpus cache: new docs are invisible until the TTL cache resets", async () => {
    const before = (await asOwner({
      query: "unmistakable fresh needle",
    })) as import("../src/search/transcriptSearch").SearchTranscriptsOutput;
    expect(before.results).toEqual([]);

    await admin.firestore().doc("transcripts/vid-fresh").set({
      videoId: "vid-fresh",
      status: "available",
      segments: [{ start: 3, text: "an unmistakable fresh needle appears" }],
      createdAt: new Date(),
      updatedAt: new Date(),
    });

    // Same instance, warm cache: still invisible.
    const warm = (await asOwner({
      query: "unmistakable fresh needle",
    })) as import("../src/search/transcriptSearch").SearchTranscriptsOutput;
    expect(warm.results).toEqual([]);

    // Cache reset (TTL expiry equivalent): visible.
    __searchInternals.resetCorpusCache();
    const cold = (await asOwner({
      query: "unmistakable fresh needle",
    })) as import("../src/search/transcriptSearch").SearchTranscriptsOutput;
    expect(cold.results[0]?.videoId).toBe("vid-fresh");

    // Clean up so scannedVideos assertions in earlier-run-order tests are
    // unaffected on re-runs (fileParallelism is off; order is stable).
    await admin.firestore().doc("transcripts/vid-fresh").delete();
    __searchInternals.resetCorpusCache();
  });
});

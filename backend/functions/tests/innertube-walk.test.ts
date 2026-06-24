import { describe, it, expect } from "vitest";
import { existsSync, readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";
import {
  walk,
  extractVideoFromRenderer,
  WalkStats,
  type ExtractedVideo,
} from "../src/youtube/innertube-sync.js";

/**
 * Pure regression test for the Watch Later renderer extraction. The walk is a
 * pure function of its JSON input, so this needs no Firestore emulator and runs
 * under both `pnpm test` and `pnpm test:unit`.
 *
 * Guards the HIGH-severity defect where every Watch Later video synced with an
 * empty title/channel/thumbnail because metadata was read as a sibling of the
 * videoId node, while the TV /browse VLWL response carries it in a separate
 * `lockupViewModel` subtree.
 *
 * The inline lockup objects below pin the EXTRACTOR contract against the
 * documented InnerTube TV field paths (yt-dlp #13665 / NewPipeExtractor #1320).
 * The PRODUCTION shape is pinned separately by the redacted real capture in
 * innertube-browse-wl-lockup.json — that assertion is operator-gated (the
 * capture needs a live TV-OAuth session) and stays skipped until the fixture
 * lands, then activates automatically.
 */

const fixturesDir = join(dirname(fileURLToPath(import.meta.url)), "fixtures");

// eslint-disable-next-line @typescript-eslint/no-explicit-any
function loadFixture(name: string): any {
  return JSON.parse(readFileSync(join(fixturesDir, name), "utf8"));
}

function runWalk(
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  page: any,
): {
  items: Map<string, ExtractedVideo>;
  stats: WalkStats;
  conts: Set<string>;
} {
  const items = new Map<string, ExtractedVideo>();
  const conts = new Set<string>();
  const stats = new WalkStats();
  walk(page, items, conts, stats);
  return { items, stats, conts };
}

describe("extractVideoFromRenderer", () => {
  it("extracts a lockupViewModel from the documented field paths", () => {
    const lockup = {
      contentId: "LOCKUPvid01",
      metadata: {
        lockupMetadataViewModel: {
          title: { content: "Lockup Sample Video" },
          metadata: {
            contentMetadataViewModel: {
              metadataRows: [
                {
                  metadataParts: [
                    {
                      text: {
                        content: "Lockup Sample Channel",
                        commandRuns: [
                          {
                            onTap: {
                              innertubeCommand: {
                                browseEndpoint: {
                                  browseId: "UCLOCKUPchannel000001",
                                },
                              },
                            },
                          },
                        ],
                      },
                    },
                  ],
                },
                {
                  metadataParts: [
                    { text: { content: "1.2M views" } },
                    { text: { content: "3 days ago" } },
                  ],
                },
              ],
            },
          },
        },
      },
      contentImage: {
        thumbnailViewModel: {
          image: {
            sources: [
              {
                url: "https://i.ytimg.com/vi/LOCKUPvid01/mqdefault.jpg",
                width: 320,
                height: 180,
              },
              {
                url: "https://i.ytimg.com/vi/LOCKUPvid01/maxresdefault.jpg",
                width: 1280,
                height: 720,
              },
            ],
          },
          overlays: [
            {
              thumbnailOverlayBadgeViewModel: {
                thumbnailBadges: [
                  { thumbnailBadgeViewModel: { text: "10:30" } },
                ],
              },
            },
          ],
        },
      },
    };

    const v = extractVideoFromRenderer(lockup);
    expect(v).toBeDefined();
    expect(v?.id).toBe("LOCKUPvid01");
    expect(v?.title).toBe("Lockup Sample Video");
    expect(v?.channelTitle).toBe("Lockup Sample Channel");
    expect(v?.channelId).toBe("UCLOCKUPchannel000001");
    expect(v?.duration).toBe("10:30");
    expect(v?.thumbnailUrl).toBe(
      "https://i.ytimg.com/vi/LOCKUPvid01/maxresdefault.jpg",
    );
  });

  it("recovers a lockup videoId from the nested watchEndpoint when contentId is absent", () => {
    const lockup = {
      metadata: {
        lockupMetadataViewModel: { title: { content: "No contentId" } },
      },
      contentImage: { thumbnailViewModel: { image: { sources: [] } } },
      rendererContext: {
        commandContext: {
          onTap: {
            innertubeCommand: { watchEndpoint: { videoId: "WATCHEPvid1" } },
          },
        },
      },
    };

    const v = extractVideoFromRenderer(lockup);
    expect(v?.id).toBe("WATCHEPvid1");
    expect(v?.title).toBe("No contentId");
  });

  it("branches the thumbnail to collectionThumbnailViewModel for a playlist lockup", () => {
    const lockup = {
      contentId: "PLAYLISTcol",
      metadata: {
        lockupMetadataViewModel: { title: { content: "A Playlist" } },
      },
      contentImage: {
        collectionThumbnailViewModel: {
          primaryThumbnail: {
            thumbnailViewModel: {
              image: {
                sources: [{ url: "https://i.ytimg.com/coll.jpg", width: 480 }],
              },
            },
          },
        },
      },
    };

    const v = extractVideoFromRenderer(lockup);
    expect(v?.id).toBe("PLAYLISTcol");
    expect(v?.thumbnailUrl).toBe("https://i.ytimg.com/coll.jpg");
  });

  it("falls back to legacy playlistVideoRenderer co-located fields", () => {
    const legacy = {
      videoId: "LEGACYINLINE",
      title: { runs: [{ text: "Inline Legacy" }] },
      shortBylineText: {
        runs: [
          {
            text: "Inline Channel",
            navigationEndpoint: { browseEndpoint: { browseId: "UCinline" } },
          },
        ],
      },
      lengthText: { simpleText: "5:00" },
      thumbnail: {
        thumbnails: [{ url: "https://i.ytimg.com/leg.jpg", width: 480 }],
      },
    };

    const v = extractVideoFromRenderer(legacy);
    expect(v?.id).toBe("LEGACYINLINE");
    expect(v?.title).toBe("Inline Legacy");
    expect(v?.channelTitle).toBe("Inline Channel");
    expect(v?.channelId).toBe("UCinline");
    expect(v?.duration).toBe("5:00");
    expect(v?.thumbnailUrl).toBe("https://i.ytimg.com/leg.jpg");
  });
});

describe("walk", () => {
  it("lets a richer renderer backfill an empty prior capture (relaxed dedup)", () => {
    // A lockup wrapper whose nested watchEndpoint repeats the same videoId as a
    // bare node — the rich renderer capture must NOT be clobbered by the bare
    // one, and the bare node must not inflate the empty-title count.
    const page = {
      contents: [
        {
          lockupViewModel: {
            contentId: "DEDUPEvid01",
            metadata: {
              lockupMetadataViewModel: { title: { content: "Rich Title" } },
            },
            contentImage: {
              thumbnailViewModel: {
                image: { sources: [{ url: "https://t/x.jpg", width: 480 }] },
              },
            },
            rendererContext: {
              commandContext: {
                onTap: {
                  innertubeCommand: {
                    watchEndpoint: { videoId: "DEDUPEvid01" },
                  },
                },
              },
            },
          },
        },
      ],
    };

    const { items, stats } = runWalk(page);
    expect(items.size).toBe(1);
    expect(items.get("DEDUPEvid01")?.title).toBe("Rich Title");
    expect(items.get("DEDUPEvid01")?.thumbnailUrl).toBe("https://t/x.jpg");
    expect(stats.emptyTitleCount).toBe(0);
  });

  it("captures a bare watchEndpoint videoId and flags it empty (tripwire)", () => {
    const page = { contents: [{ watchEndpoint: { videoId: "BAREvideo01" } }] };
    const { items, stats } = runWalk(page);
    expect(items.has("BAREvideo01")).toBe(true);
    expect(items.get("BAREvideo01")?.title).toBe("");
    expect(stats.emptyTitleCount).toBe(1);
  });

  it("extracts the legacy fixture via the fallback path and collects its continuation", () => {
    const { items, stats, conts } = runWalk(
      loadFixture("innertube-browse-wl-legacy.json"),
    );
    const v = items.get("LEGACYvid001");
    expect(v).toBeDefined();
    expect(v?.title).toBe("Legacy Sample Video One");
    expect(v?.channelTitle).toBe("Legacy Sample Channel");
    expect(v?.channelId).toBe("UCLEGACYchannel00000001");
    expect(v?.duration).toBe("12:34");
    expect(v?.thumbnailUrl).toBe(
      "https://i.ytimg.com/vi/LEGACYvid001/hqdefault.jpg",
    );
    expect(stats.emptyTitleCount).toBe(0);
    expect([...conts]).toContain("LEGACY_CONTINUATION_TOKEN_PLACEHOLDER");
  });

  // Operator-gated production-shape guard. The redacted real /browse VLWL page
  // can only be captured from a live TV-OAuth session (see
  // docs/operations/backfill-watch-later.md). Until that fixture is committed
  // this stays skipped; it activates automatically the moment the file lands.
  const lockupFixture = join(fixturesDir, "innertube-browse-wl-lockup.json");
  it.skipIf(!existsSync(lockupFixture))(
    "every video on the real redacted VLWL page has non-empty metadata",
    () => {
      const { items, stats } = runWalk(
        loadFixture("innertube-browse-wl-lockup.json"),
      );
      expect(items.size).toBeGreaterThan(0);
      for (const [id, v] of items) {
        expect(id.length).toBeGreaterThanOrEqual(6);
        expect(v.title.length).toBeGreaterThan(0);
        expect(v.channelTitle.length).toBeGreaterThan(0);
        expect(v.thumbnailUrl.length).toBeGreaterThan(0);
      }
      // The production page is the contract: no blank rows, no tripwire.
      expect(stats.emptyTitleCount).toBe(0);
    },
  );
});

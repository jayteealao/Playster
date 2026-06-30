import * as admin from "firebase-admin";
import type {
  PlaylistDocument,
  VideoDocument,
  WatchLaterSyncState,
} from "../models/index.js";

const WATCH_LATER_ID = "WL";
const WATCH_LATER_BROWSE_ID = "VLWL";
const SYNC_STATE_DOC_PATH = "sync_state/innertube-watch-later";
const MAX_BATCH_SIZE = 500;
const MAX_PAGES_PER_RUN = 500;
// Cloud Run egress IPs get rate-limited harder than residential. YouTube's
// abuse-detection is keyed per access_token; rebuilding the Innertube client
// helps a few times then re-trips. We pace + retry + rebuild a bounded number
// of times, then stash the cursor and let the next scheduled run continue.
const INTER_PAGE_DELAY_MS = 1000;
const RETRY_BACKOFF_MS = 4000;
const MAX_CLIENT_REBUILDS = 10;
// Cloud Functions kills the request at timeoutSeconds; we need to exit
// cleanly before then or lose the state-write. 540s timeout - 60s buffer.
const WALLCLOCK_BUDGET_MS = 480_000;
// Save state every N pages so a timeout kill loses at most that much work.
const CHECKPOINT_EVERY_PAGES = 20;

export interface ExtractedVideo {
  id: string;
  title: string;
  channelTitle: string;
  channelId: string;
  duration: string;
  thumbnailUrl: string;
}

// eslint-disable-next-line @typescript-eslint/no-explicit-any
type Json = any;

function extractText(node: Json): string | undefined {
  if (node == null) return undefined;
  if (typeof node === "string") return node;
  if (typeof node !== "object") return undefined;
  if (typeof node.simpleText === "string") return node.simpleText;
  if (Array.isArray(node.runs) && node.runs[0]?.text) return node.runs[0].text;
  // lockupViewModel / *ViewModel text nodes carry a flat `content` string.
  if (typeof node.content === "string") return node.content;
  if (typeof node.text === "string") return node.text;
  return undefined;
}

/**
 * Pick the highest-resolution URL from a YouTube image array. Legacy renderers
 * expose `thumbnail.thumbnails[]`; viewModels expose `image.sources[]` — both
 * are arrays of `{ url, width, height }`, so one helper serves both.
 */
function pickBestUrl(arr: Json): string | undefined {
  if (!Array.isArray(arr) || !arr.length) return undefined;
  const best = arr.reduce((a, b) =>
    (b?.width ?? 0) > (a?.width ?? 0) ? b : a,
  );
  return typeof best?.url === "string" ? best.url : undefined;
}

function extractThumbnailUrl(node: Json): string | undefined {
  if (!node || typeof node !== "object") return undefined;
  return pickBestUrl(node.thumbnails);
}

/**
 * Extract a video from a "flat" node whose videoId is co-located with its
 * title / byline / thumbnail. Covers the legacy playlist/grid/compact video
 * renderers AND serves as the bare-node safety net: a node carrying only a
 * `videoId` (e.g. a TV `watchEndpoint`) yields the id with empty metadata
 * instead of being silently dropped, so the empty-title tripwire surfaces it.
 */
function extractFromLegacy(node: Json): ExtractedVideo | undefined {
  if (typeof node.videoId !== "string") return undefined;

  const title =
    extractText(node.title) ??
    extractText(node.headline) ??
    extractText(node.metadata?.title) ??
    extractText(node.metadata?.tileMetadataRenderer?.title) ??
    "";

  const channelTitle =
    extractText(node.shortBylineText) ??
    extractText(node.longBylineText) ??
    extractText(node.metadata?.shortBylineText) ??
    "";

  let channelId = "";
  const byline =
    node.shortBylineText?.runs?.[0]?.navigationEndpoint?.browseEndpoint
      ?.browseId;
  if (typeof byline === "string") channelId = byline;

  const duration =
    extractText(node.lengthText) ??
    extractText(
      node.thumbnailOverlays?.[0]?.thumbnailOverlayTimeStatusRenderer?.text,
    ) ??
    "";

  const thumbnailUrl = extractThumbnailUrl(node.thumbnail) ?? "";

  return {
    id: node.videoId,
    title,
    channelTitle,
    channelId,
    duration,
    thumbnailUrl,
  };
}

/**
 * lockupViewModel videoId: prefer the flat `contentId`, fall back to the nested
 * `watchEndpoint` carried by the renderer's tap command.
 */
function lockupVideoId(lockup: Json): string | undefined {
  if (typeof lockup.contentId === "string") return lockup.contentId;
  const we =
    lockup.rendererContext?.commandContext?.onTap?.innertubeCommand
      ?.watchEndpoint?.videoId ??
    lockup.onTap?.innertubeCommand?.watchEndpoint?.videoId;
  return typeof we === "string" ? we : undefined;
}

/**
 * lockupViewModel channel: first non-empty text part across the metadata rows.
 * Also recovers the channel browseId when the part carries a tap command.
 */
function lockupChannel(meta: Json): { title: string; id: string } {
  const rows = meta?.metadata?.contentMetadataViewModel?.metadataRows;
  if (Array.isArray(rows)) {
    for (const row of rows) {
      const parts = row?.metadataParts;
      if (!Array.isArray(parts)) continue;
      for (const part of parts) {
        const title = extractText(part?.text);
        if (!title) continue;
        const id =
          part?.text?.commandRuns?.[0]?.onTap?.innertubeCommand?.browseEndpoint
            ?.browseId;
        return { title, id: typeof id === "string" ? id : "" };
      }
    }
  }
  return { title: "", id: "" };
}

/**
 * lockupViewModel duration: the badge overlay shipped in two shapes across the
 * 2024-2025 rollout (`thumbnailOverlayBadgeViewModel` and the older
 * `thumbnailBottomOverlayViewModel`) — try both, default empty.
 */
function lockupDuration(thumb: Json): string {
  const overlays = thumb?.overlays;
  if (!Array.isArray(overlays)) return "";
  for (const ov of overlays) {
    const badges = ov?.thumbnailOverlayBadgeViewModel?.thumbnailBadges;
    if (Array.isArray(badges)) {
      for (const b of badges) {
        const t = extractText(b?.thumbnailBadgeViewModel?.text);
        if (t) return t;
      }
    }
    const bottom = extractText(
      ov?.thumbnailBottomOverlayViewModel?.badges?.[0]?.thumbnailBadgeViewModel
        ?.text,
    );
    if (bottom) return bottom;
  }
  return "";
}

/**
 * tileRenderer videoId: the flat `contentId`, falling back to the nested
 * `onSelectCommand` watchEndpoint.
 */
function tileVideoId(tile: Json): string | undefined {
  if (typeof tile.contentId === "string") return tile.contentId;
  const we = tile.onSelectCommand?.watchEndpoint?.videoId;
  return typeof we === "string" ? we : undefined;
}

/**
 * tileRenderer channel: first non-empty text part across the metadata lines.
 * The byline is the first line; later lines carry views / age.
 */
function tileChannel(meta: Json): string {
  const lines = meta?.lines;
  if (Array.isArray(lines)) {
    for (const line of lines) {
      const parts = line?.lineRenderer?.items;
      if (!Array.isArray(parts)) continue;
      for (const part of parts) {
        const t = extractText(part?.lineItemRenderer?.text);
        if (t) return t;
      }
    }
  }
  return "";
}

/**
 * tileRenderer duration: the time-status overlay carried by the tile header.
 */
function tileDuration(header: Json): string {
  const overlays = header?.thumbnailOverlays;
  if (Array.isArray(overlays)) {
    for (const ov of overlays) {
      const t = extractText(ov?.thumbnailOverlayTimeStatusRenderer?.text);
      if (t) return t;
    }
  }
  return "";
}

/**
 * tileRenderer (the dominant item in the TVHTML5 `/browse` VLWL response). The
 * videoId is the flat `contentId`; title and byline live under
 * `metadata.tileMetadataRenderer` and the thumbnail under
 * `header.tileHeaderRenderer` — none are siblings of the videoId, so the old
 * sibling-read yielded empty rows.
 */
function extractFromTile(tile: Json): ExtractedVideo | undefined {
  const id = tileVideoId(tile);
  if (!id) return undefined;

  const meta = tile.metadata?.tileMetadataRenderer;
  const header = tile.header?.tileHeaderRenderer;

  return {
    id,
    title: extractText(meta?.title) ?? "",
    channelTitle: tileChannel(meta),
    channelId: "",
    duration: tileDuration(header),
    thumbnailUrl: extractThumbnailUrl(header?.thumbnail) ?? "",
  };
}

/**
 * Renderer-aware video extraction. In the InnerTube TV `/browse` VLWL response
 * the dominant item is a `tileRenderer` (TVHTML5 client) or a `lockupViewModel`,
 * whose videoId lives on a nested node while title / byline / thumbnail live
 * elsewhere in the renderer subtree — so reading metadata as siblings of the
 * videoId (the old behaviour) always yielded empty rows. Pull videoId AND
 * metadata from the one renderer subtree in a single pass; fall back to the
 * legacy co-located shape for the minority. `renderer` is the inner renderer
 * object (the value of `node.tileRenderer` / `node.lockupViewModel` / …).
 */
export function extractVideoFromRenderer(
  renderer: Json,
): ExtractedVideo | undefined {
  if (!renderer || typeof renderer !== "object") return undefined;

  // tileRenderer (TVHTML5 primary). Distinctive: a tileMetadataRenderer under
  // metadata and/or a tileHeaderRenderer under header. Checked BEFORE lockup:
  // tileRenderer also carries a flat `contentId`, which would otherwise misroute
  // it into the lockup branch and yield an empty title.
  const isTile =
    renderer.metadata?.tileMetadataRenderer != null ||
    renderer.header?.tileHeaderRenderer != null;
  if (isTile) {
    return extractFromTile(renderer);
  }

  // lockupViewModel (primary). Distinctive: contentId / lockupMetadataViewModel
  // / a contentImage carrying a (collection)thumbnailViewModel, OR the videoId
  // is resolvable via the lockup paths (ensures future variants whose id lives
  // on watchEndpoint always take the richer extraction path).
  const isLockup =
    typeof renderer.contentId === "string" ||
    renderer.metadata?.lockupMetadataViewModel != null ||
    renderer.contentImage?.thumbnailViewModel != null ||
    renderer.contentImage?.collectionThumbnailViewModel != null ||
    lockupVideoId(renderer) != null;

  if (isLockup) {
    const id = lockupVideoId(renderer);
    if (!id) return undefined;

    const lmvm = renderer.metadata?.lockupMetadataViewModel;
    const title = extractText(lmvm?.title) ?? "";
    const channel = lockupChannel(lmvm);

    // Video items use thumbnailViewModel; a playlist-in-WL item nests it under
    // collectionThumbnailViewModel.primaryThumbnail.
    const thumb =
      renderer.contentImage?.thumbnailViewModel ??
      renderer.contentImage?.collectionThumbnailViewModel?.primaryThumbnail
        ?.thumbnailViewModel;
    const thumbnailUrl = pickBestUrl(thumb?.image?.sources) ?? "";

    return {
      id,
      title,
      channelTitle: channel.title,
      channelId: channel.id,
      duration: lockupDuration(thumb),
      thumbnailUrl,
    };
  }

  // Legacy playlist/grid/compact video renderer — co-located fields.
  return extractFromLegacy(renderer);
}

/**
 * Run-level walk statistics. Uses two Sets (never cleared with `pending`) so
 * that `emptyTitleCount` remains accurate across periodic checkpoint flushes:
 * a videoId that was first captured empty and then backfilled with a title on
 * a later page (after a flush) is correctly counted as titled, not empty.
 */
export class WalkStats {
  /** Every videoId ever captured this run. */
  readonly seenIds = new Set<string>();
  /** Every videoId seen with a non-empty title at least once this run. */
  readonly titledIds = new Set<string>();

  /** True run-level count of videoIds never seen with a title. */
  get emptyTitleCount(): number {
    return this.seenIds.size - this.titledIds.size;
  }
}

/**
 * Insert an extracted video, relaxing first-seen-wins so a later, richer node
 * backfills an empty prior capture. This is what stops a bare videoId node
 * from locking out the renderer that actually carries the metadata.
 */
function capture(
  items: Map<string, ExtractedVideo>,
  stats: WalkStats,
  v: ExtractedVideo,
): void {
  const prior = items.get(v.id);
  // Always track at the run level (survives checkpoint flushes).
  stats.seenIds.add(v.id);
  if (v.title) stats.titledIds.add(v.id);

  if (!prior) {
    items.set(v.id, v);
  } else if (!prior.title && v.title) {
    items.set(v.id, v); // a richer node backfills an empty capture
  }
}

export function walk(
  node: Json,
  items: Map<string, ExtractedVideo>,
  continuations: Set<string>,
  stats: WalkStats,
): void {
  if (node == null) return;
  if (Array.isArray(node)) {
    for (const v of node) walk(v, items, continuations, stats);
    return;
  }
  if (typeof node !== "object") return;

  // Renderer boundary first: in the TV /browse VLWL response a video item is a
  // tileRenderer (TVHTML5) or a lockupViewModel — in both, the videoId and its
  // metadata live in separate subtrees. Extract videoId + metadata from that one
  // renderer in a single pass; legacy playlist/grid/compact renderers carry
  // co-located fields.
  const renderer =
    node.tileRenderer ??
    node.lockupViewModel ??
    node.playlistVideoRenderer ??
    node.gridVideoRenderer ??
    node.compactVideoRenderer;
  if (renderer) {
    const v = extractVideoFromRenderer(renderer);
    if (v) capture(items, stats, v);
  } else if (typeof node.videoId === "string") {
    // Flat-field fallback / safety net: a videoId outside a recognised renderer
    // (older co-located shapes, or a bare watchEndpoint on a drifted renderer).
    // Co-located metadata is captured when present; a bare node yields an empty
    // row so the tripwire surfaces it instead of dropping the videoId.
    const v = extractFromLegacy(node);
    if (v) capture(items, stats, v);
  }

  if (typeof node.continuationCommand?.token === "string") {
    continuations.add(node.continuationCommand.token);
  }
  if (
    typeof node.token === "string" &&
    node.request === "CONTINUATION_REQUEST_TYPE_BROWSE"
  ) {
    continuations.add(node.token);
  }
  if (typeof node.continuation === "string") {
    continuations.add(node.continuation);
  }

  for (const k of Object.keys(node)) {
    walk(node[k], items, continuations, stats);
  }
}

async function readSyncState(): Promise<WatchLaterSyncState | null> {
  const doc = await admin.firestore().doc(SYNC_STATE_DOC_PATH).get();
  if (!doc.exists) return null;
  return doc.data() as WatchLaterSyncState;
}

async function writeSyncState(
  state: Partial<WatchLaterSyncState>,
): Promise<void> {
  await admin
    .firestore()
    .doc(SYNC_STATE_DOC_PATH)
    .set(
      {
        ...state,
        last_run_at: admin.firestore.FieldValue.serverTimestamp(),
      },
      { merge: true },
    );
}

export interface WatchLaterSyncResult {
  videoCount: number;
  pagesThisRun: number;
  totalItemsKnown: number;
  complete: boolean;
  rebuilds: number;
  videoIds: string[];
  /**
   * Count of videoIds captured this run with an empty title. Non-zero means a
   * renderer the extractor doesn't fully understand (a lockupViewModel variant
   * or a youtubei.js shape change) slipped through to a blank row — surfaced in
   * Cloud logs so the next blank-card regression is caught early, not in the UI.
   */
  emptyTitleCount: number;
}

/**
 * Sync the Watch Later playlist via InnerTube TV-OAuth, resumable across
 * invocations. Each call picks up from the last saved continuation token and
 * walks pages until rate-limited or naturally exhausted. The scheduled sync
 * job (every 6h) will eventually walk the whole playlist over a few runs.
 *
 * Pass { reset: true } to force a fresh walk from the beginning.
 */
/**
 * Flush pending items to Firestore and update the cursor state. Called both
 * mid-run (periodic checkpoint) and at the end. Returns the number of new
 * positions consumed so the caller can update its running offset.
 */
async function flushCheckpoint(
  pending: Map<string, ExtractedVideo>,
  positionStart: number,
  nextToken: string | null,
  pageNum: number,
  priorTotal: number,
  complete: boolean,
): Promise<{ wrote: number; ids: string[] }> {
  const db = admin.firestore();
  const playlistRef = db.collection("playlists").doc(WATCH_LATER_ID);
  const items = [...pending.values()];

  const playlistPatch: Partial<PlaylistDocument> = {
    title: "Watch Later",
    source: "innertube",
    lastSyncedAt: admin.firestore.FieldValue.serverTimestamp(),
  };
  if (complete) {
    playlistPatch.videoCount = priorTotal + items.length;
  } else if (priorTotal + items.length > 0) {
    playlistPatch.videoCount = priorTotal + items.length;
  }

  let batch = db.batch();
  let opCount = 0;

  batch.set(playlistRef, playlistPatch, { merge: true });
  opCount++;

  for (let i = 0; i < items.length; i++) {
    const video = items[i];
    // Build a partial payload: always write the fields the InnerTube path
    // populates; omit publishedAt/addedAt/viewCount (InnerTube never has them)
    // and omit channelId/duration when empty so a future populated value from
    // another writer is never clobbered by an empty re-sync merge:true write.
    const videoDoc: Partial<VideoDocument> & {
      videoId: string;
      position: number;
    } = {
      videoId: video.id,
      title: video.title,
      channelTitle: video.channelTitle,
      thumbnailUrl: video.thumbnailUrl,
      position: positionStart + i,
    };
    if (video.channelId) videoDoc.channelId = video.channelId;
    if (video.duration) videoDoc.duration = video.duration;

    const videoRef = playlistRef.collection("videos").doc(video.id);
    batch.set(videoRef, videoDoc, { merge: true });
    opCount++;

    if (opCount >= MAX_BATCH_SIZE) {
      await batch.commit();
      batch = db.batch();
      opCount = 0;
    }
  }

  if (opCount > 0) await batch.commit();

  await writeSyncState({
    next_continuation_token: complete ? null : nextToken,
    pages_completed: complete ? 0 : pageNum,
    last_position: complete ? 0 : positionStart + items.length,
    total_items: complete ? 0 : priorTotal + items.length,
    complete,
  });

  return { wrote: items.length, ids: items.map((v) => v.id) };
}

/**
 * After a full walk completes cleanly, the extractor has written a non-empty
 * title for every video currently in Watch Later. Any doc that still has an
 * empty title is therefore stale — an orphan left by an earlier (broken) sync,
 * or a video since removed from the list — and would otherwise show as a blank
 * row and collide on `position` with a current video. Delete them in bounded
 * batches. The caller gates this on a clean run (`emptyTitleCount === 0`), so a
 * renderer-drift run that legitimately produced blanks never deletes a real
 * current video; the empty-title tripwire surfaces that case instead.
 */
async function pruneEmptyTitleVideos(): Promise<number> {
  const db = admin.firestore();
  const videosRef = db
    .collection("playlists")
    .doc(WATCH_LATER_ID)
    .collection("videos");
  let deleted = 0;
  for (;;) {
    const snap = await videosRef.where("title", "==", "").limit(MAX_BATCH_SIZE).get();
    if (snap.empty) break;
    const batch = db.batch();
    for (const doc of snap.docs) batch.delete(doc.ref);
    await batch.commit();
    deleted += snap.size;
    if (snap.size < MAX_BATCH_SIZE) break;
  }
  return deleted;
}

export async function syncWatchLater(
  opts: { reset?: boolean } = {},
): Promise<WatchLaterSyncResult> {
  const startedAt = Date.now();
  const overBudget = () => Date.now() - startedAt > WALLCLOCK_BUDGET_MS;

  const { getInnertubeTvClient } = await import("../auth/index.js");
  let innertube = await getInnertubeTvClient();
  let clientRebuilds = 0;

  const priorState = opts.reset ? null : await readSyncState();
  const resuming =
    !opts.reset && priorState?.next_continuation_token && !priorState.complete;

  // Accumulator that gets flushed periodically. Tracks items NOT yet written.
  const pending = new Map<string, ExtractedVideo>();
  // Run-level extraction tripwire, accumulated across every page's walk().
  // seenIds/titledIds are never cleared with pending, so emptyTitleCount
  // remains accurate across checkpoint flushes.
  const walkStats = new WalkStats();
  const visitedTokens = new Set<string>();
  let nextToken: string | undefined;
  let pageNum = 0;
  let pagesThisRun = 0;
  let positionCursor = resuming ? priorState.last_position : 0;
  let priorTotal = resuming ? priorState.total_items : 0;
  let totalNewItemsThisRun = 0;
  const collectedIds: string[] = [];

  if (resuming) {
    console.log(
      `[innertube-sync] resuming: pages_completed=${priorState.pages_completed} total_so_far=${priorState.total_items}`,
    );
    nextToken = priorState.next_continuation_token ?? undefined;
    pageNum = priorState.pages_completed;
  } else {
    console.log("[innertube-sync] starting fresh walk from browseId=VLWL");
    try {
      const firstResp = (await innertube.actions.execute("/browse", {
        browseId: WATCH_LATER_BROWSE_ID,
        // eslint-disable-next-line @typescript-eslint/no-explicit-any
      })) as any;
      const pageContinuations = new Set<string>();
      walk(firstResp.data ?? firstResp, pending, pageContinuations, walkStats);
      nextToken = [...pageContinuations][0];
      pageNum = 1;
      pagesThisRun = 1;
    } catch (err) {
      console.error("[innertube-sync] initial /browse failed:", err);
      throw err;
    }
  }

  while (nextToken) {
    if (overBudget()) {
      console.warn(
        `[innertube-sync] wallclock budget exceeded after page ${pageNum}, flushing and deferring`,
      );
      break;
    }
    if (pagesThisRun >= MAX_PAGES_PER_RUN) {
      console.warn(
        `[innertube-sync] hit MAX_PAGES_PER_RUN=${MAX_PAGES_PER_RUN}, deferring rest to next run`,
      );
      break;
    }
    visitedTokens.add(nextToken);
    pageNum++;
    pagesThisRun++;

    await new Promise((r) => setTimeout(r, INTER_PAGE_DELAY_MS));

    const before = pending.size;
    const nextContinuations = new Set<string>();
    let success = false;
    for (const attempt of [1, 2]) {
      try {
        const resp = (await innertube.actions.execute("/browse", {
          continuation: nextToken,
          // eslint-disable-next-line @typescript-eslint/no-explicit-any
        })) as any;
        walk(resp.data ?? resp, pending, nextContinuations, walkStats);
        success = true;
        break;
      } catch (err) {
        const msg = err instanceof Error ? err.message : String(err);
        if (attempt === 1 && /status 4\d\d|status 5\d\d/.test(msg)) {
          console.warn(
            `[innertube-sync] page ${pageNum} attempt 1 failed (${msg}); retrying after ${RETRY_BACKOFF_MS}ms`,
          );
          await new Promise((r) => setTimeout(r, RETRY_BACKOFF_MS));
          continue;
        }
        console.warn(`[innertube-sync] page ${pageNum} failed:`, msg);
        break;
      }
    }

    if (!success) {
      if (clientRebuilds >= MAX_CLIENT_REBUILDS) {
        console.warn(
          `[innertube-sync] hit MAX_CLIENT_REBUILDS=${MAX_CLIENT_REBUILDS}, deferring rest to next run`,
        );
        break;
      }
      clientRebuilds++;
      console.log(
        `[innertube-sync] rebuilding Innertube client (rebuild #${clientRebuilds})`,
      );
      await new Promise((r) => setTimeout(r, RETRY_BACKOFF_MS));
      try {
        innertube = await getInnertubeTvClient();
      } catch (err) {
        console.warn(
          "[innertube-sync] client rebuild failed, stopping:",
          err instanceof Error ? err.message : err,
        );
        break;
      }
      visitedTokens.delete(nextToken);
      pageNum--;
      pagesThisRun--;
      continue;
    }

    if (pending.size === before) {
      console.log(
        `[innertube-sync] page ${pageNum} returned no new items, treating as end of list`,
      );
      nextToken = undefined;
      break;
    }

    nextToken = [...nextContinuations].find((t) => !visitedTokens.has(t));

    // Periodic checkpoint: flush pending and update cursor so a timeout kill
    // never loses more than CHECKPOINT_EVERY_PAGES pages of work.
    if (pagesThisRun > 0 && pagesThisRun % CHECKPOINT_EVERY_PAGES === 0) {
      const { wrote, ids } = await flushCheckpoint(
        pending,
        positionCursor,
        nextToken ?? null,
        pageNum,
        priorTotal,
        false,
      );
      console.log(
        `[innertube-sync] checkpoint at page ${pageNum}: +${wrote} items (running total ${priorTotal + wrote})`,
      );
      positionCursor += wrote;
      priorTotal += wrote;
      totalNewItemsThisRun += wrote;
      collectedIds.push(...ids);
      pending.clear();
    }
  }

  // Final flush.
  const complete = !nextToken;
  const { wrote, ids } = await flushCheckpoint(
    pending,
    positionCursor,
    nextToken ?? null,
    pageNum,
    priorTotal,
    complete,
  );
  totalNewItemsThisRun += wrote;
  collectedIds.push(...ids);
  const totalItemsKnown =
    (resuming ? priorState.total_items : 0) + totalNewItemsThisRun;

  console.log(
    `[innertube-sync] this run: +${totalNewItemsThisRun} videos across ${pagesThisRun} page(s), rebuilds=${clientRebuilds}, complete=${complete}, total_known=${totalItemsKnown}`,
  );

  // Tripwire: any video that reached Firestore with a blank title means a
  // renderer slipped past the extractor. Surface the ratio so a silent
  // re-break (renderer A/B flip, youtubei.js drift) shows up in Cloud logs.
  if (walkStats.emptyTitleCount > 0) {
    console.warn(
      `[innertube-sync] ${walkStats.emptyTitleCount}/${totalNewItemsThisRun} videos captured with EMPTY title this run — possible renderer drift; check the committed fixture and extractor field paths`,
    );
  }

  // On a clean, completed walk, sweep stale blank orphans left by earlier syncs
  // so they stop colliding on `position` with current videos. Skipped when this
  // run produced any blank (possible drift) — see pruneEmptyTitleVideos.
  if (complete && walkStats.emptyTitleCount === 0) {
    const pruned = await pruneEmptyTitleVideos();
    if (pruned > 0) {
      console.log(
        `[innertube-sync] pruned ${pruned} stale empty-title video doc(s) after full walk`,
      );
    }
  }

  return {
    videoCount: totalNewItemsThisRun,
    pagesThisRun,
    totalItemsKnown,
    complete,
    rebuilds: clientRebuilds,
    videoIds: collectedIds,
    emptyTitleCount: walkStats.emptyTitleCount,
  };
}

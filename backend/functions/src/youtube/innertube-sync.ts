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

interface ExtractedVideo {
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
  if (typeof node.text === "string") return node.text;
  return undefined;
}

function extractThumbnailUrl(node: Json): string | undefined {
  if (!node || typeof node !== "object") return undefined;
  const thumbs = node.thumbnails;
  if (Array.isArray(thumbs) && thumbs.length) {
    const best = thumbs.reduce((a, b) => ((b?.width ?? 0) > (a?.width ?? 0) ? b : a));
    if (typeof best?.url === "string") return best.url;
  }
  return undefined;
}

function walk(
  node: Json,
  items: Map<string, ExtractedVideo>,
  continuations: Set<string>,
): void {
  if (node == null) return;
  if (Array.isArray(node)) {
    for (const v of node) walk(v, items, continuations);
    return;
  }
  if (typeof node !== "object") return;

  if (typeof node.videoId === "string" && !items.has(node.videoId)) {
    const title =
      extractText(node.title) ??
      extractText(node.headline) ??
      extractText(node.metadata?.title) ??
      extractText(node.metadata?.tileMetadataRenderer?.title) ??
      extractText(node.metadata?.lockupMetadataViewModel?.title) ??
      "";

    const channelTitle =
      extractText(node.shortBylineText) ??
      extractText(node.longBylineText) ??
      extractText(node.metadata?.shortBylineText) ??
      "";

    let channelId = "";
    const byline = node.shortBylineText?.runs?.[0]?.navigationEndpoint?.browseEndpoint?.browseId;
    if (typeof byline === "string") channelId = byline;

    const duration =
      extractText(node.lengthText) ??
      extractText(node.thumbnailOverlays?.[0]?.thumbnailOverlayTimeStatusRenderer?.text) ??
      "";

    const thumbnailUrl = extractThumbnailUrl(node.thumbnail) ?? "";

    items.set(node.videoId, {
      id: node.videoId,
      title,
      channelTitle,
      channelId,
      duration,
      thumbnailUrl,
    });
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

  for (const k of Object.keys(node)) walk(node[k], items, continuations);
}

async function readSyncState(): Promise<WatchLaterSyncState | null> {
  const doc = await admin.firestore().doc(SYNC_STATE_DOC_PATH).get();
  if (!doc.exists) return null;
  return doc.data() as WatchLaterSyncState;
}

async function writeSyncState(state: Partial<WatchLaterSyncState>): Promise<void> {
  await admin.firestore().doc(SYNC_STATE_DOC_PATH).set(
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
    const videoDoc: VideoDocument = {
      videoId: video.id,
      title: video.title,
      channelTitle: video.channelTitle,
      channelId: video.channelId,
      duration: video.duration,
      thumbnailUrl: video.thumbnailUrl,
      publishedAt: "",
      viewCount: 0,
      position: positionStart + i,
      addedAt: "",
    };

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
    !opts.reset &&
    priorState?.next_continuation_token &&
    !priorState.complete;

  // Accumulator that gets flushed periodically. Tracks items NOT yet written.
  const pending = new Map<string, ExtractedVideo>();
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
      walk(firstResp.data ?? firstResp, pending, pageContinuations);
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
        walk(resp.data ?? resp, pending, nextContinuations);
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
  const totalItemsKnown = (resuming ? priorState.total_items : 0) + totalNewItemsThisRun;

  console.log(
    `[innertube-sync] this run: +${totalNewItemsThisRun} videos across ${pagesThisRun} page(s), rebuilds=${clientRebuilds}, complete=${complete}, total_known=${totalItemsKnown}`,
  );

  return {
    videoCount: totalNewItemsThisRun,
    pagesThisRun,
    totalItemsKnown,
    complete,
    rebuilds: clientRebuilds,
    videoIds: collectedIds,
  };
}

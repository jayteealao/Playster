/*
 * Diagnosis: why ~224 synced videos have no summary doc.
 *
 * The auto-enqueue path (`autoEnqueueSafe` called at the tail of each sync
 * callable) was wired into all four sync functions starting at commit 5009bb3b.
 * Videos that were already present in Firestore before that commit were never
 * passed through `enqueueAutoSummary` — they have a `playlists/{id}/videos/{videoId}`
 * doc but no `summaries/{videoId}` doc. This is not a bug in the current code;
 * it is a one-time gap from the period before the forward guarantee existed.
 *
 * Expected exclusions that will appear as `failed-permanent` after reconcile:
 * Private and Deleted placeholder rows (title "[Private video]" / "[Deleted video]")
 * that the summarizer cannot process. These are noise, not failures; the
 * enqueue-everything-missing policy (PO-approved in shape Round 4) sweeps them
 * in intentionally.
 *
 * Fix: `reconcileAll()` below performs a one-time, idempotent backfill. It
 * pages through every doc in the `collectionGroup("videos")` query (which spans
 * all `playlists/{id}/videos` subcollections in one scan), collects every
 * `videoId` field, and feeds them to `enqueueAutoSummary()`. That function's
 * per-doc transaction already skips any video whose `summaries/{id}` doc exists
 * in any status — so running the callable twice is always safe.
 */

import * as admin from "firebase-admin";
import * as logger from "firebase-functions/logger";
import { DISPATCHER_BATCH_SIZE } from "./constants.js";
import { enqueueAutoSummary } from "./autoEnqueue.js";

export interface ReconcileResult {
  total: number;
  enqueued: number;
  skipped: number;
}

/**
 * One-time idempotent backfill: page through all `playlists/{id}/videos/{videoId}`
 * docs, collect every `videoId`, and call `enqueueAutoSummary` for each page.
 * Videos that already have a `summaries/` doc (any status) are skipped by
 * `enqueueAutoSummary`'s transaction guard — no separate check needed here.
 *
 * @returns totals accumulated across all pages.
 */
export async function reconcileAll(): Promise<ReconcileResult> {
  const db = admin.firestore();
  let total = 0;
  let enqueued = 0;
  let skipped = 0;

  const baseQuery = db
    .collectionGroup("videos")
    .orderBy(admin.firestore.FieldPath.documentId())
    .limit(DISPATCHER_BATCH_SIZE);

  let lastDoc: admin.firestore.DocumentSnapshot | null = null;
  let hasMore = true;

  logger.info("reconcileAll: starting collectionGroup scan");

  while (hasMore) {
    const pageQuery = lastDoc ? baseQuery.startAfter(lastDoc) : baseQuery;
    const page: admin.firestore.QuerySnapshot = await pageQuery.get();

    if (page.empty) {
      hasMore = false;
    } else {
      const videoIds: string[] = [];
      for (const doc of page.docs) {
        const vid = doc.data().videoId as string | undefined;
        if (vid) videoIds.push(vid);
      }

      total += videoIds.length;

      if (videoIds.length > 0) {
        const result = await enqueueAutoSummary(videoIds);
        enqueued += result.enqueued;
        skipped += result.skipped;
      }

      lastDoc = page.docs[page.docs.length - 1];
      hasMore = page.docs.length === DISPATCHER_BATCH_SIZE;
    }
  }

  logger.info("reconcileAll: complete", { total, enqueued, skipped });
  return { total, enqueued, skipped };
}

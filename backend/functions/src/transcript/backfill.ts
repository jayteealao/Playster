import * as admin from "firebase-admin";
import { onSchedule } from "firebase-functions/v2/scheduler";
import * as logger from "firebase-functions/logger";
import { acquireCronLock, releaseCronLock } from "../summarizer/lock.js";
import { fetchTranscript } from "./fetch.js";
import {
  TRANSCRIPT_BACKFILL_BATCH_SIZE,
  TRANSCRIPT_BACKFILL_LOCK_DOC_PATH,
  TRANSCRIPT_TERMINAL_STATUSES,
} from "./constants.js";

/**
 * Page size for the collectionGroup("videos") scan — same as reconcileAll().
 * Deliberately larger than TRANSCRIPT_BACKFILL_BATCH_SIZE so one page-load can
 * saturate the batch cap even when most videos are already at terminal status.
 */
const VIDEO_PAGE_SIZE = 100;

/**
 * Core backfill logic. Pages through all `playlists/{id}/videos/{videoId}` docs,
 * batch-reads the corresponding `transcripts/{videoId}` pointer docs, filters out
 * videos at a terminal status (`available` or `unavailable`), and calls
 * `fetchTranscript(videoId)` for each video in the capped batch — sequentially,
 * to avoid a burst of parallel YouTube requests that triggers egress blocking.
 *
 * Holds the distributed lock for the duration to prevent concurrent executions.
 *
 * @returns counts of attempted and skipped videos for this tick.
 */
export async function fetchTranscriptBackfill(): Promise<{
  attempted: number;
  skipped: number;
}> {
  const lockToken = await acquireCronLock(TRANSCRIPT_BACKFILL_LOCK_DOC_PATH);
  if (!lockToken) {
    logger.info("transcriptBackfillCron: lock held by another instance");
    return { attempted: 0, skipped: 0 };
  }

  let attempted = 0;
  let skipped = 0;

  try {
    const db = admin.firestore();
    const batch: string[] = [];

    // Page through collectionGroup("videos") collecting all videoId strings.
    // Stop early once the not-yet-fetched batch is full.
    const baseQuery = db
      .collectionGroup("videos")
      .orderBy(admin.firestore.FieldPath.documentId())
      .limit(VIDEO_PAGE_SIZE);

    let lastDoc: admin.firestore.DocumentSnapshot | null = null;
    let hasMore = true;

    while (hasMore && batch.length < TRANSCRIPT_BACKFILL_BATCH_SIZE) {
      const pageQuery = lastDoc ? baseQuery.startAfter(lastDoc) : baseQuery;
      const page: admin.firestore.QuerySnapshot = await pageQuery.get();

      if (page.empty) {
        hasMore = false;
        break;
      }

      // Collect videoId strings from this page.
      const pageVideoIds: string[] = [];
      for (const doc of page.docs) {
        const vid = doc.data().videoId as string | undefined;
        if (vid) pageVideoIds.push(vid);
      }

      if (pageVideoIds.length > 0) {
        // Batch-read pointer docs for this page of videos.
        const pointerRefs = pageVideoIds.map((id) =>
          db.doc(`transcripts/${id}`),
        );
        const pointerSnaps = await db.getAll(...pointerRefs);

        for (let i = 0; i < pageVideoIds.length; i++) {
          if (batch.length >= TRANSCRIPT_BACKFILL_BATCH_SIZE) break;

          const snap = pointerSnaps[i];
          const status = snap.exists
            ? (snap.data()?.status as string | undefined)
            : undefined;

          // Skip videos already at a terminal status.
          const isTerminal =
            status !== undefined &&
            (TRANSCRIPT_TERMINAL_STATUSES as readonly string[]).includes(
              status,
            );

          if (isTerminal) {
            skipped++;
          } else {
            // Missing pointer doc or transient status — eligible for fetch.
            batch.push(pageVideoIds[i]);
          }
        }
      }

      lastDoc = page.docs[page.docs.length - 1];
      hasMore = page.docs.length === VIDEO_PAGE_SIZE;
    }

    attempted = batch.length;
    logger.info("transcriptBackfillCron: starting batch", {
      attempted,
      skipped,
    });

    // Sequential execution — avoids a burst of parallel YouTube requests in a
    // single tick, which is the egress-block risk. Each fetchTranscript() call
    // handles its own error paths (transient/unavailable pointer writes) and
    // never throws, so the loop does not need a per-item try/catch.
    for (const videoId of batch) {
      await fetchTranscript(videoId);
    }
  } finally {
    await releaseCronLock(
      TRANSCRIPT_BACKFILL_LOCK_DOC_PATH,
      "transcriptBackfillCron",
      lockToken,
    );
  }

  logger.info("transcriptBackfillCron: complete", { attempted, skipped });
  return { attempted, skipped };
}

/**
 * Scheduled Cloud Function: hourly transcript backfill cron.
 * Processes a capped batch of not-yet-fetched videos per tick.
 *
 * Schedule: every 1 hour — matches the summarySweeper cadence.
 * At 50 videos/tick × 24 ticks/day ≈ 1,200 videos/day.
 * Expected drain time for ~8k videos: ~6.7 days (a property, not a failure).
 *
 * sdlc-debt: batch cap (50) and schedule ("every 1 hours") are conservative;
 *   upgrade path = raise TRANSCRIPT_BACKFILL_BATCH_SIZE if zero YouTube
 *   egress blocks observed after first full drain pass.
 */
export const transcriptBackfillCron = onSchedule(
  {
    schedule: "every 1 hours",
    memory: "512MiB",
    timeoutSeconds: 540,
    maxInstances: 1,
  },
  async () => {
    const result = await fetchTranscriptBackfill();
    logger.info("transcriptBackfillCron: tick complete", result);
  },
);

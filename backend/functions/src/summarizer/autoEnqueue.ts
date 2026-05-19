import * as admin from "firebase-admin";
import * as logger from "firebase-functions/logger";
import type { SummaryDocument } from "../models/index.js";

export interface AutoEnqueueResult {
  enqueued: number;
  skipped: number;
}

const CHUNK_SIZE = 200;

function chunk<T>(items: T[], size: number): T[][] {
  const out: T[][] = [];
  for (let i = 0; i < items.length; i += size) {
    out.push(items.slice(i, i + size));
  }
  return out;
}

/**
 * Auto-summarize policy (PO Round 2): every newly-synced video gets a
 * queued summary doc. Idempotent — re-running over the same videoIds will
 * not create duplicates because `summaries/{videoId}` is keyed by videoId.
 *
 * Quota is NOT checked here. The scheduled dispatcher drains the queue
 * within whatever per-minute / per-day budget remains.
 */
export async function enqueueAutoSummary(
  videoIds: string[],
): Promise<AutoEnqueueResult> {
  const unique = [...new Set(videoIds.filter((id) => !!id))];
  if (unique.length === 0) return { enqueued: 0, skipped: 0 };

  const db = admin.firestore();
  let enqueued = 0;
  let skipped = 0;

  for (const group of chunk(unique, CHUNK_SIZE)) {
    const refs = group.map((id) => db.doc(`summaries/${id}`));
    const snaps = await db.getAll(...refs);
    const batch = db.batch();
    let writes = 0;
    snaps.forEach((snap, i) => {
      if (snap.exists) {
        skipped += 1;
        return;
      }
      const doc: SummaryDocument = {
        videoId: group[i],
        status: "queued",
        model: "free",
        webhookSecret: "",
        requestedAt: admin.firestore.FieldValue.serverTimestamp(),
      };
      batch.set(refs[i], doc);
      writes += 1;
    });
    if (writes > 0) {
      await batch.commit();
      enqueued += writes;
    }
  }

  logger.info("enqueueAutoSummary", {
    total: unique.length,
    enqueued,
    skipped,
  });
  return { enqueued, skipped };
}

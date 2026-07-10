import * as admin from "firebase-admin";
import * as logger from "firebase-functions/logger";
import type { SummaryDocument } from "../models/index.js";
import { DISPATCHER_BATCH_SIZE } from "./constants.js";

export interface AutoEnqueueResult {
  enqueued: number;
  skipped: number;
}

const CHUNK_SIZE = DISPATCHER_BATCH_SIZE;

class AlreadyExistsError extends Error {
  constructor() {
    super("ALREADY_EXISTS");
    this.name = "AlreadyExistsError";
  }
}

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
 * not create duplicates because each doc is written with a conditional create
 * inside a per-doc transaction (ALREADY_EXISTS errors are swallowed).
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
    // Use per-doc transactions with conditional create to avoid the
    // check-then-act race between getAll() + batch.set().
    await Promise.all(
      group.map(async (id) => {
        const ref = db.doc(`summaries/${id}`);
        const doc: SummaryDocument = {
          videoId: id,
          status: "queued",
          model: "free",
          requestedAt: admin.firestore.FieldValue.serverTimestamp(),
        };
        try {
          await db.runTransaction(async (tx) => {
            const snap = await tx.get(ref);
            if (snap.exists) {
              // Signal skip via a typed error caught below.
              throw new AlreadyExistsError();
            }
            tx.set(ref, doc);
          });
          enqueued += 1;
        } catch (err: unknown) {
          if (err instanceof AlreadyExistsError) {
            skipped += 1;
          } else {
            // Re-throw genuine Firestore errors.
            throw err;
          }
        }
      }),
    );
  }

  logger.info("enqueueAutoSummary", {
    total: unique.length,
    enqueued,
    skipped,
  });
  return { enqueued, skipped };
}

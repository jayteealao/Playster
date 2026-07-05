import * as admin from "firebase-admin";
import * as logger from "firebase-functions/logger";
import { HttpsError } from "firebase-functions/v2/https";
import { allowlistedCall } from "../auth/verify.js";
import { fetchTranscript } from "./fetch.js";

export { fetchTranscript } from "./fetch.js";
export { transcriptBackfillCron } from "./backfill.js";

/**
 * Manual operator trigger: fetch and store one video's transcript.
 * Allowlisted operator only. Returns the resulting pointer-doc status.
 */
export const invokeTranscriptFetch = allowlistedCall<
  { videoId: string },
  { status: string }
>(
  { memory: "512MiB", timeoutSeconds: 120 },
  async (req) => {
    const { videoId } = req.data;
    if (!videoId || typeof videoId !== "string") {
      throw new HttpsError("invalid-argument", "Missing videoId");
    }
    logger.info("invokeTranscriptFetch: starting", { videoId });
    await fetchTranscript(videoId);
    const doc = await admin.firestore().doc(`transcripts/${videoId}`).get();
    const status = (doc.data()?.status as string | undefined) ?? "unknown";
    logger.info("invokeTranscriptFetch: complete", { videoId, status });
    return { status };
  },
);

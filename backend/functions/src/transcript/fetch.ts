import * as admin from "firebase-admin";
import * as logger from "firebase-functions/logger";
import { getInnertubeClient } from "../auth/innertube.js";
import type { TranscriptDocument, TranscriptSegment } from "../models/index.js";

/**
 * GCS path convention: transcripts/{videoId}.txt
 */
function gcsPath(videoId: string): string {
  return `transcripts/${videoId}.txt`;
}

/**
 * Returns the bucket name from env. Throws at call-time (not module load)
 * if the env var is absent so errors surface in logs with a clear message
 * rather than a silent undefined-bucket failure.
 */
function getBucketName(): string {
  const name = process.env.TRANSCRIPT_BUCKET;
  if (!name) {
    throw new Error(
      "TRANSCRIPT_BUCKET env var is not set. " +
        "Set it in .env.playster-406121 to the confirmed GCS bucket name.",
    );
  }
  return name;
}

/**
 * Builds the plain-text blob: one line per segment, prefixed by start time.
 * Format: "<start_seconds> <text>\n"
 * Human-readable for the operator smoke test; LLM-ready for the re-summarize
 * slice (plain text, no escaping overhead vs. JSON).
 */
function segmentsToText(segments: TranscriptSegment[]): string {
  return segments
    .map((s) => `${s.start.toFixed(2)} ${s.text}`)
    .join("\n");
}

/**
 * Fetches the caption transcript for a YouTube video, stores it as a GCS blob,
 * and writes a Firestore pointer doc at `transcripts/{videoId}` with the
 * resulting status.
 *
 * Three outcome paths:
 *   - `available`: captions found, blob written, signed URL generated.
 *   - `unavailable`: no caption track (terminal — not retried).
 *   - `transient`: transient fetch or write failure (eligible for retry).
 *
 * Idempotent: if the pointer doc already has `status: available`, returns early
 * without re-fetching or re-writing. The `transient` status is NOT guarded —
 * a retry should attempt the fetch again.
 */
export async function fetchTranscript(videoId: string): Promise<void> {
  const db = admin.firestore();
  const pointerRef = db.doc(`transcripts/${videoId}`);
  const now = admin.firestore.FieldValue.serverTimestamp();

  // Idempotency guard: skip if already available.
  const existing = await pointerRef.get();
  if (existing.exists && existing.data()?.status === "available") {
    logger.info("fetchTranscript: already available, skipping", { videoId });
    return;
  }

  logger.info("fetchTranscript: starting", { videoId });

  const createdAt = existing.exists
    ? (existing.data()?.createdAt ?? now)
    : now;

  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  let transcriptInfoRaw: any;
  try {
    const innertube = await getInnertubeClient();
    const videoInfo = await innertube.getInfo(videoId);
    transcriptInfoRaw = await videoInfo.getTranscript();
  } catch (fetchErr) {
    const code =
      fetchErr instanceof Error ? fetchErr.message : String(fetchErr);
    logger.warn("fetchTranscript: fetch failed (transient)", {
      videoId,
      error: code,
    });
    const transientDoc: TranscriptDocument = {
      videoId,
      status: "transient",
      errorCode: code.slice(0, 200),
      createdAt,
      updatedAt: now,
    };
    await pointerRef.set(transientDoc);
    return;
  }

  // Check for missing caption content — `unavailable` is terminal.
  const segmentList: Array<unknown> | undefined | null =
    transcriptInfoRaw?.transcript?.content?.body?.initial_segments;
  if (!segmentList || segmentList.length === 0) {
    logger.info("fetchTranscript: no caption track (unavailable)", { videoId });
    const unavailableDoc: TranscriptDocument = {
      videoId,
      status: "unavailable",
      createdAt,
      updatedAt: now,
    };
    await pointerRef.set(unavailableDoc);
    return;
  }

  // Filter TranscriptSectionHeader nodes (which have no start_ms) by
  // duck-typing: only nodes with a string `start_ms` field are segments.
  const language: string = transcriptInfoRaw.selectedLanguage ?? "unknown";
  const segments: TranscriptSegment[] = (
    segmentList as Array<Record<string, unknown>>
  )
    .filter(
      (s): s is { start_ms: string; snippet: { text: string } } =>
        typeof s["start_ms"] === "string" &&
        s["snippet"] != null &&
        typeof (s["snippet"] as Record<string, unknown>)["text"] === "string",
    )
    .map((s) => ({
      start: parseInt(s.start_ms, 10) / 1000,
      text: s.snippet.text,
    }));

  const blobText = segmentsToText(segments);
  const path = gcsPath(videoId);
  const bucketName = getBucketName();
  const bucket = admin.storage().bucket(bucketName);
  const file = bucket.file(path);

  // GCS write must succeed before the pointer doc is written as `available`.
  // On any GCS error, fall through to the transient path.
  try {
    await file.save(blobText, { contentType: "text/plain; charset=utf-8" });
  } catch (gcsErr) {
    const code = gcsErr instanceof Error ? gcsErr.message : String(gcsErr);
    logger.warn("fetchTranscript: GCS write failed (transient)", {
      videoId,
      error: code,
    });
    const transientDoc: TranscriptDocument = {
      videoId,
      status: "transient",
      errorCode: `GCS_WRITE: ${code}`.slice(0, 200),
      createdAt,
      updatedAt: now,
    };
    await pointerRef.set(transientDoc);
    return;
  }

  // Generate a signed URL with the 7-day maximum GCS lifetime.
  // Signed-URL refresh is deferred; signedUrlExpiresAt enables either approach.
  // sdlc-debt: 7-day fixed expiry; upgrade path = callable re-signs on demand,
  //   or a cron job re-signs all URLs approaching expiry nightly.
  const expiresMs = Date.now() + 7 * 24 * 60 * 60 * 1000;
  let signedUrl: string;
  try {
    const [url] = await file.getSignedUrl({
      action: "read",
      expires: expiresMs,
    });
    signedUrl = url;
  } catch (signErr) {
    const code = signErr instanceof Error ? signErr.message : String(signErr);
    logger.warn("fetchTranscript: signed URL generation failed (transient)", {
      videoId,
      error: code,
    });
    const transientDoc: TranscriptDocument = {
      videoId,
      status: "transient",
      errorCode: `SIGN_URL: ${code}`.slice(0, 200),
      createdAt,
      updatedAt: now,
    };
    await pointerRef.set(transientDoc);
    return;
  }

  // All writes succeeded — write the `available` pointer doc.
  const availableDoc: TranscriptDocument = {
    videoId,
    status: "available",
    source: "youtubei",
    language,
    segments,
    gcsPath: path,
    signedUrl,
    signedUrlExpiresAt: new Date(expiresMs),
    createdAt,
    updatedAt: now,
  };
  await pointerRef.set(availableDoc);

  logger.info("fetchTranscript: complete", {
    videoId,
    language,
    segmentCount: segments.length,
    gcsPath: path,
  });
}

import * as admin from "firebase-admin";
import * as logger from "firebase-functions/logger";
import { HttpsError } from "firebase-functions/v2/https";
import { allowlistedCall } from "../auth/verify.js";
import type { SummaryDocument, TranscriptDocument, TranscriptSegment } from "../models/index.js";
import { SUMMARIZER_API_KEY, summarizerSecrets } from "./secrets.js";
import { MIN_SUMMARY_CONTENT_CHARS } from "./constants.js";

/**
 * OpenRouter model string for the free tier.
 * Matches the model alias "free" used by the daemon dispatch path.
 */
const FREE_TIER_MODEL = "meta-llama/llama-3.1-8b-instruct:free";

/**
 * Statuses that indicate a summary job is currently in-flight or terminal-done.
 * Re-summarization skips these to avoid racing a concurrent daemon job.
 * Mirrors NON_REDISPATCHABLE_STATUSES from dispatch.ts.
 */
const NON_RESUMMARISABLE_STATUSES: ReadonlyArray<SummaryDocument["status"]> = [
  "pending",
  "running",
  "completed",
];

interface ResumarizeOptions {
  /** Injectable fetch for testing. Defaults to global fetch. */
  fetchImpl?: typeof fetch;
}

/**
 * Converts a TranscriptSegment array to the same plain-text format as the GCS blob:
 * "<start_seconds> <text>\n" per segment.
 */
function segmentsToText(segments: TranscriptSegment[]): string {
  return segments.map((s) => `${s.start.toFixed(2)} ${s.text}`).join("\n");
}

/**
 * Re-summarizes a video from its stored transcript, calling OpenRouter's
 * chat-completions API directly and writing the result to `summaries/{videoId}`.
 *
 * Three outcome paths:
 *   - no-op: pointer doc absent or not `available` (AC guard 2).
 *   - no-op: summary doc is already in-flight / completed (in-flight guard).
 *   - success: summary doc written at `status: completed` with `content` from LLM.
 *   - failure: summary doc written at `status: failed-transient` on HTTP/timeout error.
 */
export async function resummarizeFromTranscript(
  videoId: string,
  opts: ResumarizeOptions = {},
): Promise<void> {
  if (!videoId || typeof videoId !== "string") {
    throw new HttpsError("invalid-argument", "videoId is required.");
  }

  const db = admin.firestore();
  const pointerRef = db.doc(`transcripts/${videoId}`);
  const summaryRef = db.doc(`summaries/${videoId}`);

  // --- Guard 1: pointer doc must exist and be `available` ---
  const pointerSnap = await pointerRef.get();
  if (!pointerSnap.exists) {
    logger.info("resummarizeFromTranscript: no pointer doc — skipping", { videoId });
    return;
  }
  const pointerData = pointerSnap.data() as TranscriptDocument;
  if (pointerData.status !== "available") {
    logger.info("resummarizeFromTranscript: transcript not available — skipping", {
      videoId,
      transcriptStatus: pointerData.status,
    });
    return;
  }

  // --- Guard 2: in-flight / completed summary check ---
  const summarySnap = await summaryRef.get();
  if (summarySnap.exists) {
    const summaryData = summarySnap.data() as Partial<SummaryDocument>;
    if (summaryData.status && NON_RESUMMARISABLE_STATUSES.includes(summaryData.status)) {
      logger.info("resummarizeFromTranscript: summary in-flight or completed — skipping", {
        videoId,
        summaryStatus: summaryData.status,
      });
      return;
    }
  }

  // --- Read transcript text: prefer GCS blob, fall back to segments array ---
  let transcriptText: string;
  const gcsPath = pointerData.gcsPath;
  if (gcsPath) {
    const bucketName = process.env.TRANSCRIPT_BUCKET;
    if (!bucketName) {
      throw new Error(
        "TRANSCRIPT_BUCKET env var is not set. " +
          "Set it in .env.playster-406121 to the confirmed GCS bucket name.",
      );
    }
    try {
      const [buffer] = await admin.storage().bucket(bucketName).file(gcsPath).download();
      transcriptText = buffer.toString("utf-8");
    } catch (gcsErr) {
      logger.warn("resummarizeFromTranscript: GCS download failed, falling back to segments", {
        videoId,
        error: gcsErr instanceof Error ? gcsErr.message : String(gcsErr),
      });
      // Fallback to Firestore segments array.
      if (!pointerData.segments || pointerData.segments.length === 0) {
        logger.warn("resummarizeFromTranscript: no segments in pointer doc — skipping", { videoId });
        return;
      }
      transcriptText = segmentsToText(pointerData.segments);
    }
  } else {
    // No gcsPath on the pointer doc — fall back to segments.
    if (!pointerData.segments || pointerData.segments.length === 0) {
      logger.warn("resummarizeFromTranscript: no gcsPath and no segments — skipping", { videoId });
      return;
    }
    transcriptText = segmentsToText(pointerData.segments);
  }

  // --- Call OpenRouter chat-completions API directly ---
  const doFetch = opts.fetchImpl ?? fetch;
  const apiKey = SUMMARIZER_API_KEY.value();

  const requestBody = {
    model: FREE_TIER_MODEL,
    messages: [
      {
        role: "user",
        content: `Summarize the following YouTube transcript:\n\n${transcriptText}`,
      },
    ],
  };

  const ctrl = new AbortController();
  // sdlc-debt: hard-coded 30s timeout; upgrade path = make configurable via env or options param.
  const abortTimer = setTimeout(() => ctrl.abort(), 30_000);

  let response: Response;
  try {
    response = await doFetch("https://openrouter.ai/api/v1/chat/completions", {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        Authorization: `Bearer ${apiKey}`,
      },
      body: JSON.stringify(requestBody),
      signal: ctrl.signal,
    });
  } catch (err) {
    clearTimeout(abortTimer);
    const message = err instanceof Error ? err.message : String(err);
    logger.warn("resummarizeFromTranscript: OpenRouter call failed", { videoId, message });
    await summaryRef.set(
      { status: "failed-transient", errorCode: "resummarize_network", errorMessage: message },
      { merge: true },
    );
    return;
  }
  clearTimeout(abortTimer);

  if (!response.ok) {
    const errBody = await response.text().catch(() => "");
    logger.warn("resummarizeFromTranscript: OpenRouter returned non-2xx", {
      videoId,
      httpStatus: response.status,
    });
    await summaryRef.set(
      {
        status: "failed-transient",
        errorCode: "resummarize_http",
        errorMessage: `openrouter ${response.status}: ${errBody.slice(0, 256)}`,
      },
      { merge: true },
    );
    return;
  }

  // --- Extract content from response ---
  let content: string | undefined;
  try {
    const parsed = (await response.json()) as {
      choices?: Array<{ message?: { content?: unknown } }>;
    };
    const raw = parsed.choices?.[0]?.message?.content;
    if (typeof raw === "string") {
      content = raw.trim();
    }
  } catch {
    // Unparseable body treated as failure below.
  }

  if (!content || content.length < MIN_SUMMARY_CONTENT_CHARS) {
    logger.warn("resummarizeFromTranscript: summary too short or missing", {
      videoId,
      contentLength: content?.length ?? 0,
    });
    await summaryRef.set(
      {
        status: "failed-transient",
        errorCode: "summary_too_short",
        errorMessage: `content length ${content?.length ?? 0} < ${MIN_SUMMARY_CONTENT_CHARS}`,
      },
      { merge: true },
    );
    return;
  }

  // --- Write completed summary doc ---
  const now = admin.firestore.FieldValue.serverTimestamp();
  await summaryRef.set(
    {
      videoId,
      status: "completed",
      content,
      model: "free",
      completedAt: now,
    },
    { merge: true },
  );

  logger.info("resummarizeFromTranscript: complete", { videoId, contentLength: content.length });
}

interface ResummarizeInput {
  videoId: string;
}

interface ResummarizeOutput {
  status: string;
}

export const resummarizeVideoSummary = allowlistedCall<ResummarizeInput, ResummarizeOutput>(
  {
    memory: "256MiB",
    timeoutSeconds: 120,
    secrets: summarizerSecrets,
  },
  async (req) => {
    const videoId = req.data?.videoId;
    if (!videoId) {
      throw new HttpsError("invalid-argument", "videoId is required.");
    }
    logger.info("resummarizeVideoSummary", { videoId });
    await resummarizeFromTranscript(videoId);
    return { status: "ok" };
  },
);

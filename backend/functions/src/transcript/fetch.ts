import * as admin from "firebase-admin";
import * as logger from "firebase-functions/logger";
import { getInnertubeClient } from "../auth/innertube.js";
import type {
  TranscriptDocument,
  TranscriptErrorClass,
  TranscriptSegment,
} from "../models/index.js";
import {
  PANEL_NOT_FOUND_TERMINAL_COUNT,
  TIMEDTEXT_FETCH_TIMEOUT_MS,
} from "./constants.js";

// ---------------------------------------------------------------------------
// Sentinel error classes
// ---------------------------------------------------------------------------

/**
 * Thrown by fetchViaAndroidTimedtext when no caption track URL is available
 * from the ANDROID-client response. Ensures classifyError sees PANEL_NOT_FOUND
 * rather than falling through to UNKNOWN.
 */
class PanelNotFoundError extends Error {
  constructor() {
    super("Transcript panel not found");
    this.name = "PanelNotFoundError";
  }
}

/**
 * Thrown by fetchViaAndroidTimedtext when the timedtext response body is empty
 * after trimming. Exported so the test suite can construct instances directly
 * for the EMPTY_TIMEDTEXT classification-table test.
 */
export class EmptyTimedtextError extends Error {
  constructor() {
    super("timedtext response body is empty");
    this.name = "EmptyTimedtextError";
  }
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

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
  return segments.map((s) => `${s.start.toFixed(2)} ${s.text}`).join("\n");
}

// ---------------------------------------------------------------------------
// Error classification
// ---------------------------------------------------------------------------

interface TranscriptClassification {
  status: "transient" | "unavailable";
  errorClass: TranscriptErrorClass;
  fallbackEligible: boolean;
  httpStatus?: number;
}

const CLASSIFICATION_TABLE: ReadonlyArray<{
  readonly test: (err: unknown) => boolean;
  readonly status: "transient" | "unavailable";
  readonly errorClass: TranscriptErrorClass;
  readonly fallbackEligible: boolean;
  readonly extractHttpStatus?: (err: unknown) => number | undefined;
}> = [
  {
    test: (err) =>
      err instanceof Error &&
      /transcript panel not found|no transcript panel/i.test(err.message),
    status: "transient",
    errorClass: "PANEL_NOT_FOUND",
    fallbackEligible: false,
  },
  {
    test: (err) =>
      err instanceof Error &&
      /sign in|login|login_required|this video is private/i.test(err.message),
    status: "unavailable",
    errorClass: "LOGIN_REQUIRED",
    fallbackEligible: false,
  },
  {
    test: (err) =>
      err instanceof Error && /status 4\d\d|4xx/i.test(err.message),
    status: "transient",
    errorClass: "INNERTUBE_4XX",
    fallbackEligible: true,
    extractHttpStatus: (err) => {
      if (!(err instanceof Error)) return undefined;
      const m = err.message.match(/status (\d{3})/);
      return m ? parseInt(m[1], 10) : undefined;
    },
  },
  {
    test: (err) => err instanceof EmptyTimedtextError,
    status: "transient",
    errorClass: "EMPTY_TIMEDTEXT",
    fallbackEligible: false,
  },
  {
    test: (err) => err instanceof SyntaxError,
    status: "transient",
    errorClass: "PARSE_FAILURE",
    fallbackEligible: false,
  },
  {
    // Default — must be last.
    test: () => true,
    status: "transient",
    errorClass: "UNKNOWN",
    fallbackEligible: false,
  },
];

/**
 * Classifies a caught error into a stable taxonomy entry.
 * Exported as a test seam — AC4 calls this directly against each table entry.
 */
export function classifyError(err: unknown): TranscriptClassification {
  // The last entry (UNKNOWN) always matches, so find() never returns undefined.
  const entry = CLASSIFICATION_TABLE.find((e) => e.test(err))!;
  return {
    status: entry.status,
    errorClass: entry.errorClass,
    fallbackEligible: entry.fallbackEligible,
    httpStatus: entry.extractHttpStatus?.(err),
  };
}

// ---------------------------------------------------------------------------
// ANDROID-client fallback helpers
// ---------------------------------------------------------------------------

/**
 * Parses a json3 timedtext response body into transcript segments.
 * Pure — no side effects, no I/O. Filters out auto-formatting noise events
 * (events with no segs array) and empty-text segments.
 */
function parseJson3Segments(body: string): TranscriptSegment[] {
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  const root: any = JSON.parse(body);
  const events: unknown[] = Array.isArray(root?.events) ? root.events : [];
  return events
    .filter(
      (e): e is { tStartMs: number; segs: Array<{ utf8: string }> } =>
        typeof (e as Record<string, unknown>)["tStartMs"] === "number" &&
        Array.isArray((e as Record<string, unknown>)["segs"]),
    )
    .map((e) => ({
      start: e.tStartMs / 1000,
      text: e.segs
        .map((s) => s.utf8)
        .join("")
        .trim(),
    }))
    .filter((s) => s.text.length > 0);
}

/** Duck-typed shape of a single caption track from getBasicInfo().captions.caption_tracks. */
interface CaptionTrack {
  language_code: string;
  base_url: string;
  is_auto_generated?: boolean;
}

/**
 * Selects the best-matching caption track URL from an ANDROID-client caption_tracks array.
 * Priority chain: exact language-code match → prefix match → first auto-generated → first track.
 * Returns null when the array is empty (caller treats this as PANEL_NOT_FOUND).
 */
function selectCaptionTrackUrl(
  tracks: CaptionTrack[],
  preferredLang: string,
): string | null {
  if (tracks.length === 0) return null;
  const exact = tracks.find((t) => t.language_code === preferredLang);
  if (exact) return exact.base_url;
  const prefix = tracks.find((t) => t.language_code.startsWith(preferredLang));
  if (prefix) return prefix.base_url;
  const autoGen = tracks.find((t) => t.is_auto_generated);
  if (autoGen) return autoGen.base_url;
  return tracks[0].base_url;
}

/**
 * ANDROID-client caption fallback.
 * Calls getBasicInfo() with the ANDROID client, selects a caption track, and
 * fetches the json3 timedtext directly. Returns segments on success.
 * Throws PanelNotFoundError (no tracks), EmptyTimedtextError (empty 200 body),
 * or a raw Error for HTTP errors — all are picked up by classifyError().
 */
async function fetchViaAndroidTimedtext(
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  innertube: any,
  videoId: string,
  preferredLang: string,
): Promise<TranscriptSegment[]> {
  const info = await innertube.getBasicInfo(videoId, { client: "ANDROID" });
  const tracks: CaptionTrack[] = info?.captions?.caption_tracks ?? [];
  const trackUrl = selectCaptionTrackUrl(tracks, preferredLang);
  if (!trackUrl) throw new PanelNotFoundError();

  const url = `${trackUrl}&fmt=json3`;
  const resp = await fetch(url, {
    signal: AbortSignal.timeout(TIMEDTEXT_FETCH_TIMEOUT_MS),
  });
  if (!resp.ok) {
    throw new Error(`timedtext GET failed with status ${resp.status}`);
  }
  const body = await resp.text();
  if (!body.trim()) throw new EmptyTimedtextError();
  return parseJson3Segments(body);
}

// ---------------------------------------------------------------------------
// Main export
// ---------------------------------------------------------------------------

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
 * When the primary path (youtubei.js getTranscript) fails with a
 * fallback-eligible error (INNERTUBE_4XX), the ANDROID-client timedtext
 * fallback is attempted. The GCS blob format is byte-identical regardless of
 * which path ran; only the `source` field on the pointer doc differs
 * ("android-timedtext" vs "youtubei").
 *
 * Panel-not-found errors increment a counter on the pointer doc. After
 * PANEL_NOT_FOUND_TERMINAL_COUNT consecutive hits the doc flips to terminal
 * `unavailable` and the backfill cron skips it.
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

  const createdAt = existing.exists ? (existing.data()?.createdAt ?? now) : now;

  // -- Primary path --
  let segments: TranscriptSegment[] | null = null;
  let source: "youtubei" | "android-timedtext" = "youtubei";
  let language = "unknown";
  let classification: TranscriptClassification | null = null;
  let lastErr: unknown = null;
  let fallbackEngaged = false;

  try {
    const innertube = await getInnertubeClient();
    const videoInfo = await innertube.getInfo(videoId);
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    const transcriptInfoRaw: any = await videoInfo.getTranscript();

    // Check for missing caption content — terminal without triggering fallback.
    const segmentList: Array<unknown> | undefined | null =
      transcriptInfoRaw?.transcript?.content?.body?.initial_segments;
    if (!segmentList || segmentList.length === 0) {
      logger.info("fetchTranscript: no caption track (unavailable)", {
        videoId,
      });
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
    language = transcriptInfoRaw.selectedLanguage ?? "unknown";
    segments = (segmentList as Array<Record<string, unknown>>)
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
  } catch (fetchErr) {
    lastErr = fetchErr;
    classification = classifyError(fetchErr);
  }

  // -- Fallback path (only when primary fails with a fallback-eligible error) --
  if (classification?.fallbackEligible) {
    fallbackEngaged = true;
    try {
      const innertube = await getInnertubeClient();
      segments = await fetchViaAndroidTimedtext(innertube, videoId, language);
      source = "android-timedtext";
      classification = null; // fallback succeeded — clear the error state
    } catch (fallbackErr) {
      lastErr = fallbackErr;
      classification = classifyError(fallbackErr); // fallback error overrides primary
    }
  }

  // -- Handle failed primary + failed/absent fallback --
  if (classification !== null) {
    const rawMessage =
      lastErr instanceof Error ? lastErr.message : String(lastErr);
    const existingCount: number =
      (existing.data()?.panelNotFoundCount as number | undefined) ?? 0;

    let status: "transient" | "unavailable";
    let panelNotFoundCount: number;

    if (classification.errorClass === "PANEL_NOT_FOUND") {
      panelNotFoundCount = existingCount + 1;
      status =
        panelNotFoundCount >= PANEL_NOT_FOUND_TERMINAL_COUNT
          ? "unavailable"
          : "transient";
    } else {
      status = classification.status;
      panelNotFoundCount = 0; // explicit counter reset on any other error
    }

    logger.warn("fetchTranscript: fetch failed", {
      videoId,
      errorClass: classification.errorClass,
      ...(classification.httpStatus !== undefined
        ? { httpStatus: classification.httpStatus }
        : {}),
      fallbackEngaged,
      ...(classification.errorClass === "PANEL_NOT_FOUND"
        ? { panelNotFoundCount, terminal: status === "unavailable" }
        : {}),
      error: rawMessage.slice(0, 200),
    });

    const errorDoc: TranscriptDocument = {
      videoId,
      status,
      errorCode: rawMessage.slice(0, 200),
      errorClass: classification.errorClass,
      panelNotFoundCount,
      createdAt,
      updatedAt: now,
    };
    await pointerRef.set(errorDoc);
    return;
  }

  // -- Happy path: segments is non-null --
  const blobText = segmentsToText(segments!);
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
    source,
    language,
    segments: segments!,
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
    segmentCount: segments!.length,
    gcsPath: path,
    source,
  });
}

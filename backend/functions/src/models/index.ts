import { FieldValue } from "firebase-admin/firestore";

export interface PlaylistDocument {
  title: string;
  description: string;
  thumbnailUrl: string;
  videoCount: number;
  publishedAt: string;
  channelTitle: string;
  privacyStatus: string;
  source: "api" | "innertube";
  lastSyncedAt: FieldValue | Date;
}

export interface WatchLaterSyncState {
  next_continuation_token: string | null;
  pages_completed: number;
  last_position: number;
  total_items: number;
  complete: boolean;
  last_run_at: FieldValue | Date;
}

export interface VideoDocument {
  videoId: string;
  title: string;
  channelTitle: string;
  channelId: string;
  duration: string;
  thumbnailUrl: string;
  publishedAt: string;
  viewCount: number;
  position: number;
  addedAt: string;
}

export type SummaryStatus =
  | "queued"
  | "pending"
  | "running"
  | "completed"
  | "failed-transient"
  | "failed-permanent";

/**
 * One chapter entry parsed from the summarizer's "Key moments" section.
 * Canonical units are seconds — clients format display strings themselves.
 */
export interface SummaryChapter {
  /** Chapter start time in seconds. */
  t: number;
  label: string;
  /**
   * Chapter duration in seconds (`next.t - t`). `null` for the final chapter
   * when no transcript is available to bound it.
   */
  dur: number | null;
}

export interface SummaryDocument {
  videoId: string;
  status: SummaryStatus;
  /** The model used for summarization. Optional so that legacy queued docs
   *  written before this field was introduced are still tolerated on read.
   *  Matches Kotlin `SummaryDoc.model: String?`. */
  model?: string;
  /** @deprecated webhookSecret has moved to webhook_secrets/{videoId}. No longer written here. */
  webhookSecret?: string;
  summarizerJobId?: string;
  content?: string;
  errorCode?: string;
  errorMessage?: string;
  /**
   * Number of times this doc has been downgraded to failed-transient due to a
   * too-short summary (errorCode "summary_too_short"). Incremented on each
   * degraded-extraction webhook before the status write. When this reaches
   * MAX_DEGRADED_ATTEMPTS the status is promoted to failed-permanent instead,
   * removing the video from the retry queue permanently.
   */
  degradedAttempts?: number;
  /**
   * Structured chapters parsed from the summarizer's "Key moments" section.
   * Absent on summaries produced before chapters landed and on summaries
   * whose output carried no parseable section — consumers must treat the
   * field as optional. When present, `content` holds the prose with the
   * Key moments section stripped (the same information lives here, losslessly).
   */
  chapters?: SummaryChapter[];
  requestedAt: FieldValue | Date;
  dispatchedAt?: FieldValue | Date;
  completedAt?: FieldValue | Date;
}

/**
 * Server-only document stored at `webhook_secrets/{videoId}`.
 * Never readable by the Android client (denied in firestore.rules).
 * Admin SDK bypasses rules and can access it freely.
 */
export interface WebhookSecretDocument {
  secret: string;
  createdAt: FieldValue | Date;
}

export interface QuotaDocument {
  date: string;
  requestCount: number;
  dailyLimit: number;
  perMinuteLimit: number;
  recentTimestamps: number[];
  updatedAt: FieldValue | Date;
}

export type TranscriptStatus =
  | "pending"
  | "available"
  | "transient"
  | "unavailable";

/**
 * Stable taxonomy code written to transient/unavailable pointer docs and log
 * lines. Consumed by the production-watch Cloud Logging query. Additive —
 * existing consumers tolerate unknown fields via structural typing.
 */
export type TranscriptErrorClass =
  | "PANEL_NOT_FOUND"
  | "LOGIN_REQUIRED"
  | "INNERTUBE_4XX"
  | "EMPTY_TIMEDTEXT"
  | "PARSE_FAILURE"
  | "UNKNOWN";

export interface TranscriptSegment {
  /** Segment start time in seconds (float). */
  start: number;
  text: string;
}

/**
 * Per-user reading/watching progress at `users/{uid}/progress/{docId}`.
 * One collection holds both data kinds behind the `kind` discriminator:
 *
 * - `kind: "video"` — doc id is the videoId. Tracks playback position.
 * - `kind: "playlist"` — doc id is the playlistId. Tracks last-opened time
 *   (the Home shelf's ordering key).
 *
 * Deterministic doc ids make writes idempotent upserts — no duplicate-doc
 * races between devices; last write wins.
 */
export interface ProgressDocument {
  kind: "video" | "playlist";
  /** Present when kind == "video". */
  videoId?: string;
  /** Present on both kinds: the playlist context of the progress. */
  playlistId?: string;
  /** Playback position in seconds (kind == "video"). */
  positionSeconds?: number;
  /** Full video duration in seconds (kind == "video"). */
  durationSeconds?: number;
  /** Last time the playlist was opened (kind == "playlist"). */
  lastOpenedAt?: FieldValue | Date;
  updatedAt: FieldValue | Date;
}

/**
 * A timestamped user note at `users/{uid}/notes/{autoId}`, anchored to a
 * moment in a video. Queried by videoId (player/transcript margin notes,
 * ordered by `t`) and by playlistId (playlist Notes tab, newest first).
 */
export interface NoteDocument {
  videoId: string;
  playlistId: string;
  /** Anchor time in the video, seconds. */
  t: number;
  /** Note body. Rules cap this at 5000 chars. */
  text: string;
  createdAt: FieldValue | Date;
  updatedAt: FieldValue | Date;
}

/**
 * A saved transcript highlight at `users/{uid}/highlights/{autoId}`.
 * References a transcript segment by its start time; queried by videoId
 * ordered by `segmentStart` so the transcript view can merge highlights
 * into its paragraph stream in one pass.
 */
export interface HighlightDocument {
  videoId: string;
  /** Start time (seconds) of the highlighted transcript segment. */
  segmentStart: number;
  /** The highlighted text, denormalized for list rendering. */
  text: string;
  createdAt: FieldValue | Date;
}

export interface TranscriptDocument {
  videoId: string;
  status: TranscriptStatus;
  source?: "youtubei" | "shortDescription" | "android-timedtext";
  language?: string;
  segments?: TranscriptSegment[];
  gcsPath?: string;
  signedUrl?: string;
  signedUrlExpiresAt?: FieldValue | Date;
  errorCode?: string;
  /** Stable classification code written on transient/unavailable outcomes. */
  errorClass?: TranscriptErrorClass;
  /**
   * Incremented on consecutive PANEL_NOT_FOUND outcomes; persists across cron
   * ticks via the pointer doc. When it reaches PANEL_NOT_FOUND_TERMINAL_COUNT
   * the status flips to terminal `unavailable`.
   */
  panelNotFoundCount?: number;
  createdAt: FieldValue | Date;
  updatedAt: FieldValue | Date;
}

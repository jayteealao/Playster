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

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
  model: string;
  webhookSecret: string;
  summarizerJobId?: string;
  content?: string;
  errorCode?: string;
  errorMessage?: string;
  requestedAt: FieldValue | Date;
  dispatchedAt?: FieldValue | Date;
  completedAt?: FieldValue | Date;
}

export interface QuotaDocument {
  date: string;
  requestCount: number;
  dailyLimit: number;
  perMinuteLimit: number;
  recentTimestamps: number[];
  updatedAt: FieldValue | Date;
}

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

import * as admin from "firebase-admin";
import type { PlaylistDocument, VideoDocument } from "../models/index.js";

const WATCH_LATER_ID = "WL";
const MAX_BATCH_SIZE = 500;

/** Minimal shape of a playlist video item from youtubei.js */
interface InnertubeVideoItem {
  id: string;
  title?: { toString(): string };
  author?: { name: string; id: string };
  duration?: { text: string; seconds: number };
  thumbnails?: Array<{ url: string }>;
}

/**
 * Sync the Watch Later playlist via the InnerTube private API.
 * Uses `youtubei.js` with cookie-based authentication.
 */
export async function syncWatchLater(): Promise<{ videoCount: number }> {
  const { getInnertubeClient } = await import("../auth/index.js");
  const innertube = await getInnertubeClient();

  // Fetch the library to access the Watch Later section
  const library = await innertube.getLibrary();
  const watchLaterSection = library.watch_later;

  if (!watchLaterSection) {
    throw new Error("Watch Later section not found in library.");
  }

  // Get the full Watch Later playlist via getAll()
  const watchLaterPlaylist = await watchLaterSection.getAll();

  // Extract playlist metadata if available (Playlist type has `info`)
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  const playlistAny = watchLaterPlaylist as any;
  const playlistInfo = playlistAny.info ?? null;
  const rawItems: InnertubeVideoItem[] = playlistAny.items ?? [];

  // Collect all items including continuations
  const allItems: InnertubeVideoItem[] = [...rawItems];
  let current = playlistAny;

  while (current.has_continuation) {
    current = await current.getContinuation();
    if (current.items) {
      allItems.push(...(current.items as InnertubeVideoItem[]));
    }
  }

  const db = admin.firestore();
  const playlistRef = db.collection("playlists").doc(WATCH_LATER_ID);

  const playlistDoc: PlaylistDocument = {
    title: playlistInfo?.title ?? "Watch Later",
    description: playlistInfo?.description ?? "",
    thumbnailUrl: playlistInfo?.thumbnails?.[0]?.url ?? "",
    videoCount: allItems.length,
    publishedAt: "",
    channelTitle: playlistInfo?.author?.name ?? "",
    privacyStatus: playlistInfo?.privacy ?? "private",
    source: "innertube",
    lastSyncedAt: admin.firestore.FieldValue.serverTimestamp(),
  };

  let batch = db.batch();
  let opCount = 0;

  batch.set(playlistRef, playlistDoc, { merge: true });
  opCount++;

  for (let i = 0; i < allItems.length; i++) {
    const video = allItems[i];
    const videoId = video.id;
    if (!videoId) continue;

    const videoDoc: VideoDocument = {
      videoId,
      title: video.title?.toString() ?? "",
      channelTitle: video.author?.name ?? "",
      channelId: video.author?.id ?? "",
      duration: video.duration?.text ?? "",
      thumbnailUrl: video.thumbnails?.[0]?.url ?? "",
      publishedAt: "",
      viewCount: 0,
      position: i,
      addedAt: "",
    };

    const videoRef = playlistRef.collection("videos").doc(videoId);
    batch.set(videoRef, videoDoc, { merge: true });
    opCount++;

    if (opCount >= MAX_BATCH_SIZE) {
      await batch.commit();
      batch = db.batch();
      opCount = 0;
    }
  }

  if (opCount > 0) {
    await batch.commit();
  }

  return { videoCount: allItems.length };
}

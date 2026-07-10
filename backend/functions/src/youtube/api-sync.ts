import { google, type Auth, youtube_v3 } from "googleapis";
import * as admin from "firebase-admin";
import type { PlaylistDocument, VideoDocument } from "../models/index.js";

const MAX_BATCH_SIZE = 500;

/**
 * Fetch all playlists owned by the authenticated user, handling pagination.
 */
export async function fetchAllPlaylists(
  auth: Auth.OAuth2Client,
): Promise<youtube_v3.Schema$Playlist[]> {
  const youtube = google.youtube({ version: "v3", auth });
  const playlists: youtube_v3.Schema$Playlist[] = [];
  let pageToken: string | undefined;

  do {
    const res = await youtube.playlists.list({
      part: ["snippet", "contentDetails", "status"],
      mine: true,
      maxResults: 50,
      pageToken,
    });

    if (res.data.items) {
      playlists.push(...res.data.items);
    }
    pageToken = res.data.nextPageToken ?? undefined;
  } while (pageToken);

  return playlists;
}

/**
 * Fetch all videos in a playlist, handling pagination (max 50 per page).
 */
export async function fetchPlaylistVideos(
  auth: Auth.OAuth2Client,
  playlistId: string,
): Promise<youtube_v3.Schema$PlaylistItem[]> {
  const youtube = google.youtube({ version: "v3", auth });
  const items: youtube_v3.Schema$PlaylistItem[] = [];
  let pageToken: string | undefined;

  do {
    const res = await youtube.playlistItems.list({
      part: ["snippet", "contentDetails"],
      playlistId,
      maxResults: 50,
      pageToken,
    });

    if (res.data.items) {
      items.push(...res.data.items);
    }
    pageToken = res.data.nextPageToken ?? undefined;
  } while (pageToken);

  return items;
}

/**
 * Commit a Firestore write batch, respecting the 500-operation limit.
 * Returns a fresh batch for continued use.
 */
async function commitBatch(
  db: FirebaseFirestore.Firestore,
  batch: FirebaseFirestore.WriteBatch,
  opCount: number,
): Promise<{ batch: FirebaseFirestore.WriteBatch; opCount: number }> {
  if (opCount > 0) {
    await batch.commit();
  }
  return { batch: db.batch(), opCount: 0 };
}

/**
 * Orchestrate a full sync of all regular (API-accessible) playlists to Firestore.
 */
export async function syncRegularPlaylists(): Promise<{
  playlistCount: number;
  videoCount: number;
  videoIds: string[];
}> {
  const { getAuthenticatedClient } = await import("../auth/index.js");
  const auth = await getAuthenticatedClient();
  const playlists = await fetchAllPlaylists(auth);
  const db = admin.firestore();

  let totalVideos = 0;
  const videoIds: string[] = [];
  let batch = db.batch();
  let opCount = 0;

  for (const playlist of playlists) {
    const playlistId = playlist.id!;
    const snippet = playlist.snippet!;
    const contentDetails = playlist.contentDetails!;
    const status = playlist.status!;

    const playlistDoc: PlaylistDocument = {
      title: snippet.title ?? "",
      description: snippet.description ?? "",
      thumbnailUrl: snippet.thumbnails?.high?.url ?? "",
      videoCount: contentDetails.itemCount ?? 0,
      publishedAt: snippet.publishedAt ?? "",
      channelTitle: snippet.channelTitle ?? "",
      privacyStatus: status.privacyStatus ?? "",
      source: "api",
      lastSyncedAt: admin.firestore.FieldValue.serverTimestamp(),
    };

    const playlistRef = db.collection("playlists").doc(playlistId);
    batch.set(playlistRef, playlistDoc, { merge: true });
    opCount++;

    if (opCount >= MAX_BATCH_SIZE) {
      ({ batch, opCount } = await commitBatch(db, batch, opCount));
    }

    // Fetch and write videos for this playlist
    const videos = await fetchPlaylistVideos(auth, playlistId);

    for (const item of videos) {
      const itemSnippet = item.snippet!;
      const videoId =
        item.contentDetails?.videoId ?? itemSnippet.resourceId?.videoId ?? "";
      if (!videoId) continue;

      const videoDoc: VideoDocument = {
        videoId,
        title: itemSnippet.title ?? "",
        channelTitle: itemSnippet.channelTitle ?? "",
        channelId: itemSnippet.videoOwnerChannelId ?? "",
        duration: "", // Not available from playlistItems endpoint
        thumbnailUrl: itemSnippet.thumbnails?.high?.url ?? "",
        publishedAt: itemSnippet.publishedAt ?? "",
        viewCount: 0, // Not available from playlistItems endpoint
        position: itemSnippet.position ?? 0,
        addedAt: itemSnippet.publishedAt ?? "",
      };

      const videoRef = playlistRef.collection("videos").doc(videoId);
      batch.set(videoRef, videoDoc, { merge: true });
      opCount++;
      totalVideos++;
      videoIds.push(videoId);

      if (opCount >= MAX_BATCH_SIZE) {
        ({ batch, opCount } = await commitBatch(db, batch, opCount));
      }
    }
  }

  // Commit any remaining operations
  if (opCount > 0) {
    await batch.commit();
  }

  return {
    playlistCount: playlists.length,
    videoCount: totalVideos,
    videoIds,
  };
}

/**
 * Sync a single playlist by ID to Firestore.
 */
export async function syncPlaylistById(playlistId: string): Promise<{
  videoCount: number;
  videoIds: string[];
}> {
  const { getAuthenticatedClient } = await import("../auth/index.js");
  const auth = await getAuthenticatedClient();
  const youtube = google.youtube({ version: "v3", auth });
  const db = admin.firestore();

  // Fetch the playlist metadata
  const playlistRes = await youtube.playlists.list({
    part: ["snippet", "contentDetails", "status"],
    id: [playlistId],
  });

  const playlist = playlistRes.data.items?.[0];
  if (!playlist) {
    throw new Error(`Playlist ${playlistId} not found.`);
  }

  const snippet = playlist.snippet!;
  const contentDetails = playlist.contentDetails!;
  const status = playlist.status!;

  const playlistDoc: PlaylistDocument = {
    title: snippet.title ?? "",
    description: snippet.description ?? "",
    thumbnailUrl: snippet.thumbnails?.high?.url ?? "",
    videoCount: contentDetails.itemCount ?? 0,
    publishedAt: snippet.publishedAt ?? "",
    channelTitle: snippet.channelTitle ?? "",
    privacyStatus: status.privacyStatus ?? "",
    source: "api",
    lastSyncedAt: admin.firestore.FieldValue.serverTimestamp(),
  };

  const playlistRef = db.collection("playlists").doc(playlistId);

  let batch = db.batch();
  let opCount = 0;

  batch.set(playlistRef, playlistDoc, { merge: true });
  opCount++;

  const videos = await fetchPlaylistVideos(auth, playlistId);
  const videoIds: string[] = [];

  for (const item of videos) {
    const itemSnippet = item.snippet!;
    const videoId =
      item.contentDetails?.videoId ?? itemSnippet.resourceId?.videoId ?? "";
    if (!videoId) continue;

    const videoDoc: VideoDocument = {
      videoId,
      title: itemSnippet.title ?? "",
      channelTitle: itemSnippet.channelTitle ?? "",
      channelId: itemSnippet.videoOwnerChannelId ?? "",
      duration: "",
      thumbnailUrl: itemSnippet.thumbnails?.high?.url ?? "",
      publishedAt: itemSnippet.publishedAt ?? "",
      viewCount: 0,
      position: itemSnippet.position ?? 0,
      addedAt: itemSnippet.publishedAt ?? "",
    };

    const videoRef = playlistRef.collection("videos").doc(videoId);
    batch.set(videoRef, videoDoc, { merge: true });
    opCount++;
    videoIds.push(videoId);

    if (opCount >= MAX_BATCH_SIZE) {
      await batch.commit();
      batch = db.batch();
      opCount = 0;
    }
  }

  if (opCount > 0) {
    await batch.commit();
  }

  return { videoCount: videos.length, videoIds };
}

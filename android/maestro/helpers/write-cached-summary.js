// Seed playlists/PL_TEST_1 + videos/VID_CACHED + summaries/VID_CACHED at
// status=completed. Slice-local cached-navigation flow.
const admin = require("firebase-admin");

if (!process.env.FIRESTORE_EMULATOR_HOST) {
  process.env.FIRESTORE_EMULATOR_HOST = "127.0.0.1:8080";
}
admin.initializeApp({ projectId: "playster-dev" });
const db = admin.firestore();

async function main() {
  await db.collection("playlists").doc("PL_TEST_1").set({
    id: "PL_TEST_1",
    playlistId: "PL_TEST_1",
    title: "Test Playlist",
    channelTitle: "Test Channel",
    videoCount: 1,
    thumbnailUrl: "",
  });
  await db
    .collection("playlists")
    .doc("PL_TEST_1")
    .collection("videos")
    .doc("VID_CACHED")
    .set({
      videoId: "VID_CACHED",
      playlistId: "PL_TEST_1",
      title: "Cached-summary fixture",
      channelTitle: "Test Channel",
      channelId: "UC_TEST",
      duration: "PT5M",
      thumbnailUrl: "",
      publishedAt: "2026-01-01T00:00:00Z",
      viewCount: 100,
      position: 0,
      addedAt: "2026-01-01T00:00:00Z",
    });
  await db.collection("summaries").doc("VID_CACHED").set({
    videoId: "VID_CACHED",
    status: "completed",
    model: "free",
    webhookSecret: "fixture-secret",
    content:
      "# Cached summary\n\nThis is the cached fixture body used by the " +
      "cached-summary-navigation slice-local flow.",
    requestedAt: admin.firestore.FieldValue.serverTimestamp(),
    completedAt: admin.firestore.FieldValue.serverTimestamp(),
  });
  console.log("seed-cached-summary OK");
}

main().catch((err) => {
  console.error(err);
  process.exit(1);
});

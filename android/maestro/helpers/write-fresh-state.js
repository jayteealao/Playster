// Seed playlists/PL_TEST_1 + videos/VID_NO_SUMMARY. No summaries/ doc, no
// quota doc — keeps the AC-5 fresh-state flow deterministic.
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
    .doc("VID_NO_SUMMARY")
    .set({
      videoId: "VID_NO_SUMMARY",
      playlistId: "PL_TEST_1",
      title: "Fixture video for AC-5",
      channelTitle: "Test Channel",
      channelId: "UC_TEST",
      duration: "PT5M",
      thumbnailUrl: "",
      publishedAt: "2026-01-01T00:00:00Z",
      viewCount: 100,
      position: 0,
      addedAt: "2026-01-01T00:00:00Z",
    });
  console.log("seed-fresh-state OK");
}

main().catch((err) => {
  console.error(err);
  process.exit(1);
});

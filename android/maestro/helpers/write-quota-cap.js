// Seed quota/openrouter at requestCount === dailyLimit so the QuotaBanner is
// visible and all Summarize CTAs disable. AC-10 fixture.
const admin = require("firebase-admin");

if (!process.env.FIRESTORE_EMULATOR_HOST) {
  process.env.FIRESTORE_EMULATOR_HOST = "127.0.0.1:8080";
}
admin.initializeApp({ projectId: "playster-dev" });
const db = admin.firestore();

const today = new Date().toISOString().slice(0, 10);

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
    .doc("VID_ANY")
    .set({
      videoId: "VID_ANY",
      playlistId: "PL_TEST_1",
      title: "Fixture video for AC-10",
      channelTitle: "Test Channel",
      channelId: "UC_TEST",
      duration: "PT5M",
      thumbnailUrl: "",
      publishedAt: "2026-01-01T00:00:00Z",
      viewCount: 100,
      position: 0,
      addedAt: "2026-01-01T00:00:00Z",
    });
  await db.collection("quota").doc("openrouter").set({
    date: today,
    requestCount: 1000,
    dailyLimit: 1000,
    perMinuteLimit: 20,
    recentTimestamps: [],
    updatedAt: admin.firestore.FieldValue.serverTimestamp(),
  });
  console.log("seed-quota-exhausted OK");
}

main().catch((err) => {
  console.error(err);
  process.exit(1);
});

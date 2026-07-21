// Seed quota/openrouter at requestCount === dailyLimit so the re-skinned quota
// notice appears on the Playlist Summary tab. Seeds ONLY the quota doc — the
// playlists/videos/summary it renders against come from write-editorial-corpus.js
// (run seed-editorial-corpus.sh first). Writes under the app's OWN project id
// (playster-406121) so the Android Firestore SDK actually reads it back — the
// legacy playster-dev target was unreadable by the app.
const path = require("node:path");
const admin = require(
  path.join(
    __dirname,
    "..",
    "..",
    "..",
    "backend",
    "functions",
    "node_modules",
    "firebase-admin",
  ),
);

const PROJECT_ID = process.env.FIRESTORE_PROJECT_ID || "playster-406121";

if (!process.env.FIRESTORE_EMULATOR_HOST) {
  process.env.FIRESTORE_EMULATOR_HOST =
    process.env.FIRESTORE_EMULATOR || "127.0.0.1:8080";
}

admin.initializeApp({ projectId: PROJECT_ID });
const db = admin.firestore();

const today = new Date().toISOString().slice(0, 10);

async function main() {
  await db.collection("quota").doc("openrouter").set({
    date: today,
    requestCount: 1000,
    dailyLimit: 1000,
    perMinuteLimit: 20,
    recentTimestamps: [],
    updatedAt: admin.firestore.FieldValue.serverTimestamp(),
  });
  console.log(`seed-quota-exhausted OK: project=${PROJECT_ID} quota/openrouter exhausted`);
}

main().catch((err) => {
  console.error(err);
  process.exit(1);
});

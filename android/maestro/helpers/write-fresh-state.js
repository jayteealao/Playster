#!/usr/bin/env node
// Construct the manual-summary FRESH state against the RUNNING emulator
// suite: the editorial corpus stays in place (seed-fresh-state.sh runs
// seed-editorial-corpus.sh first); this script only DELETES the featured
// episode's summaries/ doc and the quota doc, so the Player's Summary tab
// renders the summarize affordance and the dispatch is quota-clear.
//
// This mirrors seed-cached-summary.sh's re-pointed pattern (running suite,
// app project id, explicit firebase-admin resolution). The legacy version
// spawned its own throwaway emulator via `emulators:exec` (colliding with a
// running suite), targeted `playster-dev` (unreadable by the app), and wrote
// `id`/`playlistId` body fields — the @DocumentId collision class the corpus
// script documents against (it crashed the app on launch). None of that
// state is written anymore; fresh state is now constructed by deletion.
//
// PROJECT ID: the Android Firestore SDK addresses documents under the app's
// OWN project id (from google-services.json) even against the emulator —
// override with FIRESTORE_PROJECT_ID only if the app config changes.
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
const fixtures = require(
  path.join(__dirname, "..", "fixtures", "editorial-fixtures.json"),
);

const PROJECT_ID = process.env.FIRESTORE_PROJECT_ID || "playster-406121";

if (!process.env.FIRESTORE_EMULATOR_HOST) {
  process.env.FIRESTORE_EMULATOR_HOST =
    process.env.FIRESTORE_EMULATOR || "127.0.0.1:8080";
}

admin.initializeApp({ projectId: PROJECT_ID });
const db = admin.firestore();

// The summary doc is keyed by the featured episode's videoId VALUE — the
// curated real id when one is mapped (fixtures.videos.realIds), the
// synthetic id otherwise. Single source of truth: the fixtures manifest.
const featured = fixtures.videos.featured;
const featuredVideoId = (fixtures.videos.realIds || {})[featured] || featured;

async function main() {
  await db.doc(`summaries/${featuredVideoId}`).delete();
  await db.doc("quota/openrouter").delete();
  console.log(
    `write-fresh-state OK: project=${PROJECT_ID} ` +
      `deleted summaries/${featuredVideoId} + quota/openrouter (fresh state)`,
  );
}

main().catch((err) => {
  console.error(err);
  process.exit(1);
});

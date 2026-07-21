#!/usr/bin/env node
// Create the fixture verification user in the local Firebase Auth emulator
// with the uid PINNED to the operator allowlist literal from
// backend/firestore.rules. Seeded interactive flows then exercise the
// PRODUCTION security rules byte-for-byte — a random-uid fixture user (the
// old REST signUp path) is denied by the owner+allowlist gates the moment a
// flow touches Firestore.
//
// Requires a running Auth emulator (default 127.0.0.1:9099; override with
// FIREBASE_AUTH_EMULATOR_HOST or AUTH_EMULATOR).
//
// Project id: key-based (client SDK) requests resolve to the emulator
// suite's DEFAULT project — probed empirically against the Auth emulator —
// so the account must live under the project the suite was started with.
// That default is playster-406121 (backend/.firebaserc), which is also the
// project the app's Firestore SDK addresses — one project id everywhere.
// Override with AUTH_PROJECT_ID only if the suite is started with an
// explicit different --project.
const fs = require("node:fs");
const path = require("node:path");
// firebase-admin lives in backend/functions's node_modules; resolve it
// explicitly so this helper runs from any cwd (the repo root installs none).
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

const EMAIL = "verify@playster.test";
const PASSWORD = "playster-verify-fixture";
const PROJECT_ID = process.env.AUTH_PROJECT_ID || "playster-406121";

if (!process.env.FIREBASE_AUTH_EMULATOR_HOST) {
  process.env.FIREBASE_AUTH_EMULATOR_HOST =
    process.env.AUTH_EMULATOR || "127.0.0.1:9099";
}

/**
 * Read the operator allowlist uid from the committed rules file — the same
 * literal backend/scripts/check-allowlist-uid.sh guards. Never hardcoded
 * here: if the operator rotates the uid, this helper follows automatically.
 */
function allowlistUid() {
  const rulesPath = path.join(
    __dirname,
    "..",
    "..",
    "..",
    "backend",
    "firestore.rules",
  );
  const rules = fs.readFileSync(rulesPath, "utf8");
  const match = rules.match(/request\.auth\.uid == "([^"]+)"/);
  if (!match) {
    throw new Error(`no allowlist uid literal found in ${rulesPath}`);
  }
  return match[1];
}

async function main() {
  const uid = allowlistUid();
  admin.initializeApp({ projectId: PROJECT_ID });
  const auth = admin.auth();

  const existing = await auth.getUserByEmail(EMAIL).catch(() => null);
  if (existing && existing.uid === uid) {
    console.log(
      `create-fixture-user: ${EMAIL} already pinned to the allowlist uid - nothing to do.`,
    );
    return;
  }
  if (existing) {
    // Legacy fixture from the old REST signUp path (random uid): replace it.
    await auth.deleteUser(existing.uid);
    console.log(
      `create-fixture-user: removed legacy fixture user (uid ${existing.uid}).`,
    );
  }
  await auth.createUser({
    uid,
    email: EMAIL,
    password: PASSWORD,
    emailVerified: true,
  });
  console.log(
    `create-fixture-user: created ${EMAIL} pinned to allowlist uid ${uid} (project ${PROJECT_ID}).`,
  );
}

main().catch((err) => {
  console.error(err);
  process.exit(1);
});

// One-time setup: run the YouTube TV device-code OAuth flow and save the
// resulting credentials so the deployed Firebase Function can read Watch Later.
//
// Usage (from backend/functions/):
//   node scripts/setup-tv-oauth.mjs
//
// The script writes credentials to .tmp/tv-oauth-credentials.json and prints a
// ready-to-run curl command targeting the setTvOauthCredentials endpoint. The
// endpoint stores them in Firestore at tokens/innertube-oauth, which is where
// the Function loads them from at runtime.

import { Innertube, UniversalCache } from "youtubei.js";
import * as path from "node:path";
import * as fs from "node:fs/promises";
import { fileURLToPath } from "node:url";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const CACHE_DIR = path.resolve(__dirname, "..", ".tmp", "innertube-oauth");
const CREDS_PATH = path.resolve(__dirname, "..", ".tmp", "tv-oauth-credentials.json");

async function main() {
  await fs.mkdir(CACHE_DIR, { recursive: true });

  const innertube = await Innertube.create({
    cache: new UniversalCache(true, CACHE_DIR),
  });

  innertube.session.on("auth-pending", (data) => {
    console.log("\n  Open: " + data.verification_url);
    console.log("  Code: " + data.user_code);
    console.log("\n  Sign in with the Google account whose Watch Later you want to sync.");
    console.log("  Waiting for you to approve...\n");
  });

  innertube.session.on("auth-error", (err) => {
    console.error("\nAuth error: " + (err?.message ?? err));
  });

  await innertube.session.signIn();
  await innertube.session.oauth.cacheCredentials();

  // Read what just got written by cacheCredentials.
  const credsFile = path.join(CACHE_DIR, "youtubei_oauth_credentials");
  const raw = await fs.readFile(credsFile, "utf-8");
  const creds = JSON.parse(raw);

  await fs.writeFile(CREDS_PATH, JSON.stringify(creds, null, 2));
  console.log("\nCredentials saved to: " + CREDS_PATH);

  console.log("\nUpload to Firestore by POSTing to your setTvOauthCredentials endpoint:");
  console.log("");
  console.log("  curl -X POST \\");
  console.log("    -H \"Content-Type: application/json\" \\");
  console.log("    -d @" + CREDS_PATH + " \\");
  console.log("    https://settvoauthcredentials-<HASH>-uc.a.run.app");
  console.log("");
  console.log("Find the exact URL by running:");
  console.log("  firebase functions:list --project playster-406121");
  console.log("");
  console.log("Or, if you have firebase-admin ADC configured locally:");
  console.log("  node scripts/upload-tv-oauth.mjs");
}

main().catch((err) => {
  console.error("Fatal: " + (err?.message ?? err));
  process.exit(1);
});

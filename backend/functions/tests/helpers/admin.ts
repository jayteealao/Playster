import * as admin from "firebase-admin";

const PROJECT_ID = "demo-playster";
const EMULATOR_HOST = process.env.FIRESTORE_EMULATOR_HOST ?? "127.0.0.1:8080";

let initialized = false;

/**
 * Point the firebase-admin SDK at the Firestore emulator. Idempotent — repeat
 * calls reuse the existing app. Tests that need the admin SDK must call this
 * before importing modules that touch `admin.firestore()`.
 */
export function initAdminEmulator(): void {
  process.env.FIRESTORE_EMULATOR_HOST = EMULATOR_HOST;
  process.env.GCLOUD_PROJECT = PROJECT_ID;
  process.env.GOOGLE_CLOUD_PROJECT = PROJECT_ID;
  if (!initialized && admin.apps.length === 0) {
    admin.initializeApp({ projectId: PROJECT_ID });
    initialized = true;
  }
}

/**
 * Wipe all Firestore data in the emulator. Uses the emulator's REST endpoint
 * rather than walking collections, which is both faster and reaches docs in
 * non-default databases.
 */
export async function clearFirestore(): Promise<void> {
  const url = `http://${EMULATOR_HOST}/emulator/v1/projects/${PROJECT_ID}/databases/(default)/documents`;
  const res = await fetch(url, { method: "DELETE" });
  if (!res.ok) {
    throw new Error(`clearFirestore failed: ${res.status}`);
  }
}

/**
 * Returns true if the Firestore emulator answers. Used by tests to skip
 * gracefully when the emulator isn't running (e.g. `pnpm test` without
 * `firebase emulators:exec`).
 */
export async function emulatorReachable(): Promise<boolean> {
  try {
    const res = await fetch(
      `http://${EMULATOR_HOST}/emulator/v1/projects/${PROJECT_ID}/databases/(default)/documents`,
      { method: "GET" },
    );
    return res.ok || res.status === 404;
  } catch {
    return false;
  }
}

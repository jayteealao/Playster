import * as fs from "node:fs";
import * as path from "node:path";
import {
  initializeTestEnvironment,
  type RulesTestEnvironment,
} from "@firebase/rules-unit-testing";

/**
 * In rules-unit-testing we substitute a stable test uid for the deploy-time
 * placeholder, so rule tests can authenticate as the "allowlisted" user
 * without touching the source rules file.
 */
export const ALLOWLISTED_UID = "ALLOWLISTED_UID_FOR_TESTS";
export const STRANGER_UID = "stranger-uid";

let testEnv: RulesTestEnvironment | null = null;

/**
 * Resolve the Firestore emulator address. Honors FIRESTORE_EMULATOR_HOST
 * (`host:port`) so suites can target an alternate port when 8080 is taken;
 * falls back to the firebase.json default.
 */
export function firestoreEmulatorAddress(): { host: string; port: number } {
  const fromEnv = process.env.FIRESTORE_EMULATOR_HOST;
  if (fromEnv) {
    const [host, rawPort] = fromEnv.split(":");
    const port = Number(rawPort);
    if (host && Number.isInteger(port) && port > 0) {
      return { host, port };
    }
  }
  return { host: "127.0.0.1", port: 8080 };
}

export async function getTestEnv(): Promise<RulesTestEnvironment> {
  if (testEnv) return testEnv;

  const rulesPath = path.resolve(__dirname, "..", "..", "firestore.rules");
  const rules = fs
    .readFileSync(rulesPath, "utf8")
    .replaceAll("__BOOTSTRAP_UID__", ALLOWLISTED_UID)
    .replaceAll("XLGNnIxqCwSckmrErKIdWftK9Vg2", ALLOWLISTED_UID);

  const { host, port } = firestoreEmulatorAddress();
  testEnv = await initializeTestEnvironment({
    projectId: "playster-rules-test",
    firestore: {
      rules,
      host,
      port,
    },
  });

  return testEnv;
}

export async function teardownTestEnv(): Promise<void> {
  if (testEnv) {
    await testEnv.cleanup();
    testEnv = null;
  }
}

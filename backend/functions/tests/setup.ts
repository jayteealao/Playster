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

export async function getTestEnv(): Promise<RulesTestEnvironment> {
  if (testEnv) return testEnv;

  const rulesPath = path.resolve(__dirname, "..", "..", "firestore.rules");
  const rules = fs
    .readFileSync(rulesPath, "utf8")
    .replace("__BOOTSTRAP_UID__", ALLOWLISTED_UID);

  testEnv = await initializeTestEnvironment({
    projectId: "playster-rules-test",
    firestore: {
      rules,
      host: "127.0.0.1",
      port: 8080,
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

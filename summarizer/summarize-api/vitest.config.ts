import { defineConfig } from "vitest/config";

export default defineConfig({
  test: {
    globals: true,
    testTimeout: 15_000,
    hookTimeout: 15_000,
    // The DB singleton in src/db/index.ts is keyed on process.env.DB_PATH,
    // and buildApp() in tests/setup.ts mutates that env var per call. Running
    // test files in parallel would race on which DB the singleton points at —
    // benign for tests that mock dispatchJob, but fatal for e2e-url-job which
    // exercises the real DB through the runner.
    fileParallelism: false,
  },
});

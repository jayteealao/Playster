import { describe, it, expect, beforeAll, afterAll } from "vitest";
import {
  buildApp,
  startSummarizeDaemon,
  TEST_API_KEY,
  type TestContext,
  type SummarizeDaemonHandle,
} from "./setup.js";

// Proves `options.timestamps` survives route validation (the zod options
// schema strips unknown keys — a missing schema entry silently drops the
// flag) and reaches the daemon body via DAEMON_FORWARD_KEYS.

async function waitFor(
  predicate: () => boolean,
  timeoutMs: number,
): Promise<void> {
  const deadline = Date.now() + timeoutMs;
  while (!predicate()) {
    if (Date.now() > deadline) throw new Error("waitFor timeout");
    await new Promise((r) => setTimeout(r, 25));
  }
}

describe("options.timestamps forwarding", () => {
  let ctx: TestContext;
  let daemon: SummarizeDaemonHandle;

  beforeAll(async () => {
    daemon = await startSummarizeDaemon();
    ctx = await buildApp({ daemonUrl: daemon.url });
  });

  afterAll(async () => {
    await ctx.cleanup();
    await new Promise<void>((r) => daemon.server.close(() => r()));
  });

  it("accepts timestamps at validation and forwards it to the daemon body", async () => {
    const createRes = await ctx.app.inject({
      method: "POST",
      url: "/v1/jobs",
      headers: {
        "x-api-key": TEST_API_KEY,
        "content-type": "application/json",
      },
      payload: JSON.stringify({
        url: "https://www.youtube.com/watch?v=fixture",
        options: { model: "free", format: "markdown", timestamps: true },
      }),
    });
    expect(createRes.statusCode).toBe(201);

    await waitFor(() => daemon.capturedBodies.length >= 1, 5000);

    const daemonBody = JSON.parse(daemon.capturedBodies[0]) as {
      url: string;
      timestamps?: unknown;
    };
    expect(daemonBody.url).toBe("https://www.youtube.com/watch?v=fixture");
    expect(daemonBody.timestamps).toBe(true);
  });

  it("omits timestamps from the daemon body when the client did not send it", async () => {
    const before = daemon.capturedBodies.length;
    const createRes = await ctx.app.inject({
      method: "POST",
      url: "/v1/jobs",
      headers: {
        "x-api-key": TEST_API_KEY,
        "content-type": "application/json",
      },
      payload: JSON.stringify({
        url: "https://example.com",
        options: { model: "free" },
      }),
    });
    expect(createRes.statusCode).toBe(201);

    await waitFor(() => daemon.capturedBodies.length >= before + 1, 5000);

    const daemonBody = JSON.parse(daemon.capturedBodies[before]) as {
      timestamps?: unknown;
    };
    expect(daemonBody.timestamps).toBeUndefined();
  });
});

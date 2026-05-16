import { describe, it, expect, afterAll, beforeAll, vi } from "vitest";
import { buildApp, type TestContext } from "./setup.js";

// Mock dispatchJob
vi.mock("../src/runners/index.js", () => ({
  dispatchJob: vi.fn(),
}));

describe("Rate limiting", () => {
  let ctx: TestContext;

  beforeAll(async () => {
    ctx = await buildApp({
      rateLimitMax: 3,
      rateLimitWindow: "1 minute",
    });
  });

  afterAll(async () => {
    await ctx.cleanup();
  });

  it("returns 429 after exceeding rate limit", async () => {
    const statuses: number[] = [];

    // Send requests up to and beyond the limit
    for (let i = 0; i < 5; i++) {
      const res = await ctx.app.inject({
        method: "GET",
        url: "/health",
      });
      statuses.push(res.statusCode);
    }

    // At least one should be 429
    expect(statuses).toContain(429);
  });
});

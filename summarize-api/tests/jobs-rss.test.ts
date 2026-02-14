import { describe, it, expect, afterAll, beforeAll, vi } from "vitest";
import { buildApp, TEST_API_KEY, type TestContext } from "./setup.js";

// Mock dispatchJob so it doesn't run async work
vi.mock("../src/runners/index.js", () => ({
  dispatchJob: vi.fn(),
}));

// Mock validateUrl to allow test URLs through
vi.mock("../src/security/ssrf.js", async () => {
  const actual = await vi.importActual<typeof import("../src/security/ssrf.js")>("../src/security/ssrf.js");
  return {
    ...actual,
    validateUrl: vi.fn(async () => ({ safe: true })),
  };
});

describe("RSS job endpoints", () => {
  let ctx: TestContext;

  beforeAll(async () => {
    ctx = await buildApp();
  });

  afterAll(async () => {
    await ctx.cleanup();
  });

  function post(payload: unknown) {
    return ctx.app.inject({
      method: "POST",
      url: "/v1/jobs",
      headers: { "x-api-key": TEST_API_KEY, "content-type": "application/json" },
      payload: JSON.stringify(payload),
    });
  }

  it("POST /v1/jobs with rss field returns 201", async () => {
    const res = await post({ rss: "https://example.com/feed.xml" });
    expect(res.statusCode).toBe(201);
    const body = res.json();
    expect(body.ok).toBe(true);
    expect(body.id).toBeDefined();
  });

  it("POST /v1/jobs with invalid rss URL returns 400", async () => {
    const res = await post({ rss: "not-a-url" });
    expect(res.statusCode).toBe(400);
  });

  it("POST /v1/jobs with rss and item option returns 201", async () => {
    const res = await post({
      rss: "https://example.com/feed.xml",
      item: "latest",
      options: { format: "markdown" },
    });
    expect(res.statusCode).toBe(201);
  });
});

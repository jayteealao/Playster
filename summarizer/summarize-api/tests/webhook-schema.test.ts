import { describe, it, expect, beforeEach, afterEach } from "vitest";
import { buildApp, TEST_API_KEY, type TestContext } from "./setup.js";

describe("POST /v1/jobs — webhook field validation", () => {
  let ctx: TestContext;

  beforeEach(async () => {
    ctx = await buildApp({ daemonUrl: "http://127.0.0.1:1" });
  });

  afterEach(async () => {
    await ctx.cleanup();
  });

  it("accepts a URL job with full webhook fields", async () => {
    const res = await ctx.app.inject({
      method: "POST",
      url: "/v1/jobs",
      headers: {
        "x-api-key": TEST_API_KEY,
        "content-type": "application/json",
      },
      payload: JSON.stringify({
        url: "https://example.com",
        webhook_url: "https://example.test/webhook",
        webhook_secret: "sixteen-byte-test-secret",
        client_job_id: "abc-123",
      }),
    });
    expect(res.statusCode).toBe(201);
  });

  it("400s when webhook_url is set without webhook_secret", async () => {
    const res = await ctx.app.inject({
      method: "POST",
      url: "/v1/jobs",
      headers: {
        "x-api-key": TEST_API_KEY,
        "content-type": "application/json",
      },
      payload: JSON.stringify({
        url: "https://example.com",
        webhook_url: "https://example.test/webhook",
      }),
    });
    expect(res.statusCode).toBe(400);
    expect(res.json()).toMatchObject({
      error: expect.stringMatching(/webhook_secret/),
    });
  });

  it("400s when webhook_url is not a URL", async () => {
    const res = await ctx.app.inject({
      method: "POST",
      url: "/v1/jobs",
      headers: {
        "x-api-key": TEST_API_KEY,
        "content-type": "application/json",
      },
      payload: JSON.stringify({
        url: "https://example.com",
        webhook_url: "not-a-url",
        webhook_secret: "sixteen-byte-test-secret",
      }),
    });
    expect(res.statusCode).toBe(400);
  });

  it("400s when webhook_secret is shorter than 16 chars", async () => {
    const res = await ctx.app.inject({
      method: "POST",
      url: "/v1/jobs",
      headers: {
        "x-api-key": TEST_API_KEY,
        "content-type": "application/json",
      },
      payload: JSON.stringify({
        url: "https://example.com",
        webhook_url: "https://example.test/webhook",
        webhook_secret: "short",
      }),
    });
    expect(res.statusCode).toBe(400);
  });

  it("accepts a URL job without any webhook fields (back-compat)", async () => {
    const res = await ctx.app.inject({
      method: "POST",
      url: "/v1/jobs",
      headers: {
        "x-api-key": TEST_API_KEY,
        "content-type": "application/json",
      },
      payload: JSON.stringify({ url: "https://example.com" }),
    });
    expect(res.statusCode).toBe(201);
  });
});

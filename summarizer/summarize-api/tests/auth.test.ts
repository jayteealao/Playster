import { describe, it, expect, afterAll, beforeAll } from "vitest";
import { buildApp, TEST_API_KEY, startMockDaemon, type TestContext } from "./setup.js";
import type { Server } from "node:http";

describe("Auth middleware", () => {
  let ctx: TestContext;
  let daemon: { url: string; server: Server };

  beforeAll(async () => {
    daemon = await startMockDaemon();
    ctx = await buildApp({ daemonUrl: daemon.url });
  });

  afterAll(async () => {
    await ctx.cleanup();
    daemon.server.close();
  });

  it("returns 401 without X-API-Key header", async () => {
    const res = await ctx.app.inject({
      method: "POST",
      url: "/v1/jobs",
      payload: { url: "https://example.com" },
    });
    expect(res.statusCode).toBe(401);
    expect(res.json().error).toBe("Unauthorized");
  });

  it("returns 401 with invalid X-API-Key", async () => {
    const res = await ctx.app.inject({
      method: "POST",
      url: "/v1/jobs",
      headers: { "x-api-key": "wrong-key" },
      payload: { url: "https://example.com" },
    });
    expect(res.statusCode).toBe(401);
    expect(res.json().error).toBe("Unauthorized");
  });

  it("allows request with valid X-API-Key", async () => {
    const res = await ctx.app.inject({
      method: "POST",
      url: "/v1/jobs",
      headers: { "x-api-key": TEST_API_KEY, "content-type": "application/json" },
      payload: JSON.stringify({ url: "https://example.com" }),
    });
    // Should not be 401 — may be 201 or 403 (SSRF) depending on DNS, but not auth error
    expect(res.statusCode).not.toBe(401);
  });

  it("/health skips auth", async () => {
    const res = await ctx.app.inject({
      method: "GET",
      url: "/health",
      // No API key header
    });
    expect(res.statusCode).not.toBe(401);
  });
});

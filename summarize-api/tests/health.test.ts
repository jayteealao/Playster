import { describe, it, expect, afterAll, beforeAll } from "vitest";
import { buildApp, startMockDaemon, type TestContext } from "./setup.js";
import type { Server } from "node:http";

describe("GET /health", () => {
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

  it("returns 200 when daemon is reachable", async () => {
    const res = await ctx.app.inject({ method: "GET", url: "/health" });
    expect(res.statusCode).toBe(200);
    const body = res.json();
    expect(body.status).toBe("ok");
    expect(body.daemon).toBe("reachable");
    expect(body.timestamp).toBeDefined();
  });

  it("returns 503 when daemon is unreachable", async () => {
    const ctx2 = await buildApp({ daemonUrl: "http://127.0.0.1:1" });
    const res = await ctx2.app.inject({ method: "GET", url: "/health" });
    expect(res.statusCode).toBe(503);
    const body = res.json();
    expect(body.status).toBe("degraded");
    expect(body.daemon).toBe("unreachable");
    await ctx2.cleanup();
  });

  it("includes a status field in the response", async () => {
    const res = await ctx.app.inject({ method: "GET", url: "/health" });
    const body = res.json();
    expect(body).toHaveProperty("status");
  });
});

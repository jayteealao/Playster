import { describe, it, expect, afterAll, beforeAll, vi } from "vitest";
import { buildApp, TEST_API_KEY, startMockDaemon, type TestContext } from "./setup.js";
import type { Server } from "node:http";

// Mock dispatchJob so it doesn't actually run async work
vi.mock("../src/runners/index.js", () => ({
  dispatchJob: vi.fn(),
}));

// Mock validateUrl to allow our test URLs through
vi.mock("../src/security/ssrf.js", async () => {
  const actual = await vi.importActual<typeof import("../src/security/ssrf.js")>("../src/security/ssrf.js");
  return {
    ...actual,
    validateUrl: vi.fn(async (url: string) => {
      // Allow example.com, block private IPs
      if (url.includes("127.0.0.1") || url.includes("10.0.0.1")) {
        return { safe: false, error: "Blocked" };
      }
      return { safe: true };
    }),
  };
});

describe("URL job endpoints", () => {
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

  function post(url: string, payload: unknown) {
    return ctx.app.inject({
      method: "POST",
      url,
      headers: { "x-api-key": TEST_API_KEY, "content-type": "application/json" },
      payload: JSON.stringify(payload),
    });
  }

  function get(url: string) {
    return ctx.app.inject({
      method: "GET",
      url,
      headers: { "x-api-key": TEST_API_KEY },
    });
  }

  it("POST /v1/jobs with valid URL returns 201", async () => {
    const res = await post("/v1/jobs", { url: "https://example.com/article" });
    expect(res.statusCode).toBe(201);
    const body = res.json();
    expect(body.ok).toBe(true);
    expect(body.id).toBeDefined();
    expect(typeof body.id).toBe("string");
  });

  it("POST /v1/jobs with invalid URL returns 400", async () => {
    const res = await post("/v1/jobs", { url: "not-a-valid-url" });
    expect(res.statusCode).toBe(400);
  });

  it("POST /v1/jobs without url or rss returns 400", async () => {
    const res = await post("/v1/jobs", { something: "else" });
    expect(res.statusCode).toBe(400);
  });

  it("GET /v1/jobs/:id returns created job", async () => {
    const createRes = await post("/v1/jobs", { url: "https://example.com/page" });
    const { id } = createRes.json();

    const getRes = await get(`/v1/jobs/${id}`);
    expect(getRes.statusCode).toBe(200);
    const job = getRes.json();
    expect(job.id).toBe(id);
    expect(job.type).toBe("url");
    expect(job.status).toBe("queued");
    expect(job.source).toBe("https://example.com/page");
  });

  it("GET /v1/jobs/nonexistent returns 404", async () => {
    const res = await get("/v1/jobs/nonexistent-id-12345");
    expect(res.statusCode).toBe(404);
  });

  it("POST /v1/jobs with options passes validation", async () => {
    const res = await post("/v1/jobs", {
      url: "https://example.com",
      options: { format: "markdown", length: "short" },
    });
    expect(res.statusCode).toBe(201);
  });
});

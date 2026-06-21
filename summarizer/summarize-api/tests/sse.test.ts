import { describe, it, expect, afterAll, beforeAll, vi } from "vitest";
import { buildApp, TEST_API_KEY, type TestContext } from "./setup.js";
import { EventStore, type JobEvent } from "../src/events/event-store.js";
import http from "node:http";

// Mock dispatchJob
vi.mock("../src/runners/index.js", () => ({
  dispatchJob: vi.fn(),
}));

// Mock validateUrl
vi.mock("../src/security/ssrf.js", async () => {
  const actual = await vi.importActual<
    typeof import("../src/security/ssrf.js")
  >("../src/security/ssrf.js");
  return {
    ...actual,
    validateUrl: vi.fn(async () => ({ safe: true })),
  };
});

describe("SSE events", () => {
  describe("EventStore (unit)", () => {
    it("stores and retrieves events", () => {
      const store = new EventStore();
      const event: JobEvent = {
        type: "status",
        data: { status: "running" },
        timestamp: new Date().toISOString(),
      };
      store.addEvent("job-1", event);
      const events = store.getEvents("job-1");
      expect(events).toHaveLength(1);
      expect(events[0].type).toBe("status");
      store.dispose();
    });

    it("replays events to new subscribers", () => {
      const store = new EventStore();
      const event: JobEvent = {
        type: "status",
        data: { status: "running" },
        timestamp: new Date().toISOString(),
      };
      store.addEvent("job-2", event);

      const received: JobEvent[] = [];
      const unsub = store.subscribe("job-2", (e) => received.push(e));
      expect(received).toHaveLength(1);
      expect(received[0].type).toBe("status");
      unsub();
      store.dispose();
    });

    it("streams new events to subscribers", () => {
      const store = new EventStore();
      const received: JobEvent[] = [];
      const unsub = store.subscribe("job-3", (e) => received.push(e));

      store.addEvent("job-3", {
        type: "chunk",
        data: { text: "hello" },
        timestamp: new Date().toISOString(),
      });

      expect(received).toHaveLength(1);
      expect(received[0].type).toBe("chunk");
      unsub();
      store.dispose();
    });

    it("unsubscribe stops receiving events", () => {
      const store = new EventStore();
      const received: JobEvent[] = [];
      const unsub = store.subscribe("job-4", (e) => received.push(e));
      unsub();

      store.addEvent("job-4", {
        type: "done",
        data: {},
        timestamp: new Date().toISOString(),
      });

      expect(received).toHaveLength(0);
      store.dispose();
    });
  });

  describe("SSE endpoint", () => {
    let ctx: TestContext;
    let baseUrl: string;

    beforeAll(async () => {
      ctx = await buildApp();
      const addr = await ctx.app.listen({ port: 0, host: "127.0.0.1" });
      baseUrl = addr;
    });

    afterAll(async () => {
      await ctx.cleanup();
    });

    it("GET /v1/jobs/:id/events returns SSE headers and streams events", async () => {
      // Create a job
      const createRes = await ctx.app.inject({
        method: "POST",
        url: "/v1/jobs",
        headers: {
          "x-api-key": TEST_API_KEY,
          "content-type": "application/json",
        },
        payload: JSON.stringify({ url: "https://example.com" }),
      });
      const { id } = createRes.json();

      // Pre-populate events so the SSE endpoint replays them immediately
      // (this forces writeHead + write to flush on subscribe)
      ctx.eventStore.addEvent(id, {
        type: "status",
        data: { status: "running" },
        timestamp: new Date().toISOString(),
      });
      ctx.eventStore.addEvent(id, {
        type: "done",
        data: { summary: "test" },
        timestamp: new Date().toISOString(),
      });

      const result = await new Promise<{
        headers: http.IncomingHttpHeaders;
        data: string;
      }>((resolve, reject) => {
        let body = "";
        const req = http.request(
          `${baseUrl}/v1/jobs/${id}/events`,
          { method: "GET", headers: { "x-api-key": TEST_API_KEY } },
          (res) => {
            const headers = res.headers;
            res.on("data", (chunk: Buffer) => {
              body += chunk.toString();
              if (body.includes('"done"')) {
                res.destroy();
                resolve({ headers, data: body });
              }
            });
          },
        );
        req.on("error", (err) => {
          if ((err as NodeJS.ErrnoException).code !== "ECONNRESET") {
            reject(err);
          }
        });
        req.setTimeout(5000, () => {
          req.destroy();
          reject(new Error("SSE request timed out"));
        });
        req.end();
      });

      expect(result.headers["content-type"]).toBe("text/event-stream");
      expect(result.headers["cache-control"]).toBe("no-cache");
      expect(result.data).toContain('"status"');
      expect(result.data).toContain('"done"');
    });

    it("GET /v1/jobs/nonexistent/events returns 404", async () => {
      const res = await ctx.app.inject({
        method: "GET",
        url: "/v1/jobs/does-not-exist/events",
        headers: { "x-api-key": TEST_API_KEY },
      });
      expect(res.statusCode).toBe(404);
    });
  });
});

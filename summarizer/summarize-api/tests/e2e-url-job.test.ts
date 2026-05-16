import { describe, it, expect, beforeAll, afterAll } from "vitest";
import http from "node:http";
import {
  buildApp,
  startSummarizeDaemon,
  TEST_API_KEY,
  type TestContext,
  type SummarizeDaemonHandle,
} from "./setup.js";
import { getJob } from "../src/db/jobs.js";

interface ReceivedEvent {
  type: string;
  data: unknown;
}

function collectSseEvents(
  url: string,
  timeoutMs: number,
): Promise<ReceivedEvent[]> {
  return new Promise((resolve, reject) => {
    const events: ReceivedEvent[] = [];
    let buffer = "";

    const req = http.request(
      url,
      { method: "GET", headers: { "x-api-key": TEST_API_KEY } },
      (res) => {
        res.on("data", (chunk: Buffer) => {
          buffer += chunk.toString();
          const messages = buffer.split("\n\n");
          buffer = messages.pop() ?? "";

          for (const msg of messages) {
            const dataLine = msg
              .split("\n")
              .find((l) => l.startsWith("data:"));
            if (!dataLine) continue;
            const json = dataLine.slice(5).trim();
            if (!json || json.startsWith(":")) continue;
            try {
              const parsed = JSON.parse(json) as ReceivedEvent;
              events.push(parsed);
              if (parsed.type === "done" || parsed.type === "error") {
                res.destroy();
                resolve(events);
                return;
              }
            } catch {
              // ignore non-JSON
            }
          }
        });
        res.on("end", () => resolve(events));
      },
    );

    req.on("error", (err: NodeJS.ErrnoException) => {
      if (err.code === "ECONNRESET") {
        resolve(events);
      } else {
        reject(err);
      }
    });
    req.setTimeout(timeoutMs, () => {
      req.destroy();
      reject(new Error(`SSE collection timed out after ${timeoutMs}ms`));
    });
    req.end();
  });
}

async function closeDaemon(daemon: SummarizeDaemonHandle): Promise<void> {
  await new Promise<void>((r) => daemon.server.close(() => r()));
}

describe("e2e: URL job lifecycle through real runner", () => {
  describe("happy path", () => {
    let ctx: TestContext;
    let daemon: SummarizeDaemonHandle;
    let baseUrl: string;

    beforeAll(async () => {
      daemon = await startSummarizeDaemon();
      ctx = await buildApp({ daemonUrl: daemon.url });
      baseUrl = await ctx.app.listen({ port: 0, host: "127.0.0.1" });
    });

    afterAll(async () => {
      await ctx.cleanup();
      await closeDaemon(daemon);
    });

    it("creates a job, streams real events from daemon, completes in DB", async () => {
      const createRes = await ctx.app.inject({
        method: "POST",
        url: "/v1/jobs",
        headers: {
          "x-api-key": TEST_API_KEY,
          "content-type": "application/json",
        },
        payload: JSON.stringify({ url: "https://example.com" }),
      });
      expect(createRes.statusCode).toBe(201);
      const { id } = createRes.json() as { id: string };

      const events = await collectSseEvents(
        `${baseUrl}/v1/jobs/${id}/events`,
        5000,
      );

      const statuses = events.filter((e) => e.type === "status");
      const chunks = events.filter((e) => e.type === "chunk");
      const dones = events.filter((e) => e.type === "done");

      expect(statuses.length).toBeGreaterThanOrEqual(1);
      expect(chunks.length).toBeGreaterThanOrEqual(1);
      expect(dones).toHaveLength(1);

      const doneData = dones[0].data as { result?: string };
      expect(doneData.result).toBe("Hello world");

      // Final DB state — job must be marked completed and tagged with daemon id
      const job = getJob(id);
      expect(job).not.toBeNull();
      expect(job!.status).toBe("completed");
      expect(job!.daemon_job_id).toBe("daemon-test-123");

      // Daemon must have received the Bearer token from config
      expect(daemon.capturedAuth).toContain("Bearer test-summarize-token");
    });
  });

  describe("daemon error path", () => {
    let ctx: TestContext;
    let daemon: SummarizeDaemonHandle;
    let baseUrl: string;

    beforeAll(async () => {
      daemon = await startSummarizeDaemon({
        summarizeError: { status: 500, body: "internal daemon error" },
      });
      ctx = await buildApp({ daemonUrl: daemon.url });
      baseUrl = await ctx.app.listen({ port: 0, host: "127.0.0.1" });
    });

    afterAll(async () => {
      await ctx.cleanup();
      await closeDaemon(daemon);
    });

    it("propagates daemon failure as a failed job + error SSE event", async () => {
      const createRes = await ctx.app.inject({
        method: "POST",
        url: "/v1/jobs",
        headers: {
          "x-api-key": TEST_API_KEY,
          "content-type": "application/json",
        },
        payload: JSON.stringify({ url: "https://example.com" }),
      });
      expect(createRes.statusCode).toBe(201);
      const { id } = createRes.json() as { id: string };

      const events = await collectSseEvents(
        `${baseUrl}/v1/jobs/${id}/events`,
        5000,
      );

      const errors = events.filter((e) => e.type === "error");
      expect(errors).toHaveLength(1);
      const errorData = errors[0].data as { message?: string };
      expect(errorData.message).toMatch(/Daemon returned 500/);

      const job = getJob(id);
      expect(job!.status).toBe("failed");
    });
  });
});

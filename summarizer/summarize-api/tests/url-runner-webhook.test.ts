import { describe, it, expect, beforeAll, afterAll } from "vitest";
import { createServer, type Server, type IncomingMessage } from "node:http";
import {
  buildApp,
  startSummarizeDaemon,
  TEST_API_KEY,
  type TestContext,
  type SummarizeDaemonHandle,
} from "./setup.js";
import { getJob } from "../src/db/jobs.js";
import { buildSignatureHeader } from "../src/webhooks/signer.js";

interface CapturedWebhook {
  body: string;
  signature: string;
  headers: Record<string, string>;
}

interface WebhookReceiver {
  url: string;
  server: Server;
  captures: CapturedWebhook[];
  /** Set the next response status. Defaults to 200. */
  setNextStatus: (status: number) => void;
}

function startWebhookReceiver(): Promise<WebhookReceiver> {
  const captures: CapturedWebhook[] = [];
  const queue: number[] = [];

  const setNextStatus = (status: number) => {
    queue.push(status);
  };

  return new Promise((resolve) => {
    const server = createServer((req: IncomingMessage, res) => {
      let body = "";
      req.on("data", (chunk: Buffer) => {
        body += chunk.toString("utf8");
      });
      req.on("end", () => {
        const status = queue.shift() ?? 200;
        captures.push({
          body,
          signature: String(req.headers["x-summarizer-signature"] ?? ""),
          headers: Object.fromEntries(
            Object.entries(req.headers).map(([k, v]) => [k, Array.isArray(v) ? v.join(",") : String(v ?? "")]),
          ),
        });
        res.writeHead(status, { "Content-Type": "text/plain" });
        res.end("");
      });
    });
    server.listen(0, "127.0.0.1", () => {
      const addr = server.address() as { port: number };
      resolve({
        url: `http://127.0.0.1:${addr.port}/webhook`,
        server,
        captures,
        setNextStatus,
      });
    });
  });
}

async function closeServer(server: Server): Promise<void> {
  await new Promise<void>((r) => server.close(() => r()));
}

async function waitForCapture(
  receiver: WebhookReceiver,
  expectedCount: number,
  timeoutMs: number,
): Promise<void> {
  const deadline = Date.now() + timeoutMs;
  while (receiver.captures.length < expectedCount) {
    if (Date.now() > deadline) {
      throw new Error(
        `webhook capture timeout: expected ${expectedCount}, got ${receiver.captures.length}`,
      );
    }
    await new Promise((r) => setTimeout(r, 25));
  }
}

describe("url-runner webhook delivery", () => {
  describe("happy path", () => {
    let ctx: TestContext;
    let daemon: SummarizeDaemonHandle;
    let receiver: WebhookReceiver;

    beforeAll(async () => {
      daemon = await startSummarizeDaemon({ chunks: ["Hello ", "world"] });
      receiver = await startWebhookReceiver();
      // Compress retry delays so retry tests don't block 5s+30s.
      ctx = await buildApp({
        daemonUrl: daemon.url,
        urlRunnerOpts: { webhookOverrides: { baseDelayMs: 10 } },
      });
    });

    afterAll(async () => {
      await ctx.cleanup();
      await closeServer(daemon.server);
      await closeServer(receiver.server);
    });

    it("posts a signed completion webhook with the daemon result", async () => {
      const createRes = await ctx.app.inject({
        method: "POST",
        url: "/v1/jobs",
        headers: { "x-api-key": TEST_API_KEY, "content-type": "application/json" },
        payload: JSON.stringify({
          url: "https://example.com",
          webhook_url: receiver.url,
          webhook_secret: "sixteen-byte-test-secret",
          client_job_id: "fixture-happy",
        }),
      });
      expect(createRes.statusCode).toBe(201);
      const { id } = createRes.json() as { id: string };

      await waitForCapture(receiver, 1, 5000);

      const capture = receiver.captures[0];
      expect(capture.signature).toMatch(/^t=\d+,v1=[0-9a-f]{64}$/);

      // Verify the signature against the raw body the receiver saw.
      const tMatch = capture.signature.match(/^t=(\d+),v1=(.+)$/);
      const t = Number(tMatch![1]);
      const expected = buildSignatureHeader(
        "sixteen-byte-test-secret",
        capture.body,
        t,
      ).header;
      expect(capture.signature).toBe(expected);

      const parsed = JSON.parse(capture.body) as {
        client_job_id: string;
        status: string;
        result: { summary: string };
      };
      expect(parsed.client_job_id).toBe("fixture-happy");
      expect(parsed.status).toBe("completed");
      expect(parsed.result.summary).toBe("Hello world");

      const job = getJob(id);
      expect(job!.status).toBe("completed");
      expect(job!.webhook_url).toBe(receiver.url);
      expect(job!.client_job_id).toBe("fixture-happy");
    });
  });

  describe("daemon failure path", () => {
    let ctx: TestContext;
    let daemon: SummarizeDaemonHandle;
    let receiver: WebhookReceiver;

    beforeAll(async () => {
      daemon = await startSummarizeDaemon({
        summarizeError: { status: 500, body: "boom" },
      });
      receiver = await startWebhookReceiver();
      ctx = await buildApp({ daemonUrl: daemon.url });
    });

    afterAll(async () => {
      await ctx.cleanup();
      await closeServer(daemon.server);
      await closeServer(receiver.server);
    });

    it("delivers a failed-status webhook with the error message", async () => {
      const createRes = await ctx.app.inject({
        method: "POST",
        url: "/v1/jobs",
        headers: { "x-api-key": TEST_API_KEY, "content-type": "application/json" },
        payload: JSON.stringify({
          url: "https://example.com",
          webhook_url: receiver.url,
          webhook_secret: "sixteen-byte-test-secret",
          client_job_id: "fixture-fail",
        }),
      });
      expect(createRes.statusCode).toBe(201);

      await waitForCapture(receiver, 1, 5000);

      const parsed = JSON.parse(receiver.captures[0].body) as {
        client_job_id: string;
        status: string;
        error: { message: string };
      };
      expect(parsed.client_job_id).toBe("fixture-fail");
      expect(parsed.status).toBe("failed");
      expect(parsed.error.message).toMatch(/Daemon returned 500/);
    });
  });

  describe("daemon SSE error event", () => {
    let ctx: TestContext;
    let daemon: SummarizeDaemonHandle;
    let receiver: WebhookReceiver;

    beforeAll(async () => {
      daemon = await startSummarizeDaemon({
        daemonError: { message: "Unsupported model provider \"meta-llama\"" },
      });
      receiver = await startWebhookReceiver();
      ctx = await buildApp({ daemonUrl: daemon.url });
    });

    afterAll(async () => {
      await ctx.cleanup();
      await closeServer(daemon.server);
      await closeServer(receiver.server);
    });

    it("delivers a failed-status webhook carrying the daemon's error message", async () => {
      const createRes = await ctx.app.inject({
        method: "POST",
        url: "/v1/jobs",
        headers: { "x-api-key": TEST_API_KEY, "content-type": "application/json" },
        payload: JSON.stringify({
          url: "https://example.com",
          webhook_url: receiver.url,
          webhook_secret: "sixteen-byte-test-secret",
          client_job_id: "fixture-daemon-error",
        }),
      });
      expect(createRes.statusCode).toBe(201);

      await waitForCapture(receiver, 1, 5000);

      const parsed = JSON.parse(receiver.captures[0].body) as {
        client_job_id: string;
        status: string;
        error?: { message: string };
        result?: { summary: string };
      };
      expect(parsed.client_job_id).toBe("fixture-daemon-error");
      expect(parsed.status).toBe("failed");
      expect(parsed.error?.message).toMatch(/Unsupported model provider/);
      expect(parsed.result).toBeUndefined();
    });
  });

  describe("no webhook configured", () => {
    let ctx: TestContext;
    let daemon: SummarizeDaemonHandle;

    beforeAll(async () => {
      daemon = await startSummarizeDaemon();
      ctx = await buildApp({ daemonUrl: daemon.url });
    });

    afterAll(async () => {
      await ctx.cleanup();
      await closeServer(daemon.server);
    });

    it("completes the job without attempting webhook delivery", async () => {
      const createRes = await ctx.app.inject({
        method: "POST",
        url: "/v1/jobs",
        headers: { "x-api-key": TEST_API_KEY, "content-type": "application/json" },
        payload: JSON.stringify({ url: "https://example.com" }),
      });
      expect(createRes.statusCode).toBe(201);
      const { id } = createRes.json() as { id: string };

      // Allow the job runner to complete.
      await new Promise((r) => setTimeout(r, 250));

      const job = getJob(id);
      expect(job!.status).toBe("completed");
      expect(job!.webhook_url).toBeNull();
    });
  });
});

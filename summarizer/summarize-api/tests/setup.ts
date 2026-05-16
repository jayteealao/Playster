import { createServer as createHttpServer, type Server } from "node:http";
import { mkdirSync, rmSync } from "node:fs";
import { join } from "node:path";
import { tmpdir } from "node:os";
import { randomBytes } from "node:crypto";
import Fastify, { type FastifyInstance } from "fastify";
import fastifyMultipart from "@fastify/multipart";
import fastifyRateLimit from "@fastify/rate-limit";
import fastifyCors from "@fastify/cors";
import type { Config } from "../src/config.js";
import { getDb, closeDb } from "../src/db/index.js";
import { requestIdHook } from "../src/middleware/request-id.js";
import { authHook } from "../src/middleware/auth.js";
import { healthRoutes } from "../src/routes/health.js";
import { jobRoutes } from "../src/routes/jobs.js";
import { EventStore } from "../src/events/event-store.js";

export const TEST_API_KEY = "test-key-12345";

let tmpCounter = 0;

export interface TestContext {
  app: FastifyInstance;
  config: Config;
  eventStore: EventStore;
  dbPath: string;
  tmpDir: string;
  cleanup: () => Promise<void>;
}

/**
 * Start a mock daemon HTTP server that responds to /health and
 * acts as a stub for URL job proxy requests.
 */
export function startMockDaemon(): Promise<{ url: string; server: Server }> {
  return new Promise((resolve) => {
    const server = createHttpServer((req, res) => {
      if (req.url === "/health") {
        res.writeHead(200, { "Content-Type": "application/json" });
        res.end(JSON.stringify({ status: "ok" }));
        return;
      }
      // Default: 200 with a stub summarize response (SSE-style)
      res.writeHead(200, { "Content-Type": "text/event-stream" });
      res.write(`data: ${JSON.stringify({ type: "status", data: { status: "running" } })}\n\n`);
      res.write(`data: ${JSON.stringify({ type: "done", data: { summary: "test summary" } })}\n\n`);
      res.end();
    });
    server.listen(0, "127.0.0.1", () => {
      const addr = server.address() as { port: number };
      resolve({ url: `http://127.0.0.1:${addr.port}`, server });
    });
  });
}

/**
 * Build a test Fastify instance with all plugins/routes registered.
 * Uses a unique temp DB per call so tests are isolated.
 */
export async function buildApp(overrides?: Partial<Config>): Promise<TestContext> {
  const id = `${Date.now()}-${tmpCounter++}-${randomBytes(4).toString("hex")}`;
  const tmpDir = join(tmpdir(), "summarize-api-test", id);
  mkdirSync(tmpDir, { recursive: true });

  const dbPath = join(tmpDir, "test.db");

  // Close any existing DB singleton so getDb() re-creates with new path
  closeDb();

  // Set env so the DB singleton picks up the new path
  process.env["DB_PATH"] = dbPath;

  const db = getDb();
  const eventStore = new EventStore();

  const config: Config = {
    port: 0,
    host: "127.0.0.1",
    apiKeys: [TEST_API_KEY],
    daemonUrl: overrides?.daemonUrl ?? "http://127.0.0.1:19999",
    summarizeToken: "test-summarize-token",
    maxUploadSize: 10 * 1024 * 1024,
    maxConcurrentJobs: 5,
    jobTimeout: 30_000,
    rateLimitMax: overrides?.rateLimitMax ?? 100,
    rateLimitWindow: overrides?.rateLimitWindow ?? "1 minute",
    dbPath,
    ...overrides,
  };

  const app = Fastify({ logger: false });

  await app.register(fastifyMultipart, {
    limits: { fileSize: config.maxUploadSize },
  });
  await app.register(fastifyRateLimit, {
    max: config.rateLimitMax,
    timeWindow: config.rateLimitWindow,
  });
  await app.register(fastifyCors, { origin: false });

  app.addHook("onRequest", requestIdHook);
  app.addHook("onRequest", authHook(config));

  await app.register(healthRoutes, { config });
  await app.register(jobRoutes, { config, eventStore, db });

  await app.ready();

  async function cleanup() {
    eventStore.dispose();
    await app.close();
    try {
      closeDb();
    } catch {
      // ignore
    }
    try {
      rmSync(tmpDir, { recursive: true, force: true });
    } catch {
      // ignore
    }
  }

  return { app, config, eventStore, dbPath, tmpDir, cleanup };
}

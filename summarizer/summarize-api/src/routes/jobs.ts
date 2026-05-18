import { mkdirSync } from "node:fs";
import { join, extname } from "node:path";
import { pipeline } from "node:stream/promises";
import { createWriteStream } from "node:fs";
import type { FastifyInstance } from "fastify";
import type Database from "better-sqlite3";
import { nanoid } from "nanoid";
import type { Config } from "../config.js";
import type { EventStore, JobEvent } from "../events/event-store.js";
import { createJob, getJob } from "../db/jobs.js";
import { dispatchJob } from "../runners/index.js";
import { validateUrl } from "../security/ssrf.js";
import { urlJobSchema, rssJobSchema } from "../schemas.js";

const UPLOAD_DIR = "./data/uploads";

export async function jobRoutes(
  app: FastifyInstance,
  opts: { config: Config; eventStore: EventStore; db: Database.Database },
) {
  const { config, eventStore, db } = opts;

  // POST /v1/jobs — create a new job
  app.post("/v1/jobs", async (request, reply) => {
    const contentType = request.headers["content-type"] ?? "";

    // --- Multipart file upload ---
    if (contentType.includes("multipart/form-data")) {
      const file = await request.file();
      if (!file) {
        return reply.code(400).send({ error: "No file uploaded" });
      }

      // Check file size via Content-Length header as a pre-check
      const declaredSize = Number(request.headers["content-length"] ?? 0);
      if (declaredSize > config.maxUploadSize) {
        return reply.code(413).send({ error: "File too large" });
      }

      // Ensure upload directory exists
      mkdirSync(UPLOAD_DIR, { recursive: true });

      const ext = extname(file.filename) || ".bin";
      const tempName = `${nanoid()}${ext}`;
      const tempPath = join(UPLOAD_DIR, tempName);

      // Stream file to disk
      await pipeline(file.file, createWriteStream(tempPath));

      // Check if the stream was truncated (file exceeded limit)
      if (file.file.truncated) {
        return reply.code(413).send({ error: "File too large" });
      }

      // Parse options from the multipart form field
      let uploadOptions: Record<string, unknown> = {};
      const fields = file.fields;
      const optionsField = fields["options"];
      if (
        optionsField &&
        "value" in optionsField &&
        typeof optionsField.value === "string"
      ) {
        try {
          uploadOptions = JSON.parse(optionsField.value);
        } catch {
          return reply
            .code(400)
            .send({ error: "Invalid JSON in options field" });
        }
      }

      const job = createJob({
        type: "upload",
        source: tempPath,
        options: uploadOptions,
      });

      dispatchJob(job, config, eventStore, db);

      return reply.code(201).send({ ok: true, id: job.id });
    }

    // --- JSON body ---
    const body = request.body as Record<string, unknown> | undefined;
    if (!body || typeof body !== "object") {
      return reply.code(400).send({ error: "Request body is required" });
    }

    // URL job
    if ("url" in body) {
      const parsed = urlJobSchema.safeParse(body);
      if (!parsed.success) {
        return reply.code(400).send({
          error: "Validation failed",
          details: parsed.error.flatten().fieldErrors,
        });
      }

      if (parsed.data.webhook_url && !parsed.data.webhook_secret) {
        return reply.code(400).send({
          error: "webhook_secret is required when webhook_url is set",
        });
      }

      const ssrfResult = await validateUrl(parsed.data.url);
      if (!ssrfResult.safe) {
        return reply
          .code(403)
          .send({ error: ssrfResult.error ?? "URL blocked by SSRF policy" });
      }

      const job = createJob({
        type: "url",
        source: parsed.data.url,
        options: parsed.data.options,
        webhookUrl: parsed.data.webhook_url,
        webhookSecret: parsed.data.webhook_secret,
        clientJobId: parsed.data.client_job_id,
      });

      dispatchJob(job, config, eventStore, db);

      return reply.code(201).send({ ok: true, id: job.id });
    }

    // RSS job
    if ("rss" in body) {
      const parsed = rssJobSchema.safeParse(body);
      if (!parsed.success) {
        return reply.code(400).send({
          error: "Validation failed",
          details: parsed.error.flatten().fieldErrors,
        });
      }

      const job = createJob({
        type: "rss",
        source: parsed.data.rss,
        options: {
          ...parsed.data.options,
          item: parsed.data.item,
        },
      });

      dispatchJob(job, config, eventStore, db);

      return reply.code(201).send({ ok: true, id: job.id });
    }

    return reply.code(400).send({
      error: "Request must include a 'url' or 'rss' field, or be a multipart file upload",
    });
  });

  // GET /v1/jobs/:id — get job status
  app.get<{ Params: { id: string } }>("/v1/jobs/:id", async (request, reply) => {
    const job = getJob(request.params.id);
    if (!job) {
      return reply.code(404).send({ error: "Job not found" });
    }
    return job;
  });

  // GET /v1/jobs/:id/events — SSE stream
  app.get<{ Params: { id: string } }>("/v1/jobs/:id/events", async (request, reply) => {
    const job = getJob(request.params.id);
    if (!job) {
      return reply.code(404).send({ error: "Job not found" });
    }

    // Take over the response from Fastify
    reply.hijack();

    const raw = reply.raw;

    raw.writeHead(200, {
      "Content-Type": "text/event-stream",
      "Cache-Control": "no-cache",
      "Connection": "keep-alive",
      "X-Accel-Buffering": "no",
    });

    // Keepalive timer
    const keepalive = setInterval(() => {
      if (!raw.destroyed) {
        raw.write(":\n\n");
      }
    }, 15_000);

    let unsubscribe: (() => void) | null = null;

    function cleanup() {
      clearInterval(keepalive);
      if (unsubscribe) {
        unsubscribe();
        unsubscribe = null;
      }
    }

    // Handle client disconnect
    request.raw.on("close", cleanup);

    // Subscribe to events — this replays existing events first, then streams new ones
    unsubscribe = eventStore.subscribe(request.params.id, (event: JobEvent) => {
      if (raw.destroyed) {
        cleanup();
        return;
      }

      raw.write(`data: ${JSON.stringify(event)}\n\n`);

      // Close connection after terminal events
      if (event.type === "done" || event.type === "error") {
        cleanup();
        raw.end();
      }
    });
  });
}

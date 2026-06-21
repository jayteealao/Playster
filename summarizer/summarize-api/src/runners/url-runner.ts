import { createParser } from "eventsource-parser";
import type Database from "better-sqlite3";
import type { Config } from "../config.js";
import type { Job } from "../db/jobs.js";
import { updateJobStatus } from "../db/jobs.js";
import type { EventStore } from "../events/event-store.js";
import { deliverWebhook } from "../webhooks/deliver.js";

// Keys forwarded from the api `options` object to the daemon body. The
// daemon accepts a superset (see summarize-daemon's
// `server-summarize-request.ts`); we only forward the ones the gateway
// schema exposes. The api `mode` enum (auto|website|youtube|media) is
// intentionally NOT in this list — it does not map to the daemon's `mode`
// enum (url|page|auto), and shape decision is to leave the gateway `mode`
// as a documented no-op.
const DAEMON_FORWARD_KEYS = [
  "model",
  "length",
  "language",
  "format",
  "youtube",
  "videoMode",
  "timestamps",
  "forceSummary",
  "noCache",
  "extractOnly",
  "prompt",
  "maxCharacters",
];

export interface UrlRunnerOpts {
  webhookOverrides?: {
    baseDelayMs?: number;
    fetchImpl?: typeof fetch;
  };
  fetchImpl?: typeof fetch;
}

// Context threaded through the SSE handler.
interface SseContext {
  jobId: string;
  eventStore: EventStore;
  accumulatedChunks: string[];
  resolve: () => void;
  reject: (err: Error) => void;
  terminalEventReceived: { value: boolean };
}

function handleDaemonSseEvent(
  event: { event?: string; data: string },
  ctx: SseContext,
): void {
  const now = new Date().toISOString();
  let eventData: unknown;
  try {
    eventData = JSON.parse(event.data);
  } catch {
    eventData = event.data;
  }

  switch (event.event) {
    case "chunk":
      ctx.accumulatedChunks.push(
        typeof eventData === "object" &&
          eventData !== null &&
          "text" in eventData
          ? (eventData as { text: string }).text
          : String(event.data),
      );
      ctx.eventStore.addEvent(ctx.jobId, {
        type: "chunk",
        data: {
          text:
            typeof eventData === "object" &&
            eventData !== null &&
            "text" in eventData
              ? (eventData as { text: string }).text
              : event.data,
        },
        timestamp: now,
      });
      break;
    case "status":
      ctx.eventStore.addEvent(ctx.jobId, {
        type: "status",
        data: eventData,
        timestamp: now,
      });
      break;
    case "meta":
      ctx.eventStore.addEvent(ctx.jobId, {
        type: "meta",
        data: eventData,
        timestamp: now,
      });
      break;
    case "metrics":
      ctx.eventStore.addEvent(ctx.jobId, {
        type: "metrics",
        data: eventData,
        timestamp: now,
      });
      break;
    case "complete":
    case "done":
      ctx.eventStore.addEvent(ctx.jobId, {
        type: "done",
        data: { done: true, result: ctx.accumulatedChunks.join("") },
        timestamp: now,
      });
      ctx.terminalEventReceived.value = true;
      ctx.resolve();
      break;
    case "error": {
      const message =
        typeof eventData === "object" &&
        eventData !== null &&
        "message" in eventData
          ? String((eventData as { message: unknown }).message)
          : String(event.data);
      ctx.terminalEventReceived.value = true;
      ctx.reject(new Error(message));
      break;
    }
  }
}

async function pumpReader(
  reader: ReadableStreamDefaultReader<Uint8Array>,
  onChunk: (text: string) => void,
  onDone: () => void,
  onError: (err: unknown) => void,
): Promise<void> {
  const decoder = new TextDecoder();
  function read(): void {
    reader.read().then(({ done, value }) => {
      if (done) {
        onDone();
        return;
      }
      onChunk(decoder.decode(value, { stream: true }));
      read();
    }, onError);
  }
  read();
}

export async function runUrlJob(
  job: Job,
  config: Config,
  eventStore: EventStore,
  _db: Database.Database,
  opts?: UrlRunnerOpts,
): Promise<void> {
  const doFetch = opts?.fetchImpl ?? fetch;
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), config.jobTimeout);

  try {
    // Step 1: Mark running
    updateJobStatus(job.id, "running");
    eventStore.addEvent(job.id, {
      type: "status",
      data: { status: "running" },
      timestamp: new Date().toISOString(),
    });

    // Step 2: POST to daemon
    const parsedOptions = job.options ? JSON.parse(job.options) : {};
    const response = await doFetch(`${config.daemonUrl}/v1/summarize`, {
      method: "POST",
      headers: {
        Authorization: `Bearer ${config.summarizeToken}`,
        "Content-Type": "application/json",
      },
      body: JSON.stringify({
        url: job.source,
        ...pickDefined(parsedOptions, DAEMON_FORWARD_KEYS),
      }),
      signal: controller.signal,
    });

    if (!response.ok) {
      const text = await response.text();
      throw new Error(`Daemon returned ${response.status}: ${text}`);
    }

    // Step 3: Get daemon session ID
    const daemonResponse = (await response.json()) as {
      id: string;
      status: string;
    };
    const daemonId = daemonResponse.id;

    // Step 4: Update daemon_job_id
    updateJobStatus(job.id, "running", { daemonJobId: daemonId });

    // Step 5: Connect to daemon SSE
    const sseResponse = await doFetch(
      `${config.daemonUrl}/v1/summarize/${daemonId}/events`,
      {
        headers: {
          Authorization: `Bearer ${config.summarizeToken}`,
          Accept: "text/event-stream",
        },
        signal: controller.signal,
      },
    );

    if (!sseResponse.ok || !sseResponse.body) {
      throw new Error(`SSE connection failed: ${sseResponse.status}`);
    }

    // Parse SSE stream
    const accumulatedChunks: string[] = [];

    await new Promise<void>((resolve, reject) => {
      const terminalEventReceived = { value: false };
      const ctx: SseContext = {
        jobId: job.id,
        eventStore,
        accumulatedChunks,
        resolve,
        reject,
        terminalEventReceived,
      };

      const parser = createParser({
        onEvent(event) {
          handleDaemonSseEvent(event, ctx);
        },
        onError(error) {
          reject(error);
        },
      });

      const reader = sseResponse.body!.getReader();

      pumpReader(
        reader,
        (text) => parser.feed(text),
        () => {
          // EOF: only resolve if a terminal event was received; otherwise the
          // stream closed unexpectedly and we must surface that as an error.
          parser.reset({ consume: true });
          if (terminalEventReceived.value) {
            resolve();
          } else {
            reject(new Error("stream closed before terminal event"));
          }
        },
        reject,
      );
    });

    // Step 6: Complete
    const result = accumulatedChunks.join("");
    updateJobStatus(job.id, "completed", {
      result: { summary: result },
    });

    await maybeDeliverWebhook(
      job,
      {
        client_job_id: job.client_job_id ?? job.id,
        status: "completed",
        result: { summary: result },
      },
      opts?.webhookOverrides,
    );
  } catch (err: unknown) {
    // Step 7: Error handling
    const message = err instanceof Error ? err.message : String(err);
    updateJobStatus(job.id, "failed", {
      error: { message },
    });
    eventStore.addEvent(job.id, {
      type: "error",
      data: { message },
      timestamp: new Date().toISOString(),
    });

    await maybeDeliverWebhook(
      job,
      {
        client_job_id: job.client_job_id ?? job.id,
        status: "failed",
        error: { message },
      },
      opts?.webhookOverrides,
    );
  } finally {
    clearTimeout(timeout);
  }
}

async function maybeDeliverWebhook(
  job: Job,
  payload: Record<string, unknown>,
  overrides?: UrlRunnerOpts["webhookOverrides"],
): Promise<void> {
  if (!job.webhook_url) return;
  if (!job.webhook_secret) {
    // Defensive: the route guards against this, but persisted state could
    // be missing the secret if an older job row predates the column. Log
    // and skip — never deliver an unsigned webhook.
    logWebhookEvent(job.id, "skipped (no secret)", { url: job.webhook_url });
    return;
  }

  const result = await deliverWebhook({
    url: job.webhook_url,
    secret: job.webhook_secret,
    payload,
    baseDelayMs: overrides?.baseDelayMs,
    fetchImpl: overrides?.fetchImpl,
  });

  logWebhookEvent(job.id, result.ok ? "delivered" : "failed", {
    url: job.webhook_url,
    status: result.status,
    attempts: result.attempts,
    error: result.error,
    // `webhook_secret` is intentionally never logged.
  });
}

function logWebhookEvent(
  jobId: string,
  outcome: string,
  extra: Record<string, unknown>,
): void {
  const entry = {
    level: outcome === "delivered" ? "info" : "warn",
    component: "webhook",
    jobId,
    outcome,
    ...extra,
  };
  const stream = outcome === "delivered" ? process.stdout : process.stderr;
  stream.write(`${JSON.stringify(entry)}\n`);
}

function pickDefined(
  obj: Record<string, unknown>,
  keys: string[],
): Record<string, unknown> {
  const result: Record<string, unknown> = {};
  for (const key of keys) {
    if (obj[key] !== undefined) {
      result[key] = obj[key];
    }
  }
  return result;
}

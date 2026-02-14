import { createParser } from "eventsource-parser";
import type Database from "better-sqlite3";
import type { Config } from "../config.js";
import type { Job } from "../db/jobs.js";
import { updateJobStatus } from "../db/jobs.js";
import type { EventStore } from "../events/event-store.js";

export async function runUrlJob(
  job: Job,
  config: Config,
  eventStore: EventStore,
  db: Database.Database,
): Promise<void> {
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
    const response = await fetch(`${config.daemonUrl}/v1/summarize`, {
      method: "POST",
      headers: {
        Authorization: `Bearer ${config.summarizeToken}`,
        "Content-Type": "application/json",
      },
      body: JSON.stringify({
        url: job.source,
        ...pickDefined(parsedOptions, ["model", "length", "language", "format"]),
      }),
      signal: controller.signal,
    });

    if (!response.ok) {
      const text = await response.text();
      throw new Error(`Daemon returned ${response.status}: ${text}`);
    }

    // Step 3: Get daemon session ID
    const daemonResponse = (await response.json()) as { id: string; status: string };
    const daemonId = daemonResponse.id;

    // Step 4: Update daemon_job_id
    updateJobStatus(job.id, "running", { daemonJobId: daemonId });

    // Step 5: Connect to daemon SSE
    const sseResponse = await fetch(
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
      const parser = createParser({
        onEvent(event) {
          const now = new Date().toISOString();
          let eventData: unknown;
          try {
            eventData = JSON.parse(event.data);
          } catch {
            eventData = event.data;
          }

          switch (event.event) {
            case "chunk":
              accumulatedChunks.push(
                typeof eventData === "object" && eventData !== null && "text" in eventData
                  ? (eventData as { text: string }).text
                  : String(event.data),
              );
              eventStore.addEvent(job.id, {
                type: "chunk",
                data: { text: typeof eventData === "object" && eventData !== null && "text" in eventData
                  ? (eventData as { text: string }).text
                  : event.data },
                timestamp: now,
              });
              break;
            case "status":
              eventStore.addEvent(job.id, {
                type: "status",
                data: eventData,
                timestamp: now,
              });
              break;
            case "meta":
              eventStore.addEvent(job.id, {
                type: "meta",
                data: eventData,
                timestamp: now,
              });
              break;
            case "metrics":
              eventStore.addEvent(job.id, {
                type: "metrics",
                data: eventData,
                timestamp: now,
              });
              break;
            case "complete":
              eventStore.addEvent(job.id, {
                type: "done",
                data: { done: true, result: accumulatedChunks.join("") },
                timestamp: now,
              });
              resolve();
              break;
          }
        },
        onError(error) {
          reject(error);
        },
      });

      const reader = sseResponse.body!.getReader();
      const decoder = new TextDecoder();

      function read(): void {
        reader.read().then(({ done, value }) => {
          if (done) {
            parser.reset({ consume: true });
            resolve();
            return;
          }
          parser.feed(decoder.decode(value, { stream: true }));
          read();
        }, reject);
      }
      read();
    });

    // Step 6: Complete
    const result = accumulatedChunks.join("");
    updateJobStatus(job.id, "completed", {
      result: { summary: result },
    });
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
  } finally {
    clearTimeout(timeout);
  }
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

import pLimit from "p-limit";
import type Database from "better-sqlite3";
import type { Config } from "../config.js";
import type { Job } from "../db/jobs.js";
import { updateJobStatus } from "../db/jobs.js";
import type { EventStore } from "../events/event-store.js";
import { runUrlJob } from "./url-runner.js";
import { runRssJob } from "./rss-runner.js";
import { runUploadJob } from "./upload-runner.js";

let limiter: ReturnType<typeof pLimit> | null = null;

function getLimiter(config: Config): ReturnType<typeof pLimit> {
  if (!limiter) {
    limiter = pLimit(config.maxConcurrentJobs);
  }
  return limiter;
}

export function dispatchJob(
  job: Job,
  config: Config,
  eventStore: EventStore,
  db: Database.Database,
): void {
  const limit = getLimiter(config);

  limit(async () => {
    switch (job.type) {
      case "url":
        await runUrlJob(job, config, eventStore, db);
        break;
      case "upload":
        await runUploadJob(job, config, eventStore, db);
        break;
      case "rss":
        await runRssJob(job, config, eventStore, db);
        break;
    }
  }).catch((err: unknown) => {
    const message = err instanceof Error ? err.message : String(err);
    console.error(`Job ${job.id} failed:`, message);
    updateJobStatus(job.id, "failed", {
      error: { message },
    });
  });
}

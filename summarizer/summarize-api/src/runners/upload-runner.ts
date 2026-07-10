import { spawn } from "node:child_process";
import { unlink } from "node:fs/promises";
import type { Database } from "better-sqlite3";
import type { Config } from "../config.js";
import type { Job } from "../db/jobs.js";
import { updateJobStatus } from "../db/jobs.js";

export interface EventStore {
  addEvent(
    jobId: string,
    event: { type: string; data: unknown; timestamp: string },
  ): void;
}

function emitEvent(
  eventStore: EventStore,
  jobId: string,
  type: string,
  data: unknown,
): void {
  eventStore.addEvent(jobId, {
    type,
    data,
    timestamp: new Date().toISOString(),
  });
}

async function cleanupFile(filePath: string): Promise<void> {
  try {
    await unlink(filePath);
  } catch {
    // File already gone — ignore
  }
}

export async function runUploadJob(
  job: Job,
  config: Config,
  eventStore: EventStore,
  _db: Database,
): Promise<void> {
  const filePath = job.source;

  // Step 1: Update job status to running
  updateJobStatus(job.id, "running");

  // Step 2: Emit initial status event
  emitEvent(eventStore, job.id, "status", {
    message: "Processing uploaded file...",
  });

  try {
    const { stdout, stderr, exitCode } = await runSummarizeProcess(
      filePath,
      config.jobTimeout,
      eventStore,
      job.id,
    );

    if (exitCode === 0) {
      // Step 5: Success — parse JSON output
      let parsedResult: unknown;
      try {
        parsedResult = JSON.parse(stdout);
      } catch {
        const errorMsg = "Failed to parse CLI JSON output";
        emitEvent(eventStore, job.id, "error", {
          done: true,
          error: { message: errorMsg, code: exitCode },
        });
        updateJobStatus(job.id, "failed", {
          error: { message: errorMsg, stdout },
        });
        return;
      }

      emitEvent(eventStore, job.id, "done", {
        done: true,
        result: parsedResult,
      });
      updateJobStatus(job.id, "completed", {
        result: parsedResult as Record<string, unknown>,
      });
    } else {
      // Step 6: Non-zero exit
      emitEvent(eventStore, job.id, "error", {
        done: true,
        error: { message: stderr, code: exitCode },
      });
      updateJobStatus(job.id, "failed", {
        error: { message: stderr, code: exitCode },
      });
    }
  } catch (err) {
    // Step 6: Process error (timeout, spawn failure, etc.)
    const message =
      err instanceof Error ? err.message : "Unknown process error";
    emitEvent(eventStore, job.id, "error", {
      done: true,
      error: { message },
    });
    updateJobStatus(job.id, "failed", { error: { message } });
  } finally {
    // Step 7: Clean up temp file
    await cleanupFile(filePath);
  }
}

function runSummarizeProcess(
  filePath: string,
  timeoutMs: number,
  eventStore: EventStore,
  jobId: string,
): Promise<{ stdout: string; stderr: string; exitCode: number }> {
  return new Promise((resolve, reject) => {
    const child = spawn("summarize", [filePath, "--json", "--plain"], {
      stdio: ["ignore", "pipe", "pipe"],
    });

    let stdout = "";
    let stderr = "";
    let killed = false;

    // Timeout handling
    const timer = setTimeout(() => {
      killed = true;
      child.kill("SIGTERM");
      // Force kill after 5 seconds if still alive
      setTimeout(() => {
        if (!child.killed) {
          child.kill("SIGKILL");
        }
      }, 5000);
    }, timeoutMs);

    child.stdout.on("data", (chunk: Buffer) => {
      stdout += chunk.toString();
    });

    child.stderr.on("data", (chunk: Buffer) => {
      const text = chunk.toString();
      stderr += text;

      // Step 4: Emit status events for non-empty stderr lines (progress)
      const lines = text.split("\n");
      for (const line of lines) {
        const trimmed = line.trim();
        if (trimmed) {
          emitEvent(eventStore, jobId, "status", { message: trimmed });
        }
      }
    });

    child.on("error", (err) => {
      clearTimeout(timer);
      reject(err);
    });

    child.on("close", (code) => {
      clearTimeout(timer);

      if (killed) {
        reject(new Error("Process timed out"));
        return;
      }

      resolve({ stdout, stderr, exitCode: code ?? 1 });
    });
  });
}

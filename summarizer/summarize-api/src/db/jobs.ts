import { nanoid } from "nanoid";
import { getDb } from "./index.js";

export interface Job {
  id: string;
  type: "url" | "upload" | "rss";
  status: "queued" | "running" | "completed" | "failed";
  source: string;
  options: string | null;
  result: string | null;
  error: string | null;
  daemon_job_id: string | null;
  client_id: string | null;
  metadata: string | null;
  webhook_url: string | null;
  webhook_secret: string | null;
  client_job_id: string | null;
  created_at: string;
  updated_at: string;
  completed_at: string | null;
}

export function createJob(params: {
  type: Job["type"];
  source: string;
  options?: Record<string, unknown>;
  clientId?: string;
  webhookUrl?: string;
  webhookSecret?: string;
  clientJobId?: string;
}): Job {
  const db = getDb();
  const id = nanoid();
  const now = new Date().toISOString();

  const stmt = db.prepare(`
    INSERT INTO jobs (id, type, source, options, client_id, webhook_url, webhook_secret, client_job_id, created_at, updated_at)
    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
  `);

  stmt.run(
    id,
    params.type,
    params.source,
    params.options ? JSON.stringify(params.options) : null,
    params.clientId ?? null,
    params.webhookUrl ?? null,
    params.webhookSecret ?? null,
    params.clientJobId ?? null,
    now,
    now,
  );

  return getJob(id)!;
}

export function getJob(id: string): Job | null {
  const db = getDb();
  const stmt = db.prepare("SELECT * FROM jobs WHERE id = ?");
  return (stmt.get(id) as Job) ?? null;
}

export function updateJobStatus(
  id: string,
  status: Job["status"],
  extra?: {
    daemonJobId?: string;
    error?: Record<string, unknown>;
    result?: Record<string, unknown>;
    metadata?: Record<string, unknown>;
  },
): void {
  const db = getDb();
  const now = new Date().toISOString();

  const sets: string[] = ["status = ?", "updated_at = ?"];
  const values: unknown[] = [status, now];

  if (status === "completed" || status === "failed") {
    sets.push("completed_at = ?");
    values.push(now);
  }

  if (extra?.daemonJobId !== undefined) {
    sets.push("daemon_job_id = ?");
    values.push(extra.daemonJobId);
  }

  if (extra?.error !== undefined) {
    sets.push("error = ?");
    values.push(JSON.stringify(extra.error));
  }

  if (extra?.result !== undefined) {
    sets.push("result = ?");
    values.push(JSON.stringify(extra.result));
  }

  if (extra?.metadata !== undefined) {
    sets.push("metadata = ?");
    values.push(JSON.stringify(extra.metadata));
  }

  values.push(id);

  const stmt = db.prepare(
    `UPDATE jobs SET ${sets.join(", ")} WHERE id = ?`,
  );
  stmt.run(...values);
}

export function updateJobResult(
  id: string,
  result: Record<string, unknown>,
): void {
  const db = getDb();
  const now = new Date().toISOString();

  const stmt = db.prepare(
    "UPDATE jobs SET result = ?, updated_at = ? WHERE id = ?",
  );
  stmt.run(JSON.stringify(result), now, id);
}

export function listJobs(filters?: {
  status?: Job["status"];
  clientId?: string;
  limit?: number;
}): Job[] {
  const db = getDb();

  const conditions: string[] = [];
  const values: unknown[] = [];

  if (filters?.status) {
    conditions.push("status = ?");
    values.push(filters.status);
  }

  if (filters?.clientId) {
    conditions.push("client_id = ?");
    values.push(filters.clientId);
  }

  const where =
    conditions.length > 0 ? `WHERE ${conditions.join(" AND ")}` : "";
  const limit = filters?.limit ? `LIMIT ${filters.limit}` : "";

  const stmt = db.prepare(
    `SELECT * FROM jobs ${where} ORDER BY created_at DESC ${limit}`,
  );
  return stmt.all(...values) as Job[];
}

export function redactJob(job: Job): Job {
  if (!job.webhook_secret) return job;
  return { ...job, webhook_secret: "<redacted>" };
}

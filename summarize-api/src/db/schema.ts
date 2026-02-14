export const CREATE_JOBS_TABLE = `
CREATE TABLE IF NOT EXISTS jobs (
  id TEXT PRIMARY KEY,
  type TEXT NOT NULL CHECK (type IN ('url', 'upload', 'rss')),
  status TEXT NOT NULL DEFAULT 'queued' CHECK (status IN ('queued', 'running', 'completed', 'failed')),
  source TEXT NOT NULL,
  options TEXT,
  result TEXT,
  error TEXT,
  daemon_job_id TEXT,
  client_id TEXT,
  metadata TEXT,
  created_at TEXT NOT NULL DEFAULT (datetime('now')),
  updated_at TEXT NOT NULL DEFAULT (datetime('now')),
  completed_at TEXT
);
`;

export const CREATE_INDEXES = `
CREATE INDEX IF NOT EXISTS idx_jobs_status ON jobs (status);
CREATE INDEX IF NOT EXISTS idx_jobs_client_id ON jobs (client_id);
CREATE INDEX IF NOT EXISTS idx_jobs_created_at ON jobs (created_at);
`;

export const MIGRATIONS = [CREATE_JOBS_TABLE, CREATE_INDEXES];

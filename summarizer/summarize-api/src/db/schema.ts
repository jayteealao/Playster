// Named, idempotent migrations recorded in `schema_migrations`. Earlier
// versions of this file exported a single `MIGRATIONS` array; that path
// re-ran identical CREATE TABLE IF NOT EXISTS statements on every open.
// Named migrations are required for non-idempotent DDL (ALTER TABLE ADD
// COLUMN is the immediate motivation).

export interface NamedMigration {
  name: string;
  sql: string;
}

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

export const ADD_WEBHOOK_COLUMNS = `
ALTER TABLE jobs ADD COLUMN webhook_url TEXT;
ALTER TABLE jobs ADD COLUMN webhook_secret TEXT;
ALTER TABLE jobs ADD COLUMN client_job_id TEXT;
`;

export const NAMED_MIGRATIONS: NamedMigration[] = [
  { name: "001_create_jobs", sql: CREATE_JOBS_TABLE },
  { name: "002_create_indexes", sql: CREATE_INDEXES },
  { name: "003_add_webhook_columns", sql: ADD_WEBHOOK_COLUMNS },
];

// Retained for backwards compatibility with callers that haven't migrated to
// `NAMED_MIGRATIONS`. Internal callers should prefer the named variant.
export const MIGRATIONS = NAMED_MIGRATIONS.map((m) => m.sql);

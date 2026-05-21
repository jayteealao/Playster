// Named, idempotent migrations recorded in `schema_migrations`. Earlier
// versions of this file exported a single `MIGRATIONS` array; that path
// re-ran identical CREATE TABLE IF NOT EXISTS statements on every open.
// Named migrations are required for non-idempotent DDL (ALTER TABLE ADD
// COLUMN is the immediate motivation).
//
// Rollback policy: SQLite database is container-local + ephemeral on Cloud Run.
// To roll back, revert the container image to a prior tag; new container
// instances will not run migrations beyond the embedded set. There are no
// DOWN migrations.

export interface NamedMigration {
  name: string;
  /** Single SQL string for simple / idempotent DDL (CREATE TABLE IF NOT EXISTS, CREATE INDEX IF NOT EXISTS). */
  sql?: string;
  /**
   * Multiple SQL statements to execute atomically inside a better-sqlite3
   * synchronous transaction. Use this instead of `sql` when the migration
   * contains non-idempotent DDL (e.g. ALTER TABLE ADD COLUMN) so that a
   * crash mid-migration cannot leave the schema partially applied.
   */
  statements?: string[];
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

// These three statements are executed atomically (see NamedMigration.statements).
// A plain db.exec() with multiple ALTERs is NOT wrapped in a transaction by
// better-sqlite3 — a mid-migration crash would leave the schema partially applied
// and cause "duplicate column name" errors on the next startup.
export const ADD_WEBHOOK_COLUMN_STATEMENTS = [
  "ALTER TABLE jobs ADD COLUMN webhook_url TEXT",
  "ALTER TABLE jobs ADD COLUMN webhook_secret TEXT",
  "ALTER TABLE jobs ADD COLUMN client_job_id TEXT",
];

export const NAMED_MIGRATIONS: NamedMigration[] = [
  { name: "001_create_jobs", sql: CREATE_JOBS_TABLE },
  { name: "002_create_indexes", sql: CREATE_INDEXES },
  { name: "003_add_webhook_columns", statements: ADD_WEBHOOK_COLUMN_STATEMENTS },
];

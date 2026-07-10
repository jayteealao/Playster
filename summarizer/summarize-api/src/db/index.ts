import Database from "better-sqlite3";
import { mkdirSync } from "fs";
import { dirname } from "path";
import { NAMED_MIGRATIONS } from "./schema.js";

let db: Database.Database | null = null;

const SCHEMA_MIGRATIONS_DDL = `
CREATE TABLE IF NOT EXISTS schema_migrations (
  name TEXT PRIMARY KEY,
  applied_at TEXT NOT NULL DEFAULT (datetime('now'))
);
`;

export function getDb(): Database.Database {
  if (db) return db;

  const dbPath = process.env.DB_PATH || "./data/jobs.db";

  mkdirSync(dirname(dbPath), { recursive: true });

  db = new Database(dbPath);

  db.pragma("journal_mode = WAL");

  db.exec(SCHEMA_MIGRATIONS_DDL);

  const isApplied = db.prepare(
    "SELECT 1 FROM schema_migrations WHERE name = ?",
  );
  const markApplied = db.prepare(
    "INSERT INTO schema_migrations(name) VALUES(?)",
  );

  // Bootstrap: if the `jobs` table already exists but schema_migrations has no
  // rows (i.e. this is a pre-migration database), mark 001 and 002 as applied
  // so the runner skips them and only considers 003+ as candidates.
  const jobsExists = db
    .prepare("SELECT 1 FROM sqlite_master WHERE type='table' AND name='jobs'")
    .get();
  if (jobsExists && !isApplied.get("001_create_jobs")) {
    markApplied.run("001_create_jobs");
    markApplied.run("002_create_indexes");
  }

  for (const migration of NAMED_MIGRATIONS) {
    if (isApplied.get(migration.name)) continue;
    if (migration.statements && migration.statements.length > 0) {
      // Wrap multi-statement migrations in a synchronous transaction so a
      // crash between statements cannot leave the schema partially applied.
      // On restart the whole migration re-runs (all statements or none).
      db.transaction(() => {
        for (const stmt of migration.statements!) {
          db!.exec(stmt);
        }
      })();
    } else if (migration.sql) {
      db.exec(migration.sql);
    }
    markApplied.run(migration.name);
  }

  return db;
}

export function closeDb(): void {
  if (db) {
    db.close();
    db = null;
  }
}

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

  const isApplied = db.prepare("SELECT 1 FROM schema_migrations WHERE name = ?");
  const markApplied = db.prepare("INSERT INTO schema_migrations(name) VALUES(?)");

  for (const migration of NAMED_MIGRATIONS) {
    if (isApplied.get(migration.name)) continue;
    db.exec(migration.sql);
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

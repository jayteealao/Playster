import Database from "better-sqlite3";
import { mkdirSync } from "fs";
import { dirname } from "path";
import { MIGRATIONS } from "./schema.js";

let db: Database.Database | null = null;

export function getDb(): Database.Database {
  if (db) return db;

  const dbPath = process.env.DB_PATH || "./data/jobs.db";

  // Ensure the directory exists
  mkdirSync(dirname(dbPath), { recursive: true });

  db = new Database(dbPath);

  // Enable WAL mode for better concurrent read performance
  db.pragma("journal_mode = WAL");

  // Run schema migrations
  for (const migration of MIGRATIONS) {
    db.exec(migration);
  }

  return db;
}

export function closeDb(): void {
  if (db) {
    db.close();
    db = null;
  }
}

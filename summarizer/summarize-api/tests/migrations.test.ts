import { describe, it, expect } from "vitest";
import { mkdirSync, rmSync } from "node:fs";
import { join } from "node:path";
import { tmpdir } from "node:os";
import { randomBytes } from "node:crypto";
import { closeDb, getDb } from "../src/db/index.js";

function freshDbPath(): { path: string; cleanup: () => void } {
  const dir = join(
    tmpdir(),
    "summarize-api-migration-test",
    `${Date.now()}-${randomBytes(4).toString("hex")}`,
  );
  mkdirSync(dir, { recursive: true });
  const path = join(dir, "jobs.db");
  return {
    path,
    cleanup: () => {
      closeDb();
      rmSync(dir, { recursive: true, force: true });
    },
  };
}

describe("schema migrations", () => {
  it("opening the DB twice does not re-apply migrations", () => {
    const { path, cleanup } = freshDbPath();
    try {
      closeDb();
      process.env.DB_PATH = path;
      const db1 = getDb();
      const rows1 = db1
        .prepare("SELECT name FROM schema_migrations ORDER BY name")
        .all() as { name: string }[];
      expect(rows1.map((r) => r.name)).toEqual([
        "001_create_jobs",
        "002_create_indexes",
        "003_add_webhook_columns",
      ]);

      closeDb();
      const db2 = getDb();
      const rows2 = db2
        .prepare("SELECT name FROM schema_migrations ORDER BY name")
        .all() as { name: string }[];
      expect(rows2).toHaveLength(3);
    } finally {
      cleanup();
    }
  });

  it("adds webhook_url, webhook_secret, client_job_id columns to jobs", () => {
    const { path, cleanup } = freshDbPath();
    try {
      closeDb();
      process.env.DB_PATH = path;
      const db = getDb();
      const cols = db.prepare("PRAGMA table_info(jobs)").all() as {
        name: string;
      }[];
      const colNames = cols.map((c) => c.name);
      expect(colNames).toContain("webhook_url");
      expect(colNames).toContain("webhook_secret");
      expect(colNames).toContain("client_job_id");
    } finally {
      cleanup();
    }
  });
});

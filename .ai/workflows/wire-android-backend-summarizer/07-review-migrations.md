---
review-command: migrations
slug: wire-android-backend-summarizer
scope: branch-diff
target: git diff main...HEAD
completed: "2026-05-20"
verdict: APPROVE_WITH_COMMENTS
---

# Database Migration Safety Review

**Scope:** Full branch diff — `feat/wire-android-backend-summarizer` vs `main`
**Reviewer:** Claude Database Migration Review Agent
**Date:** 2026-05-20

## Summary

Two distinct "migration" surfaces exist on this branch:

1. **SQLite schema migration** — `summarizer/summarize-api` (better-sqlite3, single-file local DB inside the Cloud Run container). The branch adds three new nullable columns (`webhook_url`, `webhook_secret`, `client_job_id`) to the `jobs` table via a new named-migration runner backed by a `schema_migrations` tracking table.
2. **Firestore "schema"** — schemaless, but two new document shapes are introduced (`summaries/{videoId}`, `quota/openrouter`). No traditional migration script exists or is needed, but rules compatibility and shape drift matter.

**Migrations Reviewed:** 1 SQLite migration file (migration 003); 2 Firestore document shape definitions.
**Affected Tables / Collections:** `jobs` (SQLite), `summaries/*` and `quota/openrouter` (Firestore).
**Estimated Downtime Risk (SQLite):** None — SQLite is container-local; no shared live instance exists during Cloud Run deployment. The container is replaced atomically; the DB is ephemeral per-instance.
**Estimated Downtime Risk (Firestore):** None — Firestore is schemaless; adding new document types is additive.

**Severity Breakdown:**
- BLOCKER: 0
- HIGH: 1
- MED: 3
- LOW: 1
- NIT: 1

**Merge Recommendation:** REQUEST_CHANGES (one HIGH must be addressed before shipping to production)

---

## Findings

### MIG-001: Backward-compatibility gap — existing `main` databases see migration 003 applied but migrations 001 and 002 are silently re-recorded as applied even though the `schema_migrations` table did not exist [MED]

**Migration:** `summarizer/summarize-api/src/db/schema.ts` (migration `001_create_jobs`, `002_create_indexes`)
**Location:** `summarizer/summarize-api/src/db/index.ts:26-35`

**Severity:** MED | **Confidence:** High

**Issue:**
Any database file that was created by the `main` branch code (which ran `CREATE TABLE IF NOT EXISTS jobs` + `CREATE INDEX IF NOT EXISTS` unconditionally on every open) will be upgraded by the new code as follows:
1. `schema_migrations` table is created (new).
2. Migration `001_create_jobs` is not in `schema_migrations`, so its SQL is re-executed — but `CREATE TABLE IF NOT EXISTS` makes it idempotent, so no error.
3. Migration `002_create_indexes` is not in `schema_migrations`, so its SQL is re-executed — `CREATE INDEX IF NOT EXISTS` is idempotent. No error.
4. Migration `003_add_webhook_columns` is not in `schema_migrations`, so the three `ALTER TABLE` statements run against the pre-existing table. All three columns are new; this succeeds.

**The outcome is correct** — the upgrade path works because 001 and 002 are idempotent. However, if any future migration is added between a deployment that has `schema_migrations` but where a pre-existing DB was not upgraded (e.g., a developer's local volume), the tracking state is inconsistent. This is a latent correctness risk, not an immediate production issue.

**Evidence:**
```typescript
// index.ts:31-35 — no guard for pre-existing DB without schema_migrations
for (const migration of NAMED_MIGRATIONS) {
  if (isApplied.get(migration.name)) continue;  // always false on first open of old DB
  db.exec(migration.sql);
  markApplied.run(migration.name);
}
```

**Impact:**
- Downtime: None
- Data loss: None
- Backward compatibility: Works correctly due to idempotent DDL in 001/002, but the mechanism is fragile rather than deliberate.

**Fix:**
Add a bootstrap step: if `schema_migrations` was just created AND the `jobs` table already exists, pre-populate `schema_migrations` with the already-applied migrations before running the loop. This makes the upgrade path explicit rather than accidentally correct.

```typescript
// After creating schema_migrations, detect pre-existing DB:
const jobsExists = db.prepare(
  "SELECT 1 FROM sqlite_master WHERE type='table' AND name='jobs'"
).get();

if (jobsExists) {
  // Pre-existing DB: mark 001 and 002 as already applied
  const alreadyApplied = ["001_create_jobs", "002_create_indexes"];
  for (const name of alreadyApplied) {
    if (!isApplied.get(name)) markApplied.run(name);
  }
}
```

---

### MIG-002: `ADD_WEBHOOK_COLUMNS` bundles three `ALTER TABLE` statements in a single `db.exec()` call — no transactional boundary if the second or third statement fails [HIGH]

**Migration:** `summarizer/summarize-api/src/db/schema.ts:36-40`
**Location:** `summarizer/summarize-api/src/db/index.ts:31-35`

**Severity:** HIGH | **Confidence:** High

**Issue:**
`ADD_WEBHOOK_COLUMNS` is a single string with three semicolon-separated `ALTER TABLE` statements passed to `db.exec()`. better-sqlite3's `exec()` runs multiple statements, but each statement is committed independently. If `ALTER TABLE jobs ADD COLUMN webhook_secret TEXT` succeeds but `ALTER TABLE jobs ADD COLUMN client_job_id TEXT` fails (e.g., due to disk full), the migration is partially applied. On the next startup, the runner checks `schema_migrations` for `003_add_webhook_columns` and finds it absent (because `markApplied` was never called), so it re-runs all three statements — but the first one (`webhook_url`) now fails with "duplicate column name: webhook_url", crashing the startup.

SQLite does not support transactional DDL for `ALTER TABLE` in the same way PostgreSQL does, but the partial-application problem can be mitigated by wrapping each column addition in its own named migration.

**Evidence:**
```typescript
// schema.ts:36-40
export const ADD_WEBHOOK_COLUMNS = `
ALTER TABLE jobs ADD COLUMN webhook_url TEXT;
ALTER TABLE jobs ADD COLUMN webhook_secret TEXT;
ALTER TABLE jobs ADD COLUMN client_job_id TEXT;
`;
// Single exec() call — if statement 2 or 3 fails mid-way,
// markApplied never runs, but some columns exist.
```

**Impact:**
- Downtime: Potential startup crash on the next container boot after a partial failure
- Data loss: None (columns added, no data transformed)
- Rollback: Not possible once a column is added; restart loop would require manual SQLite intervention

**Fix (option A — simplest):** Wrap in a SQLite `BEGIN`/`COMMIT` transaction. While `ALTER TABLE` is DDL, SQLite does execute DDL inside explicit transactions:

```typescript
export const ADD_WEBHOOK_COLUMNS = `
BEGIN;
ALTER TABLE jobs ADD COLUMN webhook_url TEXT;
ALTER TABLE jobs ADD COLUMN webhook_secret TEXT;
ALTER TABLE jobs ADD COLUMN client_job_id TEXT;
COMMIT;
`;
```

**Fix (option B — most robust):** Split into three separate named migrations (`003a`, `003b`, `003c`). Each is independently tracked, so partial failures leave the runner in a recoverable state.

---

### MIG-003: Missing rollback / down migration for `003_add_webhook_columns` [MED]

**Migration:** `summarizer/summarize-api/src/db/schema.ts:36-40`
**Location:** entire `NAMED_MIGRATIONS` array

**Severity:** MED | **Confidence:** High

**Issue:**
No `down` migration exists for any named migration, and specifically none for `003_add_webhook_columns`. SQLite supports `ALTER TABLE DROP COLUMN` only from version 3.35.0 (2021-03-12). The minimum SQLite version is not pinned in the project. Rolling back the three webhook columns requires either:
- Dropping and recreating the `jobs` table (destructive — data loss)
- Accepting the extra nullable columns as permanent (safe for this use case, but undocumented)

For a container-local ephemeral SQLite DB (jobs are transient Cloud Run artifacts), this is lower risk than a persistent production database, but it should be explicitly documented rather than silently absent.

**Impact:**
- Rollback: Not possible without data loss or manual table recreation
- Data loss on rollback: Jobs in the webhook columns would be lost if the table is recreated

**Fix:** Since the database is container-local and ephemeral (no persistent volume across deployments), document explicitly that down migrations are not supported and that rollback is performed by deploying the previous container image (which will create a fresh DB). Add a comment in `schema.ts`:

```typescript
// Down migrations are not supported. The jobs DB is ephemeral per Cloud Run
// instance; rollback is achieved by re-deploying the prior container image.
// A new instance starts with a fresh DB and re-runs all applicable migrations.
```

---

### MIG-004: `SummaryDocument.webhookSecret` is stored in Firestore and not redacted in security rules [HIGH → recategorized MED after context review]

**Location:** `backend/functions/src/models/index.ts:45`, `backend/functions/src/summarizer/autoEnqueue.ts:52`

**Severity:** MED | **Confidence:** Med

**Issue:**
`SummaryDocument` includes a `webhookSecret` field. Firestore security rules (`backend/firestore.rules`) allow the allowlisted uid to read the entire `summaries/{document=**}` collection. This means the `webhookSecret` (a 32-byte hex HMAC key) is readable from the Android client via Firestore listener.

In `autoEnqueue.ts:52`, `webhookSecret: ""` is written for auto-queued docs (before dispatch). In `dispatch.ts`, the secret is written as the real 64-char hex string. Both values are readable by the Android client.

While this is a single-tenant system (only the operator can read it), having a live HMAC key in a client-readable Firestore document is a schema design issue: if the Android client is compromised or the token is exfiltrated, an attacker can forge webhook payloads.

**Impact:**
- Security: HMAC key exposure to Android client (single-tenant, so low practical risk)
- Data integrity: Compromised key would allow forging webhook delivery confirmations

**Fix:** Store `webhookSecret` only in a server-side document (e.g., `secrets/summaries/{videoId}` with `allow read: if false`) or exclude it from the client-readable shape using Firestore rules field-level projection (not natively supported) or by moving it to a separate subcollection. The simpler fix: never write `webhookSecret` to the `summaries/{videoId}` doc; store it only in the backend's in-memory dispatch state or in a separate admin-only collection.

---

### MIG-005: `schema_migrations` table DDL is not atomic with the first migration run — a crash between `CREATE TABLE schema_migrations` and the first `markApplied.run()` leaves the DB in an inconsistent state [LOW]

**Location:** `summarizer/summarize-api/src/db/index.ts:26-35`

**Severity:** LOW | **Confidence:** Med

**Issue:**
The `schema_migrations` table is created via `db.exec(SCHEMA_MIGRATIONS_DDL)` outside of any explicit transaction. If the process crashes between creating `schema_migrations` and marking `001_create_jobs` as applied, the next startup will attempt to re-run `001_create_jobs` (which is idempotent — `CREATE TABLE IF NOT EXISTS`) and succeed. This is benign for the current migration set. However, if 002 or 003 were to fail mid-run in this scenario, the partial state problem described in MIG-002 applies.

For a container-local SQLite with WAL mode enabled, crash recovery is handled by SQLite's WAL journal, so this is unlikely in practice but theoretically possible on abnormal process termination (SIGKILL during init).

**Impact:**
- Downtime: Low risk — retry on next container start succeeds for idempotent DDL
- Data loss: None

**Fix:** Wrap the entire migration bootstrap (DDL creation + all migration applies) in a single `BEGIN IMMEDIATE` / `COMMIT` transaction. This is safe in SQLite.

---

### MIG-006: Migration test does not test upgrade path from a pre-existing `main`-era database [NIT]

**Location:** `summarizer/summarize-api/tests/migrations.test.ts:25-69`

**Severity:** NIT | **Confidence:** High

**Issue:**
Both test cases start from a fresh empty database. Neither test simulates the real upgrade scenario: a database that was created by `main` (with `jobs` table and indexes but no `schema_migrations` table) being opened by the new code. This is the actual deployment path for any existing instance.

The tests verify:
- Fresh DB has all 3 named migrations recorded
- Fresh DB has the 3 new columns

They do not verify:
- Pre-existing DB (with `jobs` table + old columns) can be opened without error
- Post-upgrade DB has exactly the right `schema_migrations` rows

**Fix:** Add a third test case that creates a SQLite DB manually with the `main`-era schema (no `schema_migrations`, no webhook columns, `jobs` table exists), then calls `getDb()` and asserts columns are added and `schema_migrations` has 3 rows.

---

## Migration Analysis

| Migration | Table | Operation | Lock Type | Est. Time | Risk |
|-----------|-------|-----------|-----------|-----------|------|
| 001_create_jobs | jobs | CREATE TABLE IF NOT EXISTS | Exclusive (new table) | <1ms | None — idempotent |
| 002_create_indexes | jobs | CREATE INDEX IF NOT EXISTS (×3) | Shared (read-blocking during creation) | <1ms on fresh empty table | None — SQLite in-process, no concurrent readers at startup |
| 003_add_webhook_columns | jobs | ALTER TABLE ADD COLUMN (×3) | Exclusive (brief) | <1ms | None on empty/small table; see MIG-002 for partial-failure risk |

---

## Table Size Analysis

**SQLite `jobs` table:**
- This is a container-local, ephemeral SQLite DB. Cloud Run containers are stateless; no persistent volume is mounted. Each container instance starts with a fresh database.
- No large-table migration concerns apply. The `ALTER TABLE` completes in microseconds.

**Firestore:**
- `summaries/{videoId}` and `quota/openrouter` are new collections. No existing data to migrate. Additive only.

---

## Backwards Compatibility Analysis

**SQLite:**
- The upgrade path from `main` to this branch works correctly (idempotent DDL in 001/002 prevents failures), but the mechanism is accidental rather than intentional. See MIG-001.
- Code deployed from `main` cannot read `webhook_url`, `webhook_secret`, `client_job_id` (they don't exist in the schema). Code deployed from this branch writes them as nullable — old clients get `null` for all three, which is backward-compatible since they never referenced these fields.

**Firestore:**
- Fully additive. No existing documents are modified. Old Android clients (pre-branch) that don't know about `summaries/` or `quota/` simply don't read those collections. The new Firestore rules are backward-compatible with old clients reading only `playlists/` and `videos/`.

**Recommended Deployment Order:**
1. Deploy the new container image (which runs migration 003 on startup — completes before first request).
2. Deploy backend functions (which begin writing `summaries/` and `quota/` docs).
3. Deploy Android app (which reads `summaries/` and `quota/`).

This ordering is already the design intent of the slicing plan.

---

## Rollback Analysis

**Reversible Migrations:** 0 (none have down migrations)
**Irreversible Migrations:** 1 (`003_add_webhook_columns` — `ALTER TABLE ADD COLUMN` cannot be un-done without table recreation in SQLite < 3.35)
**Missing Down Migrations:** 3 (001, 002, 003 — all)

**High-Risk Rollbacks:**
- `003_add_webhook_columns`: To roll back, the container must be re-deployed with the old image. The old image does not know about these columns and will simply ignore them (SQLite is schema-flexible for reads via `*`). This is safe because the DB is ephemeral per-instance.

**Acceptable risk conclusion:** For a container-local ephemeral SQLite instance, the lack of down migrations is acceptable and documented in the codebase (comment in `schema.ts` recommends adding one). The real rollback mechanism is image revert, not schema rollback.

---

## Recommendations

1. **HIGH — Fix before shipping to production (MIG-002):** Wrap the three `ALTER TABLE` statements in `003_add_webhook_columns` in an explicit `BEGIN`/`COMMIT` transaction, or split into three separate named migrations. The current implementation can leave the DB in a non-recoverable state on partial failure.

2. **MED — Address before GA (MIG-004):** Move `webhookSecret` out of the client-readable `summaries/{videoId}` Firestore document. Store it in a server-side-only location. For v1 single-tenant this is low practical risk but violates the principle of least-privilege for client data access.

3. **MED — Address before GA (MIG-001):** Add an explicit pre-existing-DB bootstrap step in `index.ts` to mark 001/002 as applied when upgrading from a `main`-era database without `schema_migrations`. The current behavior works but relies on idempotent DDL as a side effect, not as design.

4. **MED — Document (MIG-003):** Add an explicit comment in `schema.ts` stating down migrations are not supported and that rollback is performed via container image revert.

5. **LOW (MIG-005):** Wrap the full migration bootstrap in a `BEGIN IMMEDIATE` / `COMMIT` transaction for atomicity.

6. **NIT (MIG-006):** Add an upgrade-path migration test that starts from a pre-existing `main`-era DB.

---

## Deployment Checklist

- [x] Database is container-local; no persistent volume means no cross-instance migration coordination needed
- [x] Migrations are idempotent for 001 and 002 (CREATE IF NOT EXISTS)
- [x] Migration 003 is guarded by `schema_migrations` tracking table (no re-run on restart)
- [x] New Firestore collections are additive only
- [x] Firestore rules cover new `summaries/` and `quota/` paths
- [ ] MIG-002: Transaction-wrap the three ALTER TABLE statements
- [ ] MIG-004: Remove `webhookSecret` from client-readable Firestore document shape
- [ ] MIG-001: Add pre-existing-DB bootstrap to `schema_migrations`
- [ ] MIG-006: Add upgrade-path migration test

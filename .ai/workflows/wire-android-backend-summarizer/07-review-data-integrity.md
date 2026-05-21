---
review-command: data-integrity
slug: wire-android-backend-summarizer
scope: diff
target: git diff main...HEAD
completed: 2026-05-20
---

# Data Integrity Review — wire-android-backend-summarizer

**Scope:** Full branch diff vs `main` (232 files changed, +16 714 / -1 212 lines)
**Reviewer:** Data Integrity Review Agent
**Date:** 2026-05-20

## Summary

The implementation demonstrates strong data-integrity discipline in most paths.
The quota system uses Firestore transactions correctly. The webhook handler is
idempotent and enforces terminal-state deduplication. The `dispatchSummary`
transaction guards the pending-state reservation against concurrent callers.
The lock document protects the dispatcher cron from overlap.

Three findings warrant attention before ship: a non-atomic multi-statement SQL
migration (HIGH), a TOCTOU window in `dispatchSummary` between the idempotency
pre-check and the quota reservation (MED), and a missing `webhookSecret`
in queued-docs that can cause a no-op update if the dispatcher promotes a doc
before the secret is populated (MED). One LOW and one NIT complete the list.

**Severity Breakdown:**
- BLOCKER: 0
- HIGH: 1
- MED: 2
- LOW: 1
- NIT: 1

**Merge Recommendation:** REQUEST_CHANGES

---

## Findings

### DI-01: Non-atomic webhook-columns migration — multi-statement ALTER in one `db.exec` call [HIGH]

**Location:** `summarizer/summarize-api/src/db/schema.ts:36-40`
**Confidence:** High

**Issue:**
`ADD_WEBHOOK_COLUMNS` concatenates three `ALTER TABLE` DDL statements into a
single string and executes them via `db.exec`. In SQLite/better-sqlite3,
`db.exec` wraps each *semicolon-separated statement* in an implicit transaction
unless you wrap the whole string yourself. If the process crashes between the
second and third `ALTER`, the `schema_migrations` row for
`003_add_webhook_columns` has NOT yet been written (the mark happens after
`db.exec` succeeds), so re-opening the database will attempt to re-apply
the migration. Two of the three columns then already exist and
`ALTER TABLE ADD COLUMN` will throw `table jobs already has column webhook_url`
— making the database permanently unrecoverable without manual intervention.

**Evidence:**
```typescript
// summarizer/summarize-api/src/db/schema.ts:36
export const ADD_WEBHOOK_COLUMNS = `
ALTER TABLE jobs ADD COLUMN webhook_url TEXT;
ALTER TABLE jobs ADD COLUMN webhook_secret TEXT;
ALTER TABLE jobs ADD COLUMN client_job_id TEXT;
`;
// db/index.ts:33
db.exec(migration.sql);
markApplied.run(migration.name);  // ← written AFTER exec; crash between the
                                  //   second and third ALTER leaves schema in
                                  //   partial state but migration not marked
```

**Impact:**
A crash or OOM between the second and third `ALTER` leaves `client_job_id`
column absent but the migration un-marked. Next startup re-runs the full
migration and crashes on duplicate-column errors, rendering the DB unreachable.

**Scenario:**
1. Process starts cold on Cloud Run with an existing DB.
2. Migration 003 is applied: exec fires, `webhook_url` and `webhook_secret`
   columns added.
3. OOM kill occurs before `client_job_id` ADD and before `markApplied`.
4. Process restarts; migration 003 not marked ⇒ re-runs ⇒ `ALTER TABLE ADD
   COLUMN webhook_url` ⇒ SQLite error "duplicate column name" ⇒ unhandled
   throw ⇒ server never starts.

**Fix:**
Wrap each DDL statement separately as its own named migration, or wrap the
three ALTERs in an explicit `BEGIN … COMMIT` transaction, and use `IF NOT
EXISTS` guards (SQLite does not support these natively on `ADD COLUMN`, so
the separate-migrations approach is cleaner):

```typescript
// Split 003 into three idempotent named migrations
// or wrap in a transaction:
export const ADD_WEBHOOK_COLUMNS = `
BEGIN;
ALTER TABLE jobs ADD COLUMN webhook_url TEXT;
ALTER TABLE jobs ADD COLUMN webhook_secret TEXT;
ALTER TABLE jobs ADD COLUMN client_job_id TEXT;
COMMIT;
`;
// OR better: use db.transaction() from better-sqlite3:
// db.transaction(() => {
//   db.exec("ALTER TABLE jobs ADD COLUMN webhook_url TEXT");
//   db.exec("ALTER TABLE jobs ADD COLUMN webhook_secret TEXT");
//   db.exec("ALTER TABLE jobs ADD COLUMN client_job_id TEXT");
// })();
```

---

### DI-02: TOCTOU window between idempotency pre-check and quota reservation in `dispatchSummary` [MED]

**Location:** `backend/functions/src/summarizer/dispatch.ts:88-98`
**Confidence:** High

**Issue:**
`dispatchSummary` performs a non-transactional read of `summaries/{videoId}`
to decide whether to short-circuit (idempotency check), then — if a new
dispatch is warranted — calls `reserveOpenRouterQuotaSlot()` and subsequently
wraps the doc write in a separate transaction. Between the initial read and the
quota reservation, a concurrent caller (e.g., the scheduler `drainSummaryQueue`
firing at the same time as a manual `requestVideoSummary`) can observe the same
absent/failed doc, both pass the check, both reserve a quota slot, and both
proceed to write `summaries/{videoId}` with `status=pending`. The second caller
wins the Firestore `tx.set`, overwriting the first caller's `webhookSecret`,
so the first dispatch's webhook can never be verified.

**Evidence:**
```typescript
// dispatch.ts:88-118
const existing = await summaryRef.get();          // ← non-transactional read
if (existing.exists) {
  const data = existing.data() as ...;
  if (data?.status && IN_FLIGHT_STATUSES.includes(data.status)) {
    return { summaryId: videoId };
  }
}
// ← WINDOW: concurrent caller also reads here and falls through
await reserveOpenRouterQuotaSlot();              // ← both callers reserve
const webhookSecret = randomBytes(32).toString("hex");
await db.runTransaction(async (tx) => {
  const snap = await tx.get(summaryRef);         // ← inner transaction
  tx.set(summaryRef, next);                      // ← second caller overwrites
});
```

The inner transaction re-reads the doc but performs an unconditional `tx.set`,
not a `tx.create` — it silently overwrites a concurrent caller's doc.

**Impact:**
Two quota slots consumed for one video. The first summarizer job's webhook
will fail HMAC verification (wrong secret stored), leaving the Firestore doc
stuck at `pending` → eventually swept to `failed-transient` after 1h.

**Fix:**
Move the idempotency check inside the transaction and use an `if (!snap.exists
|| terminal)` guard before setting, or use
`tx.create(summaryRef, next)` (throws if doc already exists, which callers
should catch and treat as an idempotency hit):

```typescript
let alreadyExists = false;
await db.runTransaction(async (tx) => {
  const snap = await tx.get(summaryRef);
  const data = snap.exists
    ? (snap.data() as Partial<SummaryDocument> | undefined)
    : undefined;
  if (data?.status && IN_FLIGHT_STATUSES.includes(data.status)) {
    alreadyExists = true;
    return;
  }
  tx.set(summaryRef, { ...next });
});
if (alreadyExists) return { summaryId: videoId };
// Only reserve quota after confirming no in-flight doc exists.
await reserveOpenRouterQuotaSlot();
```

---

### DI-03: Queued docs written by `enqueueAutoSummary` have `webhookSecret: ""` — dispatcher promotes them before a secret is set [MED]

**Location:** `backend/functions/src/summarizer/autoEnqueue.ts:48-54`
**Confidence:** High

**Issue:**
When auto-enqueue creates a `summaries/{videoId}` doc with `status=queued`,
it stores `webhookSecret: ""` because no secret is needed until dispatch.
When `dispatchSummary` later promotes a queued doc, it generates a fresh
`webhookSecret` and overwrites the entire doc via `tx.set(summaryRef, next)`.
The problem is that `dispatchSummary` currently does NOT check whether the
incoming doc is in `queued` state before overwriting — the inner transaction's
condition checks `IN_FLIGHT_STATUSES` (which includes `"queued"`), so a queued
doc causes an early return with `alreadyExists` logic. Combined with the
DI-02 TOCTOU, a queued doc can be overwritten with `webhookSecret: ""` still
set if a crash occurs between the outer read and the inner transaction.

More concretely: if `dispatchSummary` crashes after the `reserveOpenRouterQuotaSlot`
call but before the transaction commits, the doc remains in `queued` state
with `webhookSecret: ""`. The quota slot is not refunded (no `releaseOpenRouterQuotaSlot`
in the outer code path because the crash happens pre-transaction). Next
dispatcher run re-reads the doc as `queued`, falls through to dispatch again
(correct), but must generate a new secret — which it does. So this is actually
survivable. However, the *real* risk is the permanent empty-secret state:
`SummaryDocument` declares `webhookSecret: string` (non-optional) but
`enqueueAutoSummary` stores `""`. If the webhook handler ever processes an event
for a doc that was never promoted (e.g., a rogue delivery), it reads
`secret = summary?.webhookSecret ?? ""` and the `verifySignature` function
returns `false` when `!secret`. That guard exists and is correct. But a doc
with `webhookSecret: ""` that somehow transitions to `running` (e.g., via a
direct Firestore Admin write for debugging) would silently fail all webhook
verifications with no error surfaced to the caller.

**Evidence:**
```typescript
// autoEnqueue.ts:48-54
const doc: SummaryDocument = {
  videoId: group[i],
  status: "queued",
  model: "free",
  webhookSecret: "",   // ← empty string; non-optional field forced to ""
  requestedAt: admin.firestore.FieldValue.serverTimestamp(),
};
```

```typescript
// webhook.ts:139
const secret = summary?.webhookSecret ?? "";
if (!verifySignature(sig.v1, raw, sig.t, secret)) { ... }
// verifySignature line 70: if (!secret) return false;  ← guard exists but silent
```

**Impact:**
Low probability of direct data corruption in normal flow, but the empty
`webhookSecret` violates the type contract. If a doc in `queued` state ever
receives a webhook (malformed or replayed delivery), it silently rejects it
with a 401, leaving no audit trail of why. The more structural risk is that
`SummaryDocument` should not allow `webhookSecret: ""` for queued docs —
the type system permits an invariant violation.

**Fix:**
Make `webhookSecret` optional on the document type and only populate it when
dispatching, or use a sentinel value that is clearly distinct from an empty
secret:

```typescript
// models/index.ts — make webhookSecret optional
export interface SummaryDocument {
  ...
  webhookSecret?: string;  // absent on queued docs; set on pending/running
  ...
}

// autoEnqueue.ts — omit webhookSecret for queued docs
const doc: Omit<SummaryDocument, "webhookSecret"> & { status: "queued" } = {
  videoId: group[i],
  status: "queued",
  model: "free",
  requestedAt: admin.firestore.FieldValue.serverTimestamp(),
};
```

---

### DI-04: `locks/summaryDispatcher` collection is not covered by Firestore security rules [LOW]

**Location:** `backend/firestore.rules:1-18`
**Confidence:** High

**Issue:**
The `backend/firestore.rules` file defines explicit allow/deny rules for
`playlists/`, `videos/`, `sync_state/`, `tokens/`, `summaries/`, and
`quota/`. The `locks/` collection (used by `summaryDispatcher` for its
per-run lock) is not listed and falls through to the catch-all
`/{document=**} { allow read, write: if false; }`. This means:

1. The Admin SDK writes to `locks/summaryDispatcher` succeed (Admin SDK bypasses security rules — correct).
2. Any client-authenticated read of `locks/summaryDispatcher` is denied (also correct).
3. However, the *intent* is unclear: if an operator ever opens Firebase Console
   and tries to read the lock doc for debugging, they will get a rules-denied
   error even with the allowlisted uid. This creates operational confusion.

This is not a correctness bug but an undocumented gap that can mislead
operators and future contributors into thinking the lock doc is inaccessible.

**Fix:**
Add an explicit rule permitting reads by the allowlisted uid:

```
match /locks/{document=**} { allow read: if isAllowlisted(); allow write: if false; }
```

---

### DI-05: `PlaylistDoc` and `VideoDoc` Android DTOs omit `lastSyncedAt` — silent data loss on Firestore deserialization [NIT]

**Location:**
- `android/app/src/main/java/com/github/jayteealao/playster/data/firestore/PlaylistDoc.kt`
- `android/app/src/main/java/com/github/jayteealao/playster/data/firestore/VideoDoc.kt`
**Confidence:** High

**Issue:**
The server `PlaylistDocument` (and `VideoDocument`) models include
`lastSyncedAt: FieldValue | Date` as a field that is written on every sync.
The Android `PlaylistDoc` and `VideoDoc` DTOs do not declare `lastSyncedAt`.
When `toObject(PlaylistDoc::class.java)` deserializes a Firestore document,
the Firestore SDK silently drops unknown fields — so `lastSyncedAt` is consumed
and discarded without error.

Currently this is a NIT because the Android UI does not display `lastSyncedAt`.
But if future code adds a "Last synced" label or a freshness check, the field
will appear absent on all existing DTO instances and the feature will silently
render stale or absent data.

Additionally, `VideoDocument` does not expose `lastSyncedAt` and the server
model does not define it on `VideoDocument` either, creating a documentation
gap about which docs carry sync timestamps.

**Fix:**
Add the field to both DTOs with a nullable default, or document explicitly that
`lastSyncedAt` is intentionally not surfaced on Android. At minimum, add a
code comment:

```kotlin
// PlaylistDoc.kt
// NOTE: `lastSyncedAt` is written by the backend but not projected here;
// it is backend-internal and not needed by the UI.
data class PlaylistDoc(...)
```

---

## Critical Invariants Check

| Invariant | Status |
|-----------|--------|
| `quota/openrouter` daily count never exceeds cap | ✅ Transactional reserve; pessimistic increment; best-effort rollback on dispatch failure |
| `quota/openrouter` per-minute sliding window is authoritative | ✅ Trimmed inside the same transaction; timestamps appended atomically |
| `summaries/{videoId}` terminal state is never overwritten by a subsequent webhook delivery | ✅ `processSummaryWebhook` checks `currentStatus` and returns 204/200 without writing |
| Only valid HMAC-signed webhooks update Firestore | ✅ Secret fetched from doc; `timingSafeEqual` compare; replay window enforced |
| `summaries/{videoId}` transitions are guarded against concurrent dispatch | ⚠️ Partial — inner transaction reads doc but does unconditional `set` (DI-02) |
| `webhookSecret` is always non-empty when a job is in `pending`/`running` state | ⚠️ Partial — queued docs written with `webhookSecret: ""` until promoted (DI-03) |
| SQL migration is atomic | ❌ Three `ALTER TABLE` statements in one `db.exec` without a wrapping transaction (DI-01) |
| `locks/summaryDispatcher` denies non-Admin access | ✅ Catch-all rule denies client writes; Admin SDK bypasses rules |

---

## Transaction Analysis

| Operation | Location | Has Transaction? | Risk Level |
|-----------|----------|------------------|------------|
| Quota reserve (daily + window) | `quota.ts:60-88` | ✅ Yes — Firestore transaction | OK |
| Quota release (rollback) | `quota.ts:121-156` | ✅ Yes — Firestore transaction | OK |
| Idempotency check + summary doc write | `dispatch.ts:88-118` | ⚠️ Partial — check is outside tx | MED (DI-02) |
| Webhook Firestore update | `webhook.ts:188-189` | ❌ No tx — `set({ merge: true })` | Acceptable — single doc; idempotent |
| Auto-enqueue batch | `autoEnqueue.ts:40-58` | ✅ Batch (not tx, but atomic per group) | OK |
| Dispatcher lock acquire | `dispatcher.ts:16-29` | ✅ Yes — Firestore transaction | OK |
| SQL migration 003 (3 ALTERs) | `schema.ts:36-40` | ❌ No wrapping tx | HIGH (DI-01) |

---

## Recommendations

### Immediate (HIGH)
1. **DI-01**: Wrap the three `ALTER TABLE` statements in a `better-sqlite3`
   synchronous transaction, or split them into three separate named migrations,
   to prevent partial-migration failures from rendering the database
   permanently unrecoverable.

### Short-term (MED)
2. **DI-02**: Move the idempotency read inside the Firestore transaction in
   `dispatchSummary`, eliminating the TOCTOU window that allows two concurrent
   callers to both reserve quota and both write the doc. Use a conditional
   create or check-then-set inside the transaction.

3. **DI-03**: Make `webhookSecret` optional in `SummaryDocument` and omit it
   from auto-queued docs. This preserves the type invariant that only
   dispatched-or-dispatching docs carry a secret.

### Low-priority (LOW / NIT)
4. **DI-04**: Add an explicit Firestore rule for `locks/{document=**}` to
   permit reads by the allowlisted uid, reducing operational confusion in the
   Firebase Console.

5. **DI-05**: Add a comment (or suppress the unused-field warning) in
   `PlaylistDoc.kt` and `VideoDoc.kt` explaining why `lastSyncedAt` is
   intentionally absent, so future contributors do not attempt to surface it
   and encounter silent deserialization drops.

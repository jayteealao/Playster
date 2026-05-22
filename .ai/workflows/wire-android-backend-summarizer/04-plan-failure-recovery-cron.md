---
schema: sdlc/v1
type: plan
slug: wire-android-backend-summarizer
slice-slug: failure-recovery-cron
status: complete
stage-number: 4
created-at: "2026-05-22T21:01:56Z"
updated-at: "2026-05-22T21:01:56Z"
metric-files-to-touch: 8
metric-step-count: 22
has-blockers: false
revision-count: 0
extension-round: 1
source: from-review
source-ref: 07-review.md
tags: [backend, firebase, cron, reliability, summarizer, openrouter]
stack-source: confirmed
refs:
  index: 00-index.md
  plan-index: 04-plan.md
  slice-def: 03-slice-failure-recovery-cron.md
  siblings:
    - 04-plan-auth-and-android-firebase.md
    - 04-plan-summarizer-container.md
    - 04-plan-summary-orchestration.md
    - 04-plan-summary-ui.md
  implement: 05-implement-failure-recovery-cron.md
implement-status: complete
implement-commit-sha: "3c9a464a"
implement-branch: "feat/failure-recovery-cron"
verify-status: complete
verify-result: pass
verify-convergence: not-needed
next-command: wf-review
next-invocation: "/wf review wire-android-backend-summarizer failure-recovery-cron"
---

# Plan: failure-recovery-cron (summarySweeper + summaryRetryCron)

## Plan Overview

Two scheduled functions land in `backend/functions/src/summarizer/`, both following the existing `dispatcher-cron.ts` pattern verbatim:

1. **`summarySweeper`** — hourly. Scans `summaries` for `status == "running" && requestedAt < (now - STUCK_TIMEOUT_MS)`. Flips each to `status: "failed-transient"` with `errorCode: "stuck_running_timeout"` via a **per-doc transaction** that re-reads status inside the tx (status-gated write). No outbound HTTP, no quota interaction.
2. **`summaryRetryCron`** — daily at 04:00 UTC. Queries `summaries where status == "failed-transient"` (capped at `DISPATCHER_BATCH_SIZE`). Calls the existing `dispatchSummary(videoId, "free")` helper via `Promise.allSettled` — full fan-out, mirroring `drainSummaryQueue`. `dispatchSummary` already encapsulates quota reservation, webhook-secret rotation (`webhook_secrets/{videoId}`), and the state transition `failed-transient → pending → running`. Distributed lock at `locks/summaryRetry` with the existing `DISPATCHER_LOCK_TTL_MS` (240s).

After this slice ships, AC-12 and AC-13 from `02-shape.md` are covered, plus three new ACs that pin down idempotency, quota awareness, and stale-lock TTL semantics. The slice is on a **parallel branch** and does NOT gate v1.0 handoff/ship of the original four slices.

Five sequential phases produce, in order: constants + sweeper module → sweeper tests → retry module → retry tests → exports + lint/build. Each phase ends at a git-commit boundary.

## Current State

**Existing primitives this slice reuses (no new dependencies):**

- [`backend/functions/src/summarizer/dispatch.ts:71`](backend/functions/src/summarizer/dispatch.ts) exports `dispatchSummary(videoId, model, opts?)`. Its transactional idempotency check at line 100 reads `summaries/{videoId}` and proceeds only when `status` is NOT in `NON_REDISPATCHABLE_STATUSES` (`queued`/`pending`/`running`/`completed`). Critical: `failed-transient` is intentionally **not** in that list, so a retry-cron call on a failed-transient doc legally flips it back through `pending → running`, rotates the `webhook_secrets/{videoId}` doc, and POSTs to the summarizer. The webhook-secret rotation is essential because slice-3's post-review fix ([f94a7691](https://github.com/local/commit/f94a7691)) moved `webhookSecret` out of `summaries/` into the server-only `webhook_secrets/` collection — retry must not write a stale secret.
- [`backend/functions/src/summarizer/dispatcher-cron.ts:14`](backend/functions/src/summarizer/dispatcher-cron.ts) — `acquireDispatcherLock()` / `releaseDispatcherLock()` is the lock pattern this slice clones (different doc path, identical mechanics).
- [`backend/functions/src/summarizer/constants.ts`](backend/functions/src/summarizer/constants.ts) — already exports `TERMINAL_STATUSES`, `DISPATCHER_LOCK_TTL_MS`, `DISPATCHER_BATCH_SIZE`. This slice appends `STUCK_TIMEOUT_MS` and `RETRY_LOCK_DOC_PATH` / `SWEEPER_LOCK_DOC_PATH` constants.
- [`backend/functions/src/summarizer/quota.ts`](backend/functions/src/summarizer/quota.ts) — `reserveOpenRouterQuotaSlot` / `releaseOpenRouterQuotaSlot` / `getQuotaBudget`. Retry inherits these for free through `dispatchSummary`; this slice writes no direct quota code.
- [`backend/functions/src/summarizer/secrets.ts`](backend/functions/src/summarizer/secrets.ts) — `summarizerSecrets` is the secret bundle attached to the retry cron (matches `summaryDispatcher`).
- [`backend/functions/src/index.ts:23`](backend/functions/src/index.ts) — registration pattern: `export { summaryDispatcher } from "./summarizer/dispatcher-cron";`. Two new export lines suffice.
- [`backend/functions/tests/dispatcher.test.ts`](backend/functions/tests/dispatcher.test.ts) — the stale-lock TTL test at line 124 (R-11) is the template for AC-16 here; the per-minute-cap test at line 65 demonstrates the quota-pre-seed pattern used for AC-15.
- [`backend/functions/tests/helpers/admin.ts`](backend/functions/tests/helpers/admin.ts) — `initAdminEmulator()` + `clearFirestore()` are the test bootstrap; identical use in both new test files.

**Firestore rules:** [`backend/firestore.rules:33`](backend/firestore.rules) already denies client access to `webhook_secrets/{document=**}` and `locks/` is not exposed at all (the catch-all denies anything not explicitly matched). No rules changes required — Admin SDK bypasses rules.

**Branch state:** `feat/wire-android-backend-summarizer` (review-stage complete on the parent v1.0 work). This slice opens its own parallel branch `feat/failure-recovery-cron` based off `main` (post-v1.0-merge) per PO's extend-stage decision. **Open coordination point** — if the parent branch hasn't merged to `main` yet at implement time, the parallel branch will need to rebase on `feat/wire-android-backend-summarizer` to pick up the post-R-fix `webhook_secrets/{videoId}` migration. See `## Risks`.

## Reuse Opportunities

- `path/backend/functions/src/summarizer/dispatch.ts` → `dispatchSummary(videoId, "free")` — **reuse as-is.** Already encapsulates the entire failed-transient → running state machine, including quota reservation and webhook-secret rotation. Retry cron calls this directly; no per-call wrapping needed beyond a try/catch that distinguishes `resource-exhausted` HttpsError (logged + continue) from other errors (logged as warn + continue).
- `path/backend/functions/src/summarizer/dispatcher-cron.ts` → `acquireDispatcherLock()` / `releaseDispatcherLock()` — **reuse with modification.** The pattern is correct; only the doc path differs. Plan: extract the lock helper into a private factory inside each new cron file rather than touching `dispatcher-cron.ts` (avoid expanding the slice's blast radius). Two near-identical 12-line functions in `sweeper.ts` and `retry.ts` is cleaner than a premature shared abstraction.
- `path/backend/functions/src/summarizer/dispatcher-cron.ts` → `drainSummaryQueue` Promise.allSettled fan-out + `HttpsError("resource-exhausted")` short-circuit — **reuse pattern.** Retry's main loop mirrors lines 71–97 verbatim with the `status == "failed-transient"` selector.
- `path/backend/functions/src/summarizer/constants.ts` — **extend.** Append three constants (`STUCK_TIMEOUT_MS`, `SWEEPER_LOCK_DOC_PATH`, `RETRY_LOCK_DOC_PATH`); do not introduce a separate constants file.
- `path/backend/functions/tests/dispatcher.test.ts` (lines 55–63, 124–144) — **reuse pattern.** The stale-lock TTL test and lock acquire/release semantics test pattern copy verbatim into `sweeper.test.ts` and `retry.test.ts`. The `counting2xxFetch()` helper at line 29 transfers to `retry.test.ts` for stubbing the summarizer POST (retry calls `dispatchSummary` which mutates `globalThis.fetch` via the existing module-level path; pattern at line 77).
- `path/backend/functions/tests/helpers/admin.ts` — **reuse as-is.** Both new test files import `initAdminEmulator` and `clearFirestore`.
- `path/backend/functions/tests/dispatch.test.ts` — **reuse pattern** for failed-transient → pending transition assertions (this file already exists; reading it during implement gives the exact pattern for asserting doc state post-dispatchSummary call).

## Likely Files / Areas to Touch

**New files (4):**

- `backend/functions/src/summarizer/sweeper.ts` — `summarySweeper` `onSchedule` cron + `acquireSweeperLock` / `releaseSweeperLock` / `sweepStuckRunning()` (testable helper).
- `backend/functions/src/summarizer/retry.ts` — `summaryRetryCron` `onSchedule` cron + `acquireRetryLock` / `releaseRetryLock` / `retryFailedTransient()` (testable helper).
- `backend/functions/tests/sweeper.test.ts` — vitest emulator tests for AC-12, AC-14, AC-16 (sweeper side), plus the per-doc-idempotency-tx behaviour.
- `backend/functions/tests/retry.test.ts` — vitest emulator tests for AC-13, AC-15, AC-16 (retry side), plus batch-size cap behaviour.

**Modified files (2):**

- `backend/functions/src/summarizer/constants.ts` — append `STUCK_TIMEOUT_MS = 60 * 60 * 1000`, `SWEEPER_LOCK_DOC_PATH = "locks/summarySweeper"`, `RETRY_LOCK_DOC_PATH = "locks/summaryRetry"`. Update the file's docblock to mention these alongside the existing constants.
- `backend/functions/src/index.ts` — add two export lines after the existing summarizer block at line 23: `export { summarySweeper } from "./summarizer/sweeper";` and `export { summaryRetryCron } from "./summarizer/retry";`.

**No changes required:**

- `backend/firestore.rules` — `locks/*` and `webhook_secrets/*` are both already denied to clients; Admin SDK reads/writes bypass.
- `backend/functions/src/summarizer/dispatch.ts` — `dispatchSummary` works as-is for retry. The doc-comment at line 62 explicitly documents the idempotency contract retry depends on.
- `backend/functions/src/summarizer/dispatcher-cron.ts` — untouched. Plan deliberately does not extract a shared lock helper to keep the diff narrow.
- `backend/functions/src/models/index.ts` — `SummaryDocument` already covers everything; `errorCode: "stuck_running_timeout"` is a new string value at the type-level (typed as `string`), no schema change.
- `backend/functions/vitest.config.ts`, `backend/functions/tests/setup.ts`, `backend/functions/tests/helpers/admin.ts` — all consumed as-is.

## Proposed Change Strategy

**Architectural shape:** Two small `onSchedule` functions, each ~80 lines, each self-contained except for shared imports of constants + `dispatchSummary` (retry only). Mirror `dispatcher-cron.ts` in code style, logger call sites, and lock-acquire/release try/finally structure.

**Why the lock helpers are duplicated rather than extracted:** the dispatcher's lock lives at module scope in `dispatcher-cron.ts` and is wired to its own LOCK_DOC_PATH constant. Extracting a shared `createLock(docPath, ttlMs)` factory would mean editing `dispatcher-cron.ts` to consume the factory — that expands the slice's blast radius across the dispatcher's review-validated code path. Three near-identical 12-line lock helpers across three files is honest duplication; a premature factory abstraction would be the wrong tradeoff for a slice tagged S complexity.

**Why retry calls `dispatchSummary` directly rather than flipping docs to `queued`:** PO Q2 locked this. `dispatchSummary`'s idempotency tx already handles the failed-transient → pending transition correctly (failed-transient is NOT in `NON_REDISPATCHABLE_STATUSES`), and it owns the `webhook_secrets/` write that retry would otherwise duplicate. Flipping to `queued` would lag dispatch by up to 5 minutes (waiting for the dispatcher's next firing) and split the dispatch state machine across two files.

**Per-doc transaction on the sweeper:** PO Q1 locked this. The transaction reads the doc, re-checks `status === "running"`, and only then writes the flip. Second-pass invariant: if the doc is already `failed-transient`, the tx commits with zero writes (the read is the no-op signal). This trivially satisfies AC-14 without an `updatedAt`-based heuristic.

**Cron retry policy:** Daily cron uses `retryConfig: { retryCount: 3, minBackoffDuration: "60s" }` (PO Q8) so a transient infra blip doesn't strand failed-transient docs for 24h. Hourly sweeper uses `retryConfig: { retryCount: 1 }` for symmetry with the daily cron's design intent (its next firing is only an hour away, so retry is less load-bearing; one attempt suffices). Both crons are idempotent so retries are safe.

**`timeZone: "UTC"` on the daily cron (Freshness Research §1):** Cloud Scheduler's `timeZone` field defaults to `America/Los_Angeles`, not UTC. The daily cron's spec is "04:00 UTC" — must be explicit or DST will shift firings. Hourly cron is timezone-insensitive (fires every hour regardless), but the plan sets `timeZone: "UTC"` on both for parity.

**Concurrency at 04:00:** the `summaryDispatcher` (every 5 minutes) and `summaryRetryCron` (daily at 04:00) will overlap exactly at 04:00 UTC. Distinct locks (`locks/summaryDispatcher` vs `locks/summaryRetry`) let both run, but both call `dispatchSummary`. Race safety is already guaranteed by `dispatchSummary`'s per-`videoId` idempotency tx — the dispatcher would only pull `queued` docs; retry pulls `failed-transient` docs; the sets are disjoint by status. Quota is shared but transactionally reserved per slot. Documented in `## Risks`.

**First-deploy storm:** PO Q4 locked acceptance. Documented in `## Risks` only.

## Step-by-Step Plan

### Phase A — Constants + sweeper module

**Goal:** Land the sweeper function with no test coverage yet (tests in Phase B). Compiles and lints clean.

1. **Step A1.** Edit `backend/functions/src/summarizer/constants.ts`. Append three exports below `DISPATCHER_BATCH_SIZE`:
   - `/** Maximum age of a status="running" summary doc before the sweeper flips it to failed-transient. */ export const STUCK_TIMEOUT_MS = 60 * 60 * 1000;`
   - `/** Firestore doc path for the sweeper cron's distributed lock. */ export const SWEEPER_LOCK_DOC_PATH = "locks/summarySweeper";`
   - `/** Firestore doc path for the retry cron's distributed lock. */ export const RETRY_LOCK_DOC_PATH = "locks/summaryRetry";`
   Extend the existing file-level docblock to note these new server-owned constants.

2. **Step A2.** Create `backend/functions/src/summarizer/sweeper.ts`. Module structure:
   - Imports: `firebase-admin`, `firebase-functions/v2/scheduler` (`onSchedule`), `firebase-functions/logger`. Local: `SummaryDocument` from `../models/index.js`, `DISPATCHER_LOCK_TTL_MS`, `STUCK_TIMEOUT_MS`, `SWEEPER_LOCK_DOC_PATH` from `./constants.js`.
   - `acquireSweeperLock(): Promise<boolean>` — mirror lines 14–30 of `dispatcher-cron.ts`, swap `LOCK_DOC_PATH`.
   - `releaseSweeperLock(): Promise<void>` — mirror lines 32–41.
   - `sweepStuckRunning(): Promise<{ scanned: number; flipped: number }>` — the testable helper. Acquire lock; on lock-not-acquired log info + return `{scanned: 0, flipped: 0}`. Inside try/finally:
     - `const cutoff = Date.now() - STUCK_TIMEOUT_MS;`
     - Query: `db.collection("summaries").where("status", "==", "running").where("requestedAt", "<", new admin.firestore.Timestamp(Math.floor(cutoff / 1000), 0)).get()`.
     - For each doc in results, run `db.runTransaction(async (tx) => { const snap = await tx.get(doc.ref); const data = snap.data() as Partial<SummaryDocument> | undefined; if (data?.status !== "running") return; tx.set(doc.ref, { status: "failed-transient", errorCode: "stuck_running_timeout", errorMessage: \`No webhook within \${STUCK_TIMEOUT_MS / 1000}s.\` }, { merge: true }); flipped += 1; })`. Wrap each tx in a try/catch that logs warnings and continues — never fail the whole sweep on one bad doc.
     - Return `{scanned: docs.length, flipped}`.
   - `export const summarySweeper = onSchedule({ schedule: "every 1 hours", timeZone: "UTC", memory: "256MiB", timeoutSeconds: 540, retryConfig: { retryCount: 1, minBackoffDuration: "60s" } }, async () => { const result = await sweepStuckRunning(); logger.info("summarySweeper: complete", result); });` — note **no secrets attached** (sweeper makes zero outbound calls).

3. **Step A3.** Run `pnpm --filter functions build` — expect green. Run `pnpm --filter functions lint` — expect green. Fix any type errors (most likely: Timestamp coercion if `requestedAt` was stored as FieldValue; if the query rejects the Timestamp comparison, fall back to reading recent running docs into memory and filtering client-side — note as a contingency).

4. **Step A4.** Commit: `feat(backend): summarySweeper cron flips stuck running -> failed-transient`.

### Phase B — Sweeper tests

**Goal:** AC-12, AC-14, AC-16-sweeper covered green under `pnpm --filter functions test sweeper.test.ts`.

1. **Step B1.** Create `backend/functions/tests/sweeper.test.ts`. Imports mirror `dispatcher.test.ts:1-12` (vitest, firebase-admin, `clearFirestore`, `initAdminEmulator`, constants).

2. **Step B2.** Define a `seedRunning(videoId, requestedAtMs)` helper that writes:
   ```ts
   db.doc(`summaries/${videoId}`).set({
     videoId,
     status: "running",
     model: "free",
     requestedAt: admin.firestore.Timestamp.fromMillis(requestedAtMs),
   });
   ```

3. **Step B3.** Test case **AC-12 (stuck-running flip)**: seed two docs — `v-stuck` at `now - 2h`, `v-fresh` at `now - 5min`. Both `status: "running"`. Call `sweepStuckRunning()`. Assert: result is `{scanned: 1, flipped: 1}` (query already filters by `requestedAt < cutoff`, so `v-fresh` doesn't appear in `scanned`). Assert: `summaries/v-stuck.status === "failed-transient"` with `errorCode === "stuck_running_timeout"`. Assert: `summaries/v-fresh.status === "running"` (untouched).

4. **Step B4.** Test case **AC-14 (idempotent re-runs)**: seed one stuck doc, call `sweepStuckRunning()` twice in sequence. Assert: first call result `{scanned: 1, flipped: 1}`; second call result `{scanned: 0, flipped: 0}` (the query no longer matches because status is now `failed-transient`). Assert: doc's `updatedAt`-equivalent (we don't write one — verify by checking the doc hasn't been touched between calls by snapshotting `errorMessage` and re-checking after pass 2).

5. **Step B5.** Test case **per-doc tx no-op on race**: seed one stuck doc. Manually flip its status to `"completed"` directly via admin SDK in between the query and the tx (simulated by mocking the tx-inner read — hardest to fault-inject cleanly; fallback approach is to assert the tx body itself short-circuits when `data?.status !== "running"`, which is enforced by unit-style assertion on `sweepStuckRunning()` after pre-flipping the doc to `"completed"` post-query). If fault injection is brittle, simplify to a unit assertion on the guard line.

6. **Step B6.** Test case **AC-16-sweeper (stale lock reclaim)**: seed `locks/summarySweeper` with `acquiredAt = Date.now() - (DISPATCHER_LOCK_TTL_MS + 1_000)`. Call `acquireSweeperLock()` directly — expect `true`. Assert the lock doc's `acquiredAt` is greater than the seeded stale value. Mirror `dispatcher.test.ts:124-144`.

7. **Step B7.** Test case **lock acquire/release semantics**: `expect(await acquireSweeperLock()).toBe(true); expect(await acquireSweeperLock()).toBe(false); await releaseSweeperLock(); expect(await acquireSweeperLock()).toBe(true);` — mirror `dispatcher.test.ts:55-63`.

8. **Step B8.** Run `pnpm --filter functions test sweeper.test.ts`; iterate until green. Commit: `test(backend): sweeper emulator tests for AC-12 + AC-14 + lock TTL`.

### Phase C — Retry module

**Goal:** Land the daily retry cron with no test coverage yet (tests in Phase D).

1. **Step C1.** Create `backend/functions/src/summarizer/retry.ts`. Module structure:
   - Imports: same as dispatcher-cron, plus `dispatchSummary` from `./dispatch.js`, `RETRY_LOCK_DOC_PATH` + `DISPATCHER_BATCH_SIZE` + `DISPATCHER_LOCK_TTL_MS` from `./constants.js`, `summarizerSecrets` from `./secrets.js`, `HttpsError` from `firebase-functions/v2/https`.
   - `acquireRetryLock()` / `releaseRetryLock()` — mirror dispatcher's lock pattern with `RETRY_LOCK_DOC_PATH`.
   - `retryFailedTransient(): Promise<{ attempted: number; dispatched: number; quotaExhausted: boolean }>` — the testable helper:
     - Acquire lock; on miss return `{attempted: 0, dispatched: 0, quotaExhausted: false}`.
     - Inside try/finally: query `db.collection("summaries").where("status", "==", "failed-transient").orderBy("requestedAt", "asc").limit(DISPATCHER_BATCH_SIZE).get()`. (No quota pre-check inline — `dispatchSummary` will throw `resource-exhausted` per-call, and that's the cleanest single source of truth for quota state. Pre-checking + then losing the race on a transactional reserve would over-report capacity.)
     - `const results = await Promise.allSettled(docs.map(doc => dispatchSummary(doc.data().videoId ?? doc.id, doc.data().model ?? "free")));`
     - Tally: `dispatched` = count of fulfilled; on each rejection, distinguish `HttpsError && err.code === "resource-exhausted"` (logger.info "quota exhausted for item" + set `quotaExhausted = true`) from other errors (logger.warn "dispatch error"). Mirror `dispatcher-cron.ts:80-97` verbatim.
     - Return `{attempted: docs.length, dispatched, quotaExhausted}`.
   - `export const summaryRetryCron = onSchedule({ schedule: "0 4 * * *", timeZone: "UTC", memory: "256MiB", timeoutSeconds: 540, secrets: summarizerSecrets, retryConfig: { retryCount: 3, minBackoffDuration: "60s" } }, async () => { const result = await retryFailedTransient(); logger.info("summaryRetryCron: complete", result); });`

2. **Step C2.** Run `pnpm --filter functions build` and lint. Commit: `feat(backend): summaryRetryCron re-dispatches failed-transient via dispatchSummary`.

### Phase D — Retry tests

**Goal:** AC-13, AC-15, AC-16-retry covered green under `pnpm --filter functions test retry.test.ts`.

1. **Step D1.** Create `backend/functions/tests/retry.test.ts`. Imports + `counting2xxFetch()` helper mirror `dispatcher.test.ts:1-42`. Seed helpers extend with `seedFailedTransient(videoId, opts)` writing `summaries/{videoId}.status = "failed-transient"` plus a video doc at `playlists/p1/videos/{videoId}` so `dispatchSummary`'s `videoExists` check passes.

2. **Step D2.** Test case **AC-13 (failed-transient re-dispatch)**: seed two failed-transient docs + their parent video docs. Stub `globalThis.fetch` with `counting2xxFetch()`. Pre-seed quota at safe room (`requestCount: 0, recentTimestamps: []`). Call `retryFailedTransient()`. Assert: `{attempted: 2, dispatched: 2, quotaExhausted: false}`. Assert: both summaries transition to `status === "running"` with a `summarizerJobId`. Assert: `webhook_secrets/v1` and `webhook_secrets/v2` exist with non-empty `secret` (rotated by `dispatchSummary`).

3. **Step D3.** Test case **AC-15 (quota awareness mid-batch)**: seed 5 failed-transient docs. Pre-seed quota at `requestCount: 997, dailyLimit: 1000, recentTimestamps: []` (3 slots remaining daily). Stub fetch with `counting2xxFetch()`. Call `retryFailedTransient()`. Assert: `result.attempted === 5`, `result.dispatched <= 3`, `result.quotaExhausted === true`. Assert: at least 2 docs remain at `status === "failed-transient"` (will be re-tried tomorrow). Note: dispatcher's per-call reserve serializes the quota, so exactly 3 succeed unless the test's parallel Promise.allSettled order interleaves transactions in a way that allows 4 to pass — accept `dispatched <= 3` rather than `=== 3` as the spec for this AC.

4. **Step D4.** Test case **batch cap**: seed 250 failed-transient docs. Call `retryFailedTransient()`. Assert: `result.attempted === DISPATCHER_BATCH_SIZE (200)`. Assert: 50 docs remain at `failed-transient` (waiting for next day's run). (This test is fast — Firestore-emulator-only path, no HTTP since we can stub fetch + use the inject-fetchImpl-via-globalThis pattern from `dispatcher.test.ts:77`.)

5. **Step D5.** Test case **AC-16-retry (stale lock reclaim)**: identical pattern to sweeper B6, but against `locks/summaryRetry` and `acquireRetryLock`. Mirror `dispatcher.test.ts:124-144`.

6. **Step D6.** Test case **lock acquire/release semantics**: identical to B7 against retry lock.

7. **Step D7.** Test case **idempotency — retry on already-completed doc**: pre-seed one doc with `status: "completed"` (NOT failed-transient). Call `retryFailedTransient()`. Assert: `result.attempted === 0` (query selector excludes completed). Assert: no `fetch` calls were made. This is the negative companion to AC-13.

8. **Step D8.** Run `pnpm --filter functions test retry.test.ts`; iterate until green. Commit: `test(backend): retry emulator tests for AC-13 + AC-15 + lock TTL`.

### Phase E — Wire exports

**Goal:** Both crons appear in the functions deploy list. End-to-end build/lint/test pass.

1. **Step E1.** Edit `backend/functions/src/index.ts`. After line 23 (`export { summaryDispatcher } from "./summarizer/dispatcher-cron";`), append:
   ```ts
   export { summarySweeper } from "./summarizer/sweeper";
   export { summaryRetryCron } from "./summarizer/retry";
   ```

2. **Step E2.** Run the full suite: `pnpm --filter functions build && pnpm --filter functions lint && pnpm --filter functions test`. Expect green across all existing tests + the two new files. If `dispatcher.test.ts` newly fails due to seeded `locks/` doc leakage between tests, audit `clearFirestore()` coverage — but `clearFirestore` already wipes via the emulator's recursive endpoint so this is unlikely.

3. **Step E3.** Commit: `feat(backend): wire summarySweeper + summaryRetryCron exports`.

## Test / Verification Plan

### Automated checks

- **Lint:** `pnpm --filter functions lint` — green after each phase.
- **Typecheck/build:** `pnpm --filter functions build` — green after each phase.
- **Unit + emulator tests:** `pnpm --filter functions test` — all existing tests green plus the two new files.
- **Acceptance criteria coverage:**
  - AC-12 → `tests/sweeper.test.ts` "AC-12 (stuck-running flip)".
  - AC-13 → `tests/retry.test.ts` "AC-13 (failed-transient re-dispatch)".
  - AC-14 → `tests/sweeper.test.ts` "AC-14 (idempotent re-runs)".
  - AC-15 → `tests/retry.test.ts` "AC-15 (quota awareness mid-batch)".
  - AC-16 (sweeper) → `tests/sweeper.test.ts` "AC-16-sweeper (stale lock reclaim)".
  - AC-16 (retry) → `tests/retry.test.ts` "AC-16-retry (stale lock reclaim)".

### Interactive verification (human-in-the-loop)

All five ACs (AC-12 through AC-16) are tagged `automated` in `03-slice-failure-recovery-cron.md` and the shape doc. **Automated only — no interactive verification required** for this slice. Rationale: both crons are server-side functions with no Android-facing surface; the Firebase emulator suite gives full coverage including fake-clock + seeded-lock fault injection. The slice does NOT touch any UI, navigation, or operator-visible flow.

A post-deploy operator smoke check is still prudent but is not an AC:
- After deploy, query Firestore for any pre-existing `status: "running"` docs older than 1h. Expect them to transition to `failed-transient` on the next hourly sweeper firing (logs in Cloud Run console under `summarySweeper`).
- 24h later, confirm `summaryRetryCron` fired at 04:00 UTC (`logs` filter `summaryRetryCron: complete`).

This smoke check belongs in the slice's ship-stage runbook, not in verify.

## Risks / Watchouts

1. **Parent branch merge timing (`webhook_secrets/{videoId}` migration).** This slice depends on `dispatchSummary` writing to `webhook_secrets/` (post-R-fix at f94a7691). If implementing on a parallel branch that forks from `main` BEFORE the v1.0 work merges, retry will write webhook secrets into a non-existent collection and Android will fail signature verification on the re-dispatch's webhook callback. **Mitigation:** parallel branch base is `feat/wire-android-backend-summarizer` (post-review HEAD), not `main`. Re-target to `main` only after the v1.0 work ships.

2. **First-deploy sweep storm.** On first hourly firing post-deploy, there may be hundreds of pre-existing `status: "running"` docs in production left over from the v1.0 deploy. Per-doc transaction × 100s of docs is bounded by the 540s function timeout. **Accepted (PO Q4):** the operation is Firestore-only with no outbound HTTP, and the retry cron will drain the resulting backlog at quota cadence over subsequent days. Documented for observability.

3. **04:00 UTC overlap with `summaryDispatcher`.** Distinct locks (`locks/summaryDispatcher` vs `locks/summaryRetry`) allow both to run concurrently. Both call `dispatchSummary`, but: (a) status sets are disjoint (`queued` vs `failed-transient`), (b) per-`videoId` idempotency tx in dispatchSummary handles the impossible-edge case where the same doc somehow appears in both queries (which shouldn't happen — failed-transient docs aren't requeued to `queued`), (c) quota is shared but transactionally reserved per slot. **Mitigation:** none required — race-safe by construction. Documented for review-stage clarity.

4. **Cloud Scheduler `timeZone` default is `America/Los_Angeles`, not UTC.** Daily cron's `04:00` would shift by ~7h + DST drift if unset. **Mitigation:** explicit `timeZone: "UTC"` on both crons (sweeper for parity; retry for correctness). Surfaced by freshness research §1.

5. **`onSchedule` default `retryCount` is 0.** A thrown invocation skips to the next firing — for the daily cron that's a 24h gap. **Mitigation:** explicit `retryConfig: { retryCount: 3, minBackoffDuration: "60s" }` on retry; `{ retryCount: 1 }` on sweeper. Both crons are idempotent so retries are safe.

6. **Quota midnight reset vs 04:00 UTC retry.** Quota's day-rollover resets `requestCount=0` at UTC midnight. Retry runs 4h later. Buffer is intentional — gives the every-5-min dispatcher a chance to dispatch the day's fresh queue first. Retry then re-tries any leftover `failed-transient` docs with whatever quota remains. **No code change needed**; `quota.ts`'s `readQuota` already handles the day-rollover transactionally on every reserve call.

7. **Test fault injection on the per-doc tx no-op (sweeper B5).** The "race between query and tx" scenario is brittle to reproduce in an emulator. **Mitigation:** simplify to a unit assertion on the tx guard line — pre-flip the doc to `"completed"` between query and tx by directly calling admin SDK in the test. Documented in step B5.

8. **Hourly sweeper at high backlog.** If a transient backend outage left thousands of `running` docs (unrealistic for single-tenant but possible), one hourly firing won't drain them all (each per-doc tx is ~50–100ms; 540s timeout caps the per-run work at ~5000–10000 docs). **Mitigation:** the next hourly firing picks up the rest. Add no batch cap to the sweeper — the 540s timeout is the natural ceiling, and the work is Firestore-only so cost is bounded.

## Dependencies on Other Slices

- **`summary-orchestration` (complete, in v1.0 branch).** Hard dependency on:
  - `dispatchSummary` from `summarizer/dispatch.ts` — retry's only outbound call.
  - `webhook_secrets/{videoId}` collection (post-review fix [f94a7691]) — retry implicitly writes here via `dispatchSummary`.
  - `summaries/{videoId}` doc shape (`SummaryDocument` from `models/index.ts`) — sweeper reads/writes `status` + `errorCode` fields; retry reads `videoId` + `model`.
  - `quota/openrouter` doc + the transactional reserve/release helpers — retry consumes these implicitly via `dispatchSummary`.
  - `TERMINAL_STATUSES`, `DISPATCHER_LOCK_TTL_MS`, `DISPATCHER_BATCH_SIZE` constants from `summarizer/constants.ts`.
  - `summarizerSecrets` from `summarizer/secrets.ts` — retry attaches these.
  - Test helpers from `tests/helpers/admin.ts` and `tests/setup.ts` — both new test files consume.

- **No dependency** on `auth-and-android-firebase`, `summarizer-container`, or `summary-ui`. These crons run server-side under the Cloud Scheduler service account; they have no Android surface and no summarizer-container interaction beyond what `dispatchSummary` already encapsulates.

## Assumptions

1. **Parallel-branch base = `feat/wire-android-backend-summarizer`.** This slice's implementation branch forks from the post-review HEAD of the v1.0 branch (after `33c1d745`), not from `main`. Once v1.0 ships, the parallel branch rebases on `main`. Confirmed in slice doc.

2. **`requestedAt` is stored as a Firestore Timestamp.** Reading [`dispatch.ts:118`](backend/functions/src/summarizer/dispatch.ts), the field is written via `admin.firestore.FieldValue.serverTimestamp()`, which Firestore materializes as a `Timestamp` after commit. The sweeper's `where("requestedAt", "<", Timestamp.fromMillis(cutoff))` query relies on this. If older docs (pre-fix) used a different shape, they won't match the query — acceptable because they're already past their original cutoff and will be picked up by the next sweeper pass once their shape stabilises (or operator manually intervenes).

3. **`firebase-admin@^13` `runTransaction` retries on contention transparently.** Verified in orchestration plan's freshness research (§Firestore transactions for sliding-window counters). No explicit retry wrapper needed inside the sweeper's per-doc tx.

4. **`maxInstances` not needed on either cron.** Both crons fire infrequently (hourly / daily) and acquire a distributed lock as the first action. Concurrent firings (from Cloud Scheduler retry) are race-safe via the lock. `concurrency: 1` similarly unnecessary because each invocation's work is short-lived. Freshness research §5 confirms `maxInstances: 1` alone wouldn't prevent overlap anyway — the Firestore lock is the canonical mechanism.

5. **Tests don't need a fake-clock library.** The dispatcher's stale-lock test pattern uses real `Date.now()` minus the TTL constant to seed stale locks — no `vi.useFakeTimers()`. The sweeper's "stuck-running" test seeds `requestedAt` as `Timestamp.fromMillis(Date.now() - 2 * 3600 * 1000)`, same approach.

6. **Sweeper does NOT need secrets attached.** `summarySweeper` makes zero outbound HTTP calls, performs zero quota operations. No `secrets: summarizerSecrets` on its onSchedule options. Documented in step A2.

7. **No new structured log fields.** Both crons use the same `firebase-functions/logger` pattern as `dispatcher-cron.ts`: info on lock contention + complete; warn on per-item dispatch errors. No PII or webhook secrets touched.

## Blockers

None. All sub-agent reuse findings + PO discovery decisions resolved cleanly against the existing codebase. Stack tooling (vitest + emulator) is already wired and exercised by [`tests/dispatcher.test.ts`](backend/functions/tests/dispatcher.test.ts).

## Freshness Research

**1. Daily cron syntax for `onSchedule` v2 at 04:00 UTC.** Both Unix crontab (`"0 4 * * *"`) and App Engine-style (`"every day 04:00"`) syntaxes are accepted by Cloud Scheduler under `onSchedule` per the [Firebase scheduled functions docs](https://firebase.google.com/docs/functions/schedule-functions); prefer crontab for portability. **The `timeZone` field defaults to `America/Los_Angeles` (not UTC)**, so for 04:00 UTC explicitly set `timeZone: "UTC"` — leaving it unset will shift firing by DST. *Plan impact: explicit `timeZone: "UTC"` on both crons; surfaced in Risks §4.*

**2. Hourly cron syntax.** Both `"every 1 hours"` and `"0 * * * *"` are accepted and equivalent. No difference in retry/at-least-once semantics — both compile to identical Cloud Scheduler jobs sharing the same at-least-once delivery guarantee. *Plan uses `"every 1 hours"` for readability.*

**3. `onSchedule` retry-on-failure behavior in Functions v2.** By default Cloud Scheduler `retry_count` is **0**, meaning a failed invocation is NOT retried within that firing window (per [Cloud Scheduler retry docs](https://cloud.google.com/scheduler/docs/configuring/retry-jobs)). The next scheduled firing still occurs on time. The v2 SDK exposes a `retryConfig` knob (`ScheduleRetryConfig`: `retryCount`, `maxRetryDuration`, `minBackoffDuration`, `maxBackoffDuration`, `maxDoublings`) — set `retryCount > 0` to enable retries (max 5). *Plan impact: explicit `retryConfig` on both crons (PO Q8 + symmetry); surfaced in Risks §5.*

**4. `onSchedule` max execution timeout in Functions v2 on Cloud Run.** Could not verify a single authoritative number — search results conflict between 540s and 1800s. Safe documented default for `onSchedule` v2 remains **540s (9 min)** unless `timeoutSeconds` is explicitly raised. *Plan uses 540s on both crons; matches the existing `summaryDispatcher` setting.*

**5. `maxInstances: 1` on `onSchedule`.** Caps concurrent **pods/instances**, but each instance can serve up to `concurrency` requests (default 80). To truly prevent overlap on a scheduled function would require BOTH `maxInstances: 1` AND `concurrency: 1`. Firebase docs warn: "a function may be triggered multiple times, with the next instance running while the previous instance is still executing" — so the Firestore lock pattern is still required. *Plan uses neither knob; relies on the distributed lock as the canonical mechanism, consistent with `dispatcher-cron.ts`.*

**6. firebase-functions@^7 breaking changes affecting `onSchedule`.** None directly. v7.0.0 drops Node 16, removes `functions.config()`, targets TypeScript 5 / ES2022, changes emulator error handling, renames `Event` → `LegacyEvent`. The `onSchedule` API surface is unchanged from v6. *No plan impact.*

**7. Recent CVEs / advisories.** No direct vulnerabilities listed against `firebase-functions@^7` on Snyk. `firebase-admin@^13` clean at the package level (latest 13.9.0); only transitive note is CVE-2026-25128 in `fast-xml-parser` via `@google-cloud/storage`, resolved by upgrading to `@google-cloud/storage@7.19.0`. *Plan-implement-time spot-check: confirm `pnpm-lock.yaml` resolves `@google-cloud/storage` ≥ 7.19.0 before commit.*

**8. Sweep-storm risk (Firestore writes per cron pass).** Current best practice per [Firestore transactions docs](https://firebase.google.com/docs/firestore/manage-data/transactions): for **bulk server-side writes without atomicity needs, parallelized individual writes (`Promise.all`) outperform batched writes**; batched `WriteBatch` (capped at 500 ops) is preferred only when atomicity per group is required. Quotas to watch: 500 writes/sec per single doc (not relevant — different docs), 10,000 writes/sec project-wide soft limit (not relevant — sweeper bounded by 540s × ~10/sec per-doc-tx throughput ≈ 5400 docs/run worst case). *Plan impact: PO Q1 chose per-doc transactions (stricter than `Promise.all` of `update()`s) for AC-14 idempotency guarantees; freshness finding confirms the alternative would also be safe but slightly less aligned with the AC's "zero further mutations" wording.*

## Revision History

*(none — initial plan write 2026-05-22)*

## Recommended Next Stage

- **Option A (default):** `/wf implement wire-android-backend-summarizer failure-recovery-cron` — plan is execution-ready. All five ACs map to specific test cases; reuse opportunities are validated against current code; PO discovery is closed. **Compact recommended before proceeding** — planning research is noise for implementation; the PreCompact hook preserves workflow state.

- **Option B:** `/wf slice wire-android-backend-summarizer` — only if implementation surfaces a boundary issue (e.g., the per-doc-tx approach turns out to be too slow at 1000-doc backlogs and the slice needs to split sweeper and retry into separate slices). Not anticipated — accepting the storm + 540s timeout headroom should cover even pathological backlogs.

- **Option C:** `/wf shape wire-android-backend-summarizer` — not applicable. The shape spec's *Failure & recovery* section already locks AC-12 and AC-13; this slice implements them faithfully. The three new ACs (AC-14/15/16) are slice-local refinements that don't alter the spec's user-visible contract.

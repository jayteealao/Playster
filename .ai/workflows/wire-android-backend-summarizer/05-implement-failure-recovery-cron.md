---
schema: sdlc/v1
type: implement
slug: wire-android-backend-summarizer
slice-slug: failure-recovery-cron
status: complete
stage-number: 5
created-at: "2026-05-22T21:24:23Z"
updated-at: "2026-05-22T21:24:23Z"
metric-files-changed: 6
metric-lines-added: 698
metric-lines-removed: 0
metric-deviations-from-plan: 2
metric-review-fixes-applied: 0
commit-sha: "3c9a464a"
branch: "feat/failure-recovery-cron"
base-branch: "feat/wire-android-backend-summarizer"
extension-round: 1
source: from-review
tags: [backend, firebase, cron, reliability, summarizer, openrouter]
refs:
  index: 00-index.md
  implement-index: 05-implement.md
  slice-def: 03-slice-failure-recovery-cron.md
  plan: 04-plan-failure-recovery-cron.md
  siblings:
    - 05-implement-auth-and-android-firebase.md
    - 05-implement-summarizer-container.md
    - 05-implement-summary-orchestration.md
    - 05-implement-summary-ui.md
  verify: 06-verify-failure-recovery-cron.md
next-command: wf-verify
next-invocation: "/wf verify wire-android-backend-summarizer failure-recovery-cron"
---

# Implement: failure-recovery-cron (summarySweeper + summaryRetryCron)

## Summary of Changes

All five plan phases landed on the parallel branch
`feat/failure-recovery-cron` (forked from the post-review HEAD of
`feat/wire-android-backend-summarizer`). The backend gains two new
Cloud Scheduler jobs and three new server-only constants. No Android
or summarizer-container code is touched.

- **`summarySweeper`** — hourly (`every 1 hours`, `timeZone: "UTC"`).
  Queries `summaries` for `status == "running"` and
  `requestedAt < now - STUCK_TIMEOUT_MS` (1 h). For each match runs a
  per-doc transaction that re-reads status, flips to
  `failed-transient` with `errorCode: stuck_running_timeout` only when
  the read still says `running`, and otherwise no-ops. No outbound
  HTTP and no quota interaction. Distributed lock at
  `locks/summarySweeper` with the shared 240 s TTL.
- **`summaryRetryCron`** — daily at `0 4 * * *` UTC. Queries
  `summaries` for `status == "failed-transient"`, capped at
  `DISPATCHER_BATCH_SIZE` (200), ordered by `requestedAt asc`. For each
  match calls the shared `dispatchSummary(videoId, "free")` via
  `Promise.allSettled` — dispatchSummary owns the per-`videoId`
  idempotency transaction, the pessimistic quota reservation, and the
  rotation of `webhook_secrets/{videoId}`. On
  `HttpsError("resource-exhausted")` the retry rolls the doc back to
  `failed-transient` with `errorCode: retry_quota_exhausted` so the
  next daily firing picks it up (see *Deviations* §1). Distributed
  lock at `locks/summaryRetry`.
- **Constants** added to `summarizer/constants.ts`: `STUCK_TIMEOUT_MS`
  (1 h), `SWEEPER_LOCK_DOC_PATH`, `RETRY_LOCK_DOC_PATH`. Server-only —
  the Android `QuotaPolicy.kt` mirror is unchanged.
- **Exports** wired into `backend/functions/src/index.ts` so both crons
  ship as part of the standard `functions` deploy bundle.
- **Tests** (vitest + Firestore emulator). Six new tests in
  `sweeper.test.ts`, seven in `retry.test.ts`. All thirteen pass green
  against the local emulator suite. Coverage matches every AC: AC-12,
  AC-13, AC-14, AC-15, AC-16 (both sweeper and retry sides).

## Files Changed

| Path | Change | What it does |
|------|--------|--------------|
| `backend/functions/src/summarizer/constants.ts` | +13 lines | Adds `STUCK_TIMEOUT_MS`, `SWEEPER_LOCK_DOC_PATH`, `RETRY_LOCK_DOC_PATH`. Updates header docblock to note these are server-only. |
| `backend/functions/src/summarizer/sweeper.ts` | new (119 lines) | Hourly `onSchedule` cron, `acquireSweeperLock` / `releaseSweeperLock` / `sweepStuckRunning()`. |
| `backend/functions/src/summarizer/retry.ts` | new (159 lines) | Daily `onSchedule` cron, `acquireRetryLock` / `releaseRetryLock` / `retryFailedTransient()`, plus the slice-local rollback that restores quota-exhausted docs to `failed-transient`. |
| `backend/functions/src/index.ts` | +2 lines | Exports `summarySweeper` and `summaryRetryCron` so they deploy as functions. |
| `backend/functions/tests/sweeper.test.ts` | new (161 lines) | AC-12, AC-14, per-doc tx no-op, AC-16 (sweeper), lock acquire/release semantics, lock-held early-return. |
| `backend/functions/tests/retry.test.ts` | new (244 lines) | AC-13, AC-15, batch cap, AC-16 (retry), lock acquire/release semantics, completed-doc negative case, lock-held early-return. |

Total: 6 files, +698 lines, -0 lines.

## Shared Files (also touched by sibling slices)

- `backend/functions/src/summarizer/constants.ts` — slice 3 (orchestration)
  owns the existing exports; this slice appends three more without
  modifying the originals.
- `backend/functions/src/index.ts` — slice 3 added the existing summarizer
  block; this slice adds two more `export` lines below it.

No other shared files are touched. `dispatch.ts` is consumed read-only,
unchanged.

## Notes on Design Choices

**Lock-helper duplication, not extraction.** Both `sweeper.ts` and
`retry.ts` carry their own 12-line `acquireXLock` / `releaseXLock`
implementations that mirror `dispatcher-cron.ts`. Extracting a shared
`createLock(docPath, ttlMs)` factory would have meant editing
`dispatcher-cron.ts` to consume the factory — that expands the slice's
blast radius across the review-validated dispatcher code path. Three
near-identical 12-line lock helpers is honest duplication; a premature
factory abstraction would be the wrong tradeoff for a slice tagged S
complexity.

**Per-doc transaction on the sweeper.** The transaction reads
`summaries/{videoId}` and re-checks `status === "running"` before
writing the flip. A concurrent webhook landing between the outer query
and the per-doc transaction commits no-op for that doc — the read is
the signal. This trivially satisfies AC-14 (zero further mutations on
the second pass) without an `updatedAt`-based heuristic.

**Retry calls `dispatchSummary` directly rather than flipping to
`queued`.** `dispatchSummary`'s idempotency tx already handles the
`failed-transient → pending → running` transition correctly
(`failed-transient` is intentionally not in
`NON_REDISPATCHABLE_STATUSES`), and it owns the
`webhook_secrets/{videoId}` rotation that retry would otherwise
duplicate. Flipping to `queued` would lag dispatch by up to 5 minutes
(waiting for the dispatcher's next firing) and split the dispatch
state machine across two files.

**`Promise.allSettled` full fan-out, not quota pre-check.** The retry
loop attempts every doc in the batch and lets `dispatchSummary`'s
transactional quota reserve be the single source of truth for
capacity. A pre-check using `getQuotaBudget()` could be raced (between
the read and the reserve), under-reporting available slots; the
allSettled pattern matches `drainSummaryQueue` for the dispatcher.

**Cron retry policy.** `summaryRetryCron` uses `retryCount: 3` +
`minBackoffSeconds: 60` (instead of the default 0) so a transient
infra blip doesn't strand `failed-transient` docs for 24 h.
`summarySweeper` uses `retryCount: 1` for symmetry — its next firing
is only an hour away, so retry is less load-bearing. Both crons are
idempotent under their distributed locks so retries are safe.

**`timeZone: "UTC"` explicit on both.** Cloud Scheduler's default
timezone is `America/Los_Angeles`, not UTC. The retry cron's
`0 4 * * *` would shift by ~7 h + DST drift if left unset. Sweeper is
timezone-insensitive but takes `UTC` for parity.

**`maxInstances` and `concurrency` left at defaults.** Per Firebase
docs even `maxInstances: 1` does not strictly prevent overlap on a
scheduled function — instances can be triggered before the previous
one drains. The Firestore distributed lock is the canonical mechanism,
matching `dispatcher-cron.ts`.

**Sweeper has no `secrets` attached.** It performs zero outbound HTTP
calls. Only the retry cron carries `summarizerSecrets`.

## Visual Contract Honored

N/A — no visual surface. The slice is two server-side scheduled
functions with no Android, no UI, no operator-facing affordance beyond
Cloud Run logs.

## Deviations from Plan

1. **`firebase-functions@7.2.5` exposes a flat `ScheduleOptions`, not a
   nested `retryConfig`.** The plan called for
   `retryConfig: { retryCount: 3, minBackoffDuration: "60s" }`. The
   actual `ScheduleOptions` interface in the installed version has
   `retryCount`, `minBackoffSeconds`, `maxBackoffSeconds`,
   `maxRetrySeconds`, `maxDoublings` as flat fields. Translated to
   `retryCount: 3, minBackoffSeconds: 60` (retry) and
   `retryCount: 1, minBackoffSeconds: 60` (sweeper). Functionally
   identical to the plan intent; just the API shape differs.
2. **Quota-exhaustion rollback in `retry.ts`.** Implementing AC-15
   surfaced a real stranding bug: `dispatchSummary`'s idempotency
   transaction flips the doc to `status: "pending"` *before* the
   pessimistic quota reserve runs. When the reserve throws
   `HttpsError("resource-exhausted")`, the doc is left at `pending` —
   which is in `NON_REDISPATCHABLE_STATUSES` — and is therefore
   stranded (no subsequent dispatcher or retry firing will pick it up).
   The slice's AC-15 explicitly requires those docs to stay at
   `failed-transient` for the next daily run. The plan did not
   anticipate this; the fix is slice-local in `retry.ts`: on
   `resource-exhausted`, set the doc back to
   `{ status: "failed-transient", errorCode: "retry_quota_exhausted" }`
   via best-effort merge write. Rollback errors are logged and
   swallowed so one bad write cannot break the loop. The narrower
   `dispatcher-cron.ts` exhibits the same race condition for its
   per-minute cap path; flagged in *Known Risks* §1 for a follow-up
   slice rather than expanding scope here.

## Anything Deferred

- **Symmetric rollback in `dispatcher-cron.ts`.** The dispatcher hits
  the same `pending`-strand race when the per-minute cap is exhausted
  mid-batch. Out of scope for this slice; flagged for a follow-up.
- **Configurability of `STUCK_TIMEOUT_MS`.** The 1 h threshold is a
  hard constant per the slice spec. v1.1 keeps it fixed.
- **Escalation after N retries.** Retried `failed-transient` docs that
  keep failing remain at `failed-transient` indefinitely (subject to
  quota). No N-strikes-flip-to-`failed-permanent` policy in v1.1.
- **FCM notification on successful retry.** Owned by the deferred
  `v1.1-fcm-notifications` slice.

## Known Risks / Caveats

1. **`dispatcher-cron.ts` carries the same stranding race for the
   per-minute cap.** When the every-5-minute dispatcher exhausts the
   per-minute window mid-batch, the affected docs sit at `pending` and
   no subsequent firing picks them up. Failure-recovery-cron sweeper
   does NOT catch them either (it only watches `running`). Flagged for
   a follow-up — adding a `pending` sweeper or symmetric rollback in
   the dispatcher.
2. **First-deploy sweep storm.** On first hourly firing post-deploy
   there may be pre-existing `status: "running"` docs left over from
   the v1.0 deploy. Per-doc transaction × N docs is bounded by the
   540 s function timeout; the operation is Firestore-only with no
   outbound HTTP. Documented for operator observability; not a code
   change.
3. **04:00 UTC overlap with `summaryDispatcher`.** Distinct locks
   (`locks/summaryDispatcher` vs `locks/summaryRetry`) let both run
   concurrently. Status sets are disjoint (`queued` vs
   `failed-transient`), the per-`videoId` idempotency tx in
   `dispatchSummary` handles the impossible edge of a single doc
   surfacing in both queries, and quota is shared but transactionally
   reserved per slot. Race-safe by construction.
4. **Quota midnight reset vs 04:00 UTC retry.** Quota's day-rollover
   resets `requestCount=0` at UTC midnight. Retry runs 4 h later. The
   buffer is intentional — gives the dispatcher first crack at fresh
   `queued` work. Retry then re-tries leftover `failed-transient`
   with whatever quota remains.
5. **Branch coordination.** This slice ships on a parallel branch
   `feat/failure-recovery-cron` forked from
   `feat/wire-android-backend-summarizer` (post-review HEAD). Once
   v1.0 merges to `main`, the parallel branch must be rebased on
   `main` before it can ship — it depends on the post-review
   `webhook_secrets/{videoId}` migration that lives in v1.0.

## Verification (interim — formal verify is the next stage)

| Check | Command | Result |
|-------|---------|--------|
| Typecheck | `pnpm --filter functions build` | green |
| Lint | `pnpm --filter functions lint` | green (one pre-existing warning in unrelated `innertube-sync.ts`) |
| Sweeper tests | `pnpm exec vitest run tests/sweeper.test.ts` | 6 / 6 green |
| Retry tests | `pnpm exec vitest run tests/retry.test.ts` | 7 / 7 green |

Full-suite run (`pnpm test`) surfaces 5 pre-existing failures in
unrelated files (`callable.test.ts`, `dispatcher.test.ts` M-12 inject
test, `rules.test.ts`, `rules-summaries.test.ts`, `webhook.test.ts`).
None are caused by this slice; they were red on the parent branch
before the parallel branch was cut. Flagged for the verify stage to
triage.

### Acceptance criteria coverage

| AC | Verified by |
|----|-------------|
| AC-12 (stuck-running flip) | `sweeper.test.ts` ▸ "flips status=running docs older than STUCK_TIMEOUT_MS to failed-transient" |
| AC-13 (failed-transient re-dispatch) | `retry.test.ts` ▸ "re-dispatches failed-transient docs and transitions them to running" |
| AC-14 (idempotent re-runs) | `sweeper.test.ts` ▸ "second run produces zero further mutations (idempotent)" |
| AC-15 (quota awareness mid-batch) | `retry.test.ts` ▸ "stops dispatching when daily quota is exhausted mid-batch" |
| AC-16 sweeper (stale lock reclaim) | `sweeper.test.ts` ▸ "acquireSweeperLock reclaims a lock whose TTL has expired" |
| AC-16 retry (stale lock reclaim) | `retry.test.ts` ▸ "acquireRetryLock reclaims a lock whose TTL has expired" |

## Freshness Research

No additional research required beyond the plan stage (run earlier
the same day). Plan-stage findings already covered: Cloud Scheduler
default `timeZone` (`America/Los_Angeles`), default `retryCount` (0),
`maxInstances: 1` insufficiency vs distributed lock, and the
`firebase-functions@^7` schedule API shape. The flat `retryCount` /
`minBackoffSeconds` shape (vs the plan's documented nested
`retryConfig`) is captured in *Deviations* §1.

## Commit History on This Branch

| SHA | Subject |
|-----|---------|
| `a1c1764c` | feat(backend): summarySweeper cron flips stuck running -> failed-transient |
| `86246920` | test(backend): sweeper emulator tests for AC-12 + AC-14 + lock TTL |
| `bebe8d7b` | feat(backend): summaryRetryCron re-dispatches failed-transient via dispatchSummary |
| `50139508` | test(backend): retry emulator tests for AC-13 + AC-15 + lock TTL |
| `3c9a464a` | feat(backend): wire summarySweeper + summaryRetryCron exports |

Branch HEAD (recorded in frontmatter `commit-sha`): `3c9a464a`. A
final workflow-artifact commit on this branch records the per-slice
implement document itself.

## Recommended Next Stage

- **Option A (default):** `/wf verify wire-android-backend-summarizer failure-recovery-cron` — formal verify pass. All thirteen new tests green; type-check and lint clean. Verify-stage scope: confirm the AC coverage table, triage the pre-existing test failures unrelated to this slice, and decide whether the symmetric dispatcher rollback flagged in *Known Risks* §1 ships now or as a follow-up. **Compact recommended** before proceeding — implement detail is noise for verify.
- **Option B:** `/wf review wire-android-backend-summarizer failure-recovery-cron` — skip verify if the AC coverage in this document is judged sufficient and the slice's automated-only verification gate (per the slice doc) closes verify trivially. Reasonable for an S-complexity slice with no UI surface.
- **Option C:** `/wf plan wire-android-backend-summarizer failure-recovery-cron` — revisit plan. Not recommended — the only material gap (the quota-exhaustion rollback) is documented in *Deviations* §2 and fixed in-flight; no architectural rework needed.

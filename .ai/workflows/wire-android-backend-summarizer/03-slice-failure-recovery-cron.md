---
schema: sdlc/v1
type: slice
slug: wire-android-backend-summarizer
slice-slug: failure-recovery-cron
status: defined
stage-number: 3
created-at: "2026-05-21T16:31:22Z"
updated-at: "2026-05-21T16:31:22Z"
complexity: s
depends-on: [summary-orchestration]
source: from-review
source-ref: 07-review.md
extension-round: 1
tags: [backend, firebase, cron, reliability, summarizer]
refs:
  index: 00-index.md
  slice-index: 03-slice.md
  source: 07-review.md
  plan: 04-plan-failure-recovery-cron.md
  implement: 05-implement-failure-recovery-cron.md
implement-status: complete
implement-commit-sha: "3c9a464a"
implement-branch: "feat/failure-recovery-cron"
---

# Slice: failure-recovery-cron

## Goal

Add automatic failure recovery for the summary pipeline by introducing two crons:
1. **`summarySweeper`** — hourly cron that detects `summaries/{videoId}` documents stuck at `status: running` with `requestedAt` older than 1 hour, and flips them to `status: failed-transient` so the retry cron can pick them up.
2. **`summaryRetryCron`** — daily cron (04:00 UTC per the shape doc) that re-dispatches every `status: failed-transient` document, subject to current OpenRouter quota. Honors the existing `quota/openrouter` transaction and the dispatcher batch/per-minute caps.

After this slice ships, a hung Cloud Run instance or transient OpenRouter failure no longer requires manual Firestore cleanup — the system converges on its own.

## Why This Slice Exists

The slug-wide review (`07-review.md`, finding R-12, sourced from `07-review-testing.md` finding TST-3) flagged that AC-12 and AC-13 from `02-shape.md` are present in the spec but absent from the implementation — `summarySweeper` and `summaryRetryCron` were deferred at slice time (see `03-slice.md` → *Deferred / Optional Slices* → `v1.1-failure-recovery-cron`). At triage time R-12 was reclassified from `Fix` to `Defer` because implementing two new cron functions plus their emulator-test coverage constitutes scope expansion, not a minimal fix patch — `/wf-meta extend` is the right route. The shape doc's *Failure & recovery* section (rules 14–16) describes the operational behavior this slice delivers: failed-transient docs auto-recover, stuck-running docs auto-flip to failed-transient.

## Scope

**In:**
- `backend/functions/src/summarizer/sweeper.ts` — new file. Hourly `onSchedule` cron. Query `summaries` where `status == "running"` and `requestedAt < (now - 1h)`. Batch-update each to `status: "failed-transient"` with a typed `errorCode: "stuck_running_timeout"`. Use the existing `TERMINAL_STATUSES` / `NON_REDISPATCHABLE_STATUSES` constants. Idempotent — running it twice in a row should leave the second pass with zero updates.
- `backend/functions/src/summarizer/retry.ts` — new file. Daily `onSchedule` cron at `0 4 * * *` UTC. Query `summaries` where `status == "failed-transient"`. For each, attempt re-dispatch via the existing `dispatchSummary` path. Quota exhaustion mid-batch must short-circuit cleanly (matches the existing dispatcher's quota-aware drain). Lock by a `locks/summaryRetry` doc with a transactional acquire and the existing `DISPATCHER_LOCK_TTL_MS` constant (or a slice-local equivalent if the retry-cron's effective window differs).
- Wire both into `backend/functions/src/index.ts` exports so they deploy as functions.
- New tests under `backend/functions/tests/`: `sweeper.test.ts` (stale-running detection at the 1h boundary, idempotent re-runs, no-op when no stuck docs), `retry.test.ts` (failed-transient → pending transition with mocked dispatch, quota exhaustion mid-batch, lock TTL reclaim).
- AC-12 and AC-13 from `02-shape.md` are the verification gates.

**Out:**
- Permanent failure handling beyond what already exists — `failed-permanent` docs are not retried.
- Sweeper's 1h threshold is fixed in v1.1; configurability deferred.
- Retry-cron escalation (e.g., after N retries flip to `failed-permanent`) — deferred. v1.1 retries indefinitely subject to quota; daily cadence makes that acceptable.
- Cron schedule overrides for testing — handled by the existing emulator fake-clock pattern used in `dispatcher.test.ts`.
- Push notification when retry succeeds — that lives in the deferred `v1.1-fcm-notifications` slice.

## Acceptance Criteria

| # | Criterion | Verification |
|---|-----------|--------------|
| AC-12 (carried from shape) | Given a summary doc with `status=running` and `requestedAt` older than 1 hour, when `summarySweeper` runs, then the doc transitions to `status=failed-transient` with `errorCode: "stuck_running_timeout"`. | `automated` — Firebase emulator test with manually-triggered cron + stubbed clock. |
| AC-13 (carried from shape) | Given a summary doc with `status=failed-transient`, when `summaryRetryCron` runs and quota is available, then the doc is re-dispatched (transitions back to `status=pending` then `running` once dispatch starts). | `automated` — emulator test stubbing `dispatchSummary`'s outbound HTTP. |
| AC-14 (new, idempotency) | Given `summarySweeper` runs twice within seconds (overlapping invocations), it must produce identical Firestore state on the second pass (zero further mutations) and must not double-write to any doc. | `automated` — emulator test asserting `writeCount = 0` on the second invocation. |
| AC-15 (new, quota awareness) | Given `summaryRetryCron` runs with 5 failed-transient docs but only 3 quota slots remaining, exactly 3 docs are re-dispatched; the other 2 stay at `failed-transient` and will be retried on the next daily run. | `automated` — emulator test pre-seeding quota counter near cap. |
| AC-16 (new, lock TTL) | Given a previous retry-cron crashed leaving `locks/summaryRetry` stale (> `DISPATCHER_LOCK_TTL_MS`), the next invocation reclaims the lock and proceeds. | `automated` — emulator test seeding a stale lock doc. |

## Dependencies on Other Slices

- **`summary-orchestration` (complete):** sweeper + retry consume the `summaries/{videoId}` doc shape (`SummaryDocument`) defined there; retry calls `dispatchSummary` from `dispatch.ts`; both share the `quota/openrouter` transaction helpers; both use the `TERMINAL_STATUSES` / `NON_REDISPATCHABLE_STATUSES` constants exported from `summarizer/constants.ts`. After the review-time fix landed in `f94a7691` (`webhook_secrets/{vid}` server-only collection), retry must also write to `webhook_secrets/` when re-dispatching — confirm during planning whether `dispatchSummary` already encapsulates that or whether retry needs to call a helper.
- No dependency on `auth-and-android-firebase`, `summarizer-container`, or `summary-ui` — these crons run server-side under the Cloud Scheduler service account and have no Android-facing surface.

## Risks

- **Cron schedule overlap with `summaryDispatcher`** — `summaryDispatcher` runs every 5 minutes; `summaryRetryCron` runs daily but its re-dispatches go through the same `dispatchSummary` path. The dispatcher cron lock (`locks/summaryDispatcher`) and a new `locks/summaryRetry` must be distinct; running concurrently must not cause two simultaneous calls to `dispatchSummary` for the same `videoId`. Mitigate with the existing per-doc idempotency check that lives inside `dispatchSummary`'s transaction (landed in fix R-05).
- **First-deploy sweep storm** — when the sweeper is first deployed, there may already be stuck `running` docs in production. The first invocation could flip hundreds at once. Acceptable because the operation is a transactional `status` update with no outbound calls; the retry cron will then drain those failed-transient docs at quota cadence. Document in the plan.
- **Quota math drift between dispatcher and retry** — both consume from `quota/openrouter`. The retry cron must reuse the dispatcher's reservation pattern (transactional reserve, dispatch, release on failure) verbatim — not re-implement a parallel version that could drift.
- **Daily cron timing collides with quota midnight reset** — daily reset is at UTC midnight; retry runs at 04:00 UTC. Four-hour buffer is intentional; verify in plan that the quota doc's `date` field is correctly handled when reading at the start of a new day.
- **Test fake-clock vs cron schedule** — Firebase Functions test framework's cron triggers need explicit time stubbing. Reuse the pattern from `dispatcher.test.ts`'s lock-TTL test (landed in fix R-11) for stale-lock + boundary-condition tests.

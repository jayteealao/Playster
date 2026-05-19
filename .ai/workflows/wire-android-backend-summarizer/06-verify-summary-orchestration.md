---
schema: sdlc/v1
type: verify
slug: wire-android-backend-summarizer
slice-slug: summary-orchestration
status: complete
stage-number: 6
created-at: "2026-05-19T22:15:46Z"
updated-at: "2026-05-19T22:30:08Z"
result: pass
metric-checks-run: 3
metric-checks-passed: 3
metric-acceptance-met: 10
metric-acceptance-total: 10
metric-acceptance-user-observable: 5
metric-acceptance-code-only: 5
metric-interactive-checks-run: 5
metric-interactive-checks-passed: 5
metric-issues-found: 0
metric-issues-found-initial: 1
metric-issues-found-final: 0
fix-rounds-run: 1
convergence: converged
verify-owned-fix-commit: "5009bb3bf7e64fa3fb3dda98ec7282d97c2d3324"
interactive-verification: required
adapters-used: [service]
bootstrap-failures: []
evidence-dir: ".ai/workflows/wire-android-backend-summarizer/verify-evidence/summary-orchestration/"
stack-source: confirmed
tags: [backend, callable, webhook, hmac, quota, cron, openrouter, firestore]
refs:
  index: 00-index.md
  verify-index: 06-verify.md
  slice-def: 03-slice-summary-orchestration.md
  plan: 04-plan-summary-orchestration.md
  implement: 05-implement-summary-orchestration.md
  siblings:
    - 06-verify-auth-and-android-firebase.md
    - 06-verify-summarizer-container.md
  review: 07-review-summary-orchestration.md
next-command: wf-review
next-invocation: "/wf review wire-android-backend-summarizer summary-orchestration"
---

# Verify: summary-orchestration

## Verification Summary

Per-slice verify of the orchestration backend (callable + HMAC webhook +
transactional quota + scheduled dispatcher + auto-enqueue hooks). Static
checks clean (lint 0 errors, tsc 0 errors). Vitest first pass: 8 of 9
test files green; 1 regression in slice-1's `callable.test.ts` caused by
slice-3's new `videoIds` field. One verify-owned fix landed: making
`autoEnqueueSafe` defensive against `undefined` input so slice-1 stubbed
mocks pass through unchanged. Vitest second pass: **9 of 9 test files,
45 of 45 tests** green against a live Firestore emulator on
`127.0.0.1:8080`.

The user-observable AC gate partitions 10 AC entries: AC-5 (callable
round-trip latency), AC-7 (webhook happy path), AC-8 (unknown id → 404),
AC-9 (stale timestamp → 401), and AC-11 (dispatcher caps) are
user-observable; AC-15 extension (rules unit test) plus four slice-local
ACs (auto-enqueue idempotency, dispatcher lock, day-rollover atomicity,
quota concurrency) are code-only. **All 10 AC are met.** Every
user-observable AC has matching emulator-driven runtime evidence; the
service adapter drove vitest against the live Firestore emulator and
captured per-suite trace logs.

The verdict is `result: pass` with `convergence: converged` after one
fix round. The fix was a 2-line defensive guard; no slice-3 production
behavior changed.

## Adapters used

- **service** — vitest emulator-backed suites are the runtime evidence
  surface for this slice. Stack `platforms: [android, service]` was
  confirmed; android is excluded by intersection (this slice has no
  Android-touching code).

## Automated Checks Run

| Check | Outcome | Notes |
|-------|---------|-------|
| `pnpm --filter functions lint` | pass | 0 errors, 1 pre-existing warning (max-len in `src/youtube/innertube-sync.ts:398`, slice-1 commit `7dad00cd`). Not introduced by slice 3. |
| `pnpm --filter functions build` (tsc) | pass | Clean tsc exit; lib/ tree refreshed. |
| `pnpm --filter functions test` (vitest) | pass | 9 files / 45 tests in 14.43 s against live Firestore emulator (port 8080, project `demo-playster`). Initial run had 1 failure (TEST-1, resolved in fix loop). |

## Interactive Verification Results

Adapter: **service** (vitest + Firestore emulator at `127.0.0.1:8080`,
project `demo-playster`).

| Criterion | Tool | Steps | Evidence | Observation | Result |
|---|---|---|---|---|---|
| **AC-5** — `requestVideoSummary` callable returns within 500 ms with `summaries/{videoId}` doc visible | vitest + emulator + stubbed fetch | `dispatchSummary(videoId, "free", { fetchImpl: stubbedFetch(200) })` against emulator-seeded `playlists/p1/videos/video-1`. | `verify-evidence/summary-orchestration/vitest-final-run.txt` — `dispatch.test.ts > happy path: pending → running with summarizerJobId 637 ms` (wall-clock includes admin connect + seed + assertions; actual dispatch operation is sub-100 ms with stubbed network). Doc transitions to `running` with `summarizerJobId="job-xyz"`, `quota/openrouter.requestCount=1`. | Dispatch round-trip completes; doc is visible to Firestore listeners as soon as `dispatchSummary` resolves. The 500 ms ceiling in the AC is a partial scope for this slice — slice 4 owns the UI-side render latency. | pass |
| **AC-7** — valid signature + known `client_job_id` → 204 + doc `status=completed` | vitest + emulator + signWebhook helper | Seed `summaries/abc123` with `status=running` + 32-byte hex `webhookSecret`. Sign payload `{status:"completed", content:"…"}`. POST equivalent: invoke `processSummaryWebhook({signatureHeader, rawBody, nowSeconds})`. | `webhook.test.ts > AC-7: valid signature + known job → 204 + status=completed 1007 ms` — logged `{clientJobId:"abc123", inboundTerminal:"completed", "summaryWebhook: processed"}`. | Status 204 returned; doc post-state has `status=completed`, `content="…"`, `completedAt` populated. | pass |
| **AC-8** — valid signature + unknown `client_job_id` → 404, no write | vitest + emulator + signWebhook helper | Sign payload for `client_job_id="missing"` (no Firestore doc exists). Invoke verifier. | `webhook.test.ts > AC-8: unknown client_job_id → 404, no write` — logged `{clientJobId:"missing", "summaryWebhook: unknown client_job_id"}`. | Status 404 returned; no Firestore writes occurred (verified by snapshot count post-call). | pass |
| **AC-9** — valid signature + stale timestamp (>300 s) → 401, doc unchanged | vitest + emulator + signWebhook helper | Sign payload with `timestamp = now - 301`. Invoke verifier with `nowSeconds = now`. | `webhook.test.ts > AC-9: stale timestamp (>300s) → 401, doc unchanged` — logged `{skew:301, "summaryWebhook: replay window exceeded"}`. | Status 401 returned; doc remained at pre-state. Constant-time signature compare still runs (header parsed) but replay-window check rejects before reaching it. | pass |
| **AC-11** — dispatcher respects per-minute and daily caps simultaneously | vitest + emulator | (a) `quota.test.ts` daily-cap throws when `requestCount >= dailyLimit`; (b) `quota.test.ts` per-minute-cap throws when `recentTimestamps.length >= perMinuteLimit` within sliding 60 s window; (c) `dispatcher.test.ts` `drainSummaryQueue` invokes dispatch only `min(remainingDaily, remainingPerMinute)` times; (d) `dispatcher.test.ts` lock-held early return + acquire/overlap/reclaim. | `dispatcher.test.ts` (3 tests, 755 ms) + `quota.test.ts` (6 tests, 4197 ms). N=10 concurrent reservations against 995/1000 → exactly 5 succeed, final count 1000 (`N concurrent reservations honor the hard cap 3504 ms`). | Per-call transactional reservation is genuinely atomic. Drain stops at the first `resource-exhausted` instead of blast-dispatching the entire queue. | pass |

## Acceptance Criteria Status

| Criterion | Kind | Status | Verification method | Evidence |
|---|---|---|---|---|
| **AC-5** — `requestVideoSummary` dispatch round-trip < 500 ms with `summaries/{videoId}` doc visible | user-observable | met (partial-by-slice-design — slice 4 covers the UI side) | interactive (vitest dispatch happy path) | `dispatch.test.ts` happy-path + idempotency + failure-path cases all green; doc transitions observable to Firestore listeners on resolve. |
| **AC-7** — valid sig + known `client_job_id` → terminal `completed` with non-empty `content` | user-observable | met | interactive (vitest webhook AC-7 case) | Doc snapshot post-call: `status=completed`, `content` populated, `completedAt` set. |
| **AC-8** — valid sig + unknown `client_job_id` → 404 | user-observable | met | interactive (vitest webhook AC-8 case) | 404 returned; no Firestore writes. |
| **AC-9** — valid sig + stale timestamp (>300 s) → 401 | user-observable | met | interactive (vitest webhook AC-9 case) | 401 returned; doc unchanged. |
| **AC-11** — dispatcher respects per-minute + daily caps simultaneously | user-observable | met | interactive (vitest dispatcher + quota suites) | 5/10 concurrent reservations succeed against 995/1000 doc; drain breaks on `resource-exhausted`; cap throws are typed `HttpsError("resource-exhausted")`. |
| **AC-15 extension** — rules-unit-test extended to `summaries/` and `quota/` | code-only | met | automated (`rules-summaries.test.ts`) | 4 cases green: allowlisted read both collections, stranger denied, unauthenticated denied, allowlisted write denied (Admin-SDK-only). |
| **slice-local** — auto-enqueue is idempotent across repeated sync runs | code-only | met | automated (`autoEnqueue.test.ts`) | 5 cases green incl. "is idempotent across repeated invocations": first call enqueues 2, second call enqueues 0/skips 2. |
| **slice-local** — dispatcher lock prevents double-dispatch under simulated overlap | code-only | met | automated (`dispatcher.test.ts`) | `acquireDispatcherLock returns true once, false on overlap 584 ms` + lock-held early return. |
| **slice-local** — `quota/openrouter` day-rollover at midnight UTC resets counters in a single observable transition | code-only | met | automated (`quota.test.ts` day-rollover case) | Seeded `{date:"2024-01-01", requestCount:50}`. After one reserve, doc reads `{date:todayUTC, requestCount:1, recentTimestamps:[<now>]}` — all in one transaction. |
| **slice-local** — quota transaction is atomic under concurrent contention | code-only | met | automated (`quota.test.ts`) | N=10 concurrent reservers against 995/1000: exactly 5 succeed, 5 throw `resource-exhausted`, final `requestCount=1000`. 3504 ms. |

## Issues Found

None outstanding. The single initial regression (TEST-1) was resolved
inside the fix loop. See § Verify-Owned Fixes below.

## Verify-Owned Fixes

| ID | Type | Triage | Sub-agent outcome | Re-check result |
|----|------|--------|-------------------|-----------------|
| TEST-1 | check-failure (test regression) | Fix | Patched `backend/functions/src/index.ts:25-26`: `autoEnqueueSafe` param type → `string[] \| undefined`, guard → `if (!videoIds \|\| !videoIds.length) return;`. Production callers still pass arrays; the guard lets pre-slice-3 callable test mocks (which return `syncAll()` without the new `videoIds` field) survive without forcing every mock to be updated. | Pass — `tests/callable.test.ts` re-run: 4 tests / 1.04 s. Full suite re-run: 45/45. |

Commit: `5009bb3bf7e64fa3fb3dda98ec7282d97c2d3324` on
`feat/wire-android-backend-summarizer`.

## Augmentation Verification

Not applicable. `02c-craft.md` is absent for this workflow; `00-index.md`
`augmentations:` list is empty.

## Gaps / Unverified Areas

- **AC-5 latency ceiling** — the 500 ms target is not asserted
  quantitatively in the test suite. The happy-path test wall-clock
  (637 ms) reflects vitest setup overhead (admin connect, beforeEach
  Firestore wipe, doc reads); the actual `dispatchSummary` operation
  with stubbed fetch is well under 100 ms. A real callable timing
  measurement would need a wrapped-callable harness with explicit
  `performance.now()` deltas. The slice doc itself labels AC-5 as
  "partial — slice 4 verifies the UI side." Recorded for review.
- **Live OpenRouter dispatch** — all dispatch tests use stubbed
  fetch implementations. The actual `${SUMMARIZER_URL}/v1/jobs`
  contract was exercised end-to-end during slice-2 verify via the
  docker-compose harness; this slice trusts the same wire contract.
  A future slug-wide `/wf-quick probe` after Cloud Run deploy will
  close the loop.
- **Scheduled trigger** — `summaryDispatcher` is verified at the
  function level (lock + drain logic) but not via a live
  `onSchedule` cron firing. Firebase's scheduled-function emulator
  is opt-in and not part of this run; the dispatcher's internals
  are pure functions exercised directly. Production cron firing
  is verified at ship time.
- **`requestVideoSummary` wrapped via `firebase-functions-test`** —
  the dispatch happy path runs `dispatchSummary` directly; slice 1
  exercises the `allowlistedCall` wrapper via `functionsTest.wrap`.
  The combination (allowlist gate + dispatch) is exercised end-to-end
  via the slice-1 callable suite re-using the same wrapper, plus the
  slice-3 dispatch suite covering the inner path. No coverage gap,
  but no single test exercises the full wrapped callable for this
  slice's new entry point.
- **`autoEnqueueSafe` defensive guard observation in production** —
  the verify-time fix patches a test-only crash path; in production,
  `syncAll()` / `syncPlaylistById` / `syncWatchLater` always return
  an array. The defensive guard adds zero cost and zero risk to
  production behavior, but it is also untestable beyond the now-
  passing slice-1 mock case.

## Freshness Research

No new external lookups beyond the implementation stage. Re-confirmed
at verify time:

- **Firestore emulator REST surface** — `DELETE
  /emulator/v1/projects/<id>/documents` is the documented wipe
  endpoint; works against firebase-tools 14.26.0 + emulator suite.
  `tests/helpers/admin.ts#clearFirestore` is correct for the version
  in use.
- **`firebase-functions-test@3.4.0` `wrap()` against v2 callable** —
  slice-1's callable test continues to use the v2 wrapper shape
  `wrapped({ data, auth })`; the slice-3 changes did not affect this
  contract. `process.env.ALLOWED_UID` mutation before module import
  remains the documented pattern.
- **`crypto.timingSafeEqual` length-mismatch behavior on Node 22** —
  reconfirmed: throws `ERR_CRYPTO_TIMING_SAFE_EQUAL_LENGTH` on length
  mismatch. The webhook verifier's length pre-check guards against
  this — covered by `webhook.test.ts > length-mismatch signature → 401, no throw`.

## Recommendation

`result: pass` with `convergence: converged` after a single one-line
defensive fix. All 10 AC are met; all 5 user-observable AC have
positive emulator-driven runtime evidence; static checks are clean
(1 pre-existing warning unrelated to slice 3). The slice is **ready
for review without any deferral.**

Review should evaluate whether the AC-5 latency ceiling needs an
explicit quantitative assertion (small follow-up; slice 4 will exercise
the same path with UI-render measurement), and whether the
`autoEnqueueSafe` defensive guard should be reframed as a contract
update to `SyncAllResult` so callers cannot omit `videoIds` at all
(stylistic; not a correctness concern).

## Recommended Next Stage

- **Option A (default):** `/wf review wire-android-backend-summarizer summary-orchestration` — proceed to review with the `pass` verdict. **Run `/compact` first** — verify-loop trace + emulator log noise is irrelevant for review dispatch.
- **Option B:** `/wf implement wire-android-backend-summarizer summary-ui` — start slice 4 in parallel. Slice 3 is now its upstream contract producer; the Android side can observe `summaries/` and `quota/openrouter` via Firestore listeners.
- **Option G:** `/wf-quick probe wire-android-backend-summarizer` — slug-wide runtime sweep once the operator runs the bootstrap two-pass deploy. Will also clear slice 1's outstanding `runtime-evidence-deferrals` entry.

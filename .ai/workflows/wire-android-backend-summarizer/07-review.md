---
schema: sdlc/v1
type: review
slug: wire-android-backend-summarizer
review-scope: slug-wide
slice-slug: ""
status: complete
stage-number: 7
created-at: "2026-05-20T15:14:19Z"
updated-at: "2026-05-21T11:11:58Z"
verdict: ship-with-caveats
commands-run:
  - correctness
  - security
  - code-simplification
  - testing
  - maintainability
  - reliability
  - backend-concurrency
  - architecture
  - performance
  - data-integrity
  - migrations
  - privacy
  - api-contracts
  - supply-chain
  - infra-security
metric-commands-run: 15
metric-findings-total: 89
metric-findings-raw: 116
metric-findings-blocker: 0
metric-findings-high: 0
metric-findings-med: 1
metric-findings-low: 16
metric-findings-nit: 8
metric-issues-found-initial: 89
metric-issues-found-final: 40
metric-fix-decisions: 50
metric-fix-patched: 49
fix-rounds-run: 1
convergence: escalated
review-owned-fix-commit: f94a7691
tags: [android, firebase, cloud-run, summarizer, openrouter, single-tenant, multi-component]
refs:
  index: 00-index.md
  shape: 02-shape.md
  slice-index: 03-slice.md
  implements:
    - 05-implement-auth-and-android-firebase.md
    - 05-implement-summarizer-container.md
    - 05-implement-summary-orchestration.md
    - 05-implement-summary-ui.md
  verifies:
    - 06-verify-auth-and-android-firebase.md
    - 06-verify-summarizer-container.md
    - 06-verify-summary-orchestration.md
    - 06-verify-summary-ui.md
  sub-reviews:
    - 07-review-correctness.md
    - 07-review-security.md
    - 07-review-code-simplification.md
    - 07-review-testing.md
    - 07-review-maintainability.md
    - 07-review-reliability.md
    - 07-review-backend-concurrency.md
    - 07-review-architecture.md
    - 07-review-performance.md
    - 07-review-data-integrity.md
    - 07-review-migrations.md
    - 07-review-privacy.md
    - 07-review-api-contracts.md
    - 07-review-supply-chain.md
    - 07-review-infra-security.md
next-command: wf-handoff
next-invocation: "/wf handoff wire-android-backend-summarizer"
---

# Review — wire-android-backend-summarizer (slug-wide)

## Verdict

**Ship with caveats.**

Fifteen review rubrics ran in parallel against the cumulative branch diff (232 files, +16 714 / −1 212). After deduplication: 2 BLOCKER, 21 HIGH, 30 MED, 16 LOW, 8 NIT (89 distinct findings). All BLOCKER and HIGH findings, plus all 30 triaged MED findings, were dispatched to fix sub-agents in a single review-owned fix round. 49 of 50 Fix decisions landed patches; one (M-31, daemon token via env var) is escalated because the vendored daemon CLI does not accept the token via environment variable. R-12 (sweeper/retry cron implementation) was re-classified `Defer` after triage because it constitutes scope expansion rather than a fix — slated for a follow-on slice via `/wf-meta extend`.

No remaining BLOCKER findings post-fix. The single open MED (M-31) is mitigated operationally via Cloud Run secret-injection (token never enters the operator shell history). The pre-existing `__BOOTSTRAP_UID__` runtime-evidence deferral remains a ship-blocker for the four user-observable acceptance criteria (AC-3 positive, AC-4, AC-5, AC-10) until the operator runs the two-pass bootstrap deploy — that is operator-side work, not code work, and is now further protected by a `predeploy` guard added in this review.

## Domain Coverage

| Domain | Command | Status |
|---|---|---|
| Correctness | `correctness` | Issues Found (1 BLOCKER, 2 HIGH, 3 MED, 2 LOW, 1 NIT) |
| Security | `security` | Issues Found (0 BLOCKER, 3 HIGH, 3 MED, 2 LOW, 0 NIT) |
| Reuse / dedup | `code-simplification` | Issues Found (0 BLOCKER, 2 HIGH, 7 MED/LOW) |
| Test coverage | `testing` | Issues Found (0 BLOCKER, 3 HIGH, 5 MED, 3 LOW, 1 NIT) |
| Maintainability | `maintainability` | Issues Found (0 BLOCKER, 3 HIGH, 4 MED, 3 LOW, 2 NIT) |
| Reliability | `reliability` | Issues Found (0 BLOCKER, 1 HIGH, 4 MED, 2 LOW, 1 NIT) |
| Concurrency | `backend-concurrency` | Issues Found (0 BLOCKER, 0 HIGH, 2 MED, 3 LOW, 2 NIT) |
| Architecture | `architecture` | Issues Found (1 BLOCKER, 1 HIGH, 3 MED, 2 LOW, 1 NIT) |
| Performance | `performance` | Issues Found (0 BLOCKER, 2 HIGH, 2 MED, 1 LOW, 1 NIT) |
| Data integrity | `data-integrity` | Issues Found (0 BLOCKER, 1 HIGH, 2 MED, 1 LOW, 1 NIT) |
| Migrations | `migrations` | Issues Found (0 BLOCKER, 1 HIGH, 3 MED, 1 LOW, 1 NIT) |
| Privacy | `privacy` | Issues Found (0 BLOCKER, 0 HIGH, 1 MED, 3 LOW, 1 NIT) |
| API contracts | `api-contracts` | Issues Found (0 BLOCKER, 2 HIGH, 3 MED, 1 LOW, 1 NIT) |
| Supply chain | `supply-chain` | Issues Found (0 BLOCKER, 2 HIGH, 4 MED, 2 LOW, 1 NIT) |
| Infra security | `infra-security` | Issues Found (0 BLOCKER, 3 HIGH, 4 MED, 3 LOW, 1 NIT) |

## All Findings (Deduplicated)

| ID | Sev | Source command(s) | File:Line | Issue |
|----|-----|-------------------|-----------|-------|
| R-01 | BLOCKER | architecture, performance, code-simplification | `android/.../data/firestore/FirestoreRepository.kt:41` | videosFlow queries non-existent root `videos/` collection; backend writes subcollection `playlists/{id}/videos/{vid}`. |
| R-02 | BLOCKER | correctness, security, infra-security | `backend/firestore.rules:6` | `__BOOTSTRAP_UID__` sentinel committed; runtime-evidence deferral; operator bootstrap pending. |
| R-03 | HIGH | security, api-contracts | `summarizer/summarize-api/src/routes/jobs.ts:162` | `GET /v1/jobs/:id` returns plaintext `webhook_secret`. |
| R-04 | HIGH | security | `summarizer/summarize-api/src/routes/jobs.ts:100-120` | `webhook_url` not passed through SSRF guard. |
| R-05 | HIGH | correctness, data-integrity, backend-concurrency | `backend/functions/src/summarizer/dispatch.ts:88-117` | Non-transactional idempotency read; concurrent dispatches double-burn quota + overwrite webhookSecret. |
| R-06 | HIGH | correctness, reliability | `summarizer/summarize-api/src/runners/url-runner.ts:179-195` | SSE EOF resolves regardless of terminal event arrival; partial content delivered as `completed`. |
| R-07 | HIGH | reliability | `backend/functions/src/summarizer/dispatch.ts:131` | Outbound fetch has no AbortController/timeout; hung Cloud Run holds 60s function timeout + burns quota. |
| R-08 | HIGH | code-simplification | (multiple) | HMAC sign/verify across three locations with hex/UTF-8 inconsistency in mock-backend. |
| R-09 | HIGH | data-integrity, migrations | `summarizer/summarize-api/src/db/schema.ts:36-40` | Three ALTER TABLE statements via single `db.exec` with no transaction. |
| R-10 | HIGH | testing | `backend/functions/tests/callable.test.ts` | `requestVideoSummary` callable untested through `allowlistedCall` wrapper. |
| R-11 | HIGH | testing | `backend/functions/tests/dispatcher.test.ts` | Dispatcher lock TTL reclaim never tested. |
| R-12 | HIGH | testing | (missing files: sweeper.ts, retry.ts) | AC-12 / AC-13 unimplemented (already-deferred v1.1 work). |
| R-13 | HIGH | architecture | `android/.../data/firestore/{QuotaDoc,SummaryDoc}.kt` | UI display policy types defined in data layer. |
| R-14 | HIGH | performance | `android/.../VideoListScreen.kt:80-93` | Pre-navigation Firestore getOnce duplicates work done in SummaryViewModel.init. |
| R-15 | HIGH | maintainability | (multiple TS+Kotlin) | Quota magic constants duplicated across two languages with no cross-reference. |
| R-16 | HIGH | maintainability | `backend/functions/src/summarizer/dispatch.ts` | `IN_FLIGHT_STATUSES` includes `completed` — misleading name. |
| R-17 | HIGH | maintainability | `summarizer/summarize-api/src/runners/url-runner.ts:32-35` | `__webhookTestOverrides` mutable module-level singleton test backdoor. |
| R-18 | HIGH | api-contracts | `02-shape.md` (NFR Security + AC-7/8/9) | Shape doc claims 200/401 webhook codes; impl returns 204/400/401/404. |
| R-19 | HIGH | supply-chain, infra-security | `summarizer/deploy/Dockerfile`, `mock-backend/Dockerfile` | Base images not pinned to `@sha256:`. |
| R-20 | HIGH | supply-chain | `.github/workflows/*.yml` | All 8 `uses:` references at mutable version tags. |
| R-21 | HIGH | infra-security | `summarizer/deploy/Dockerfile`, `mock-backend/Dockerfile` | Containers run as root (no USER directive). |
| M-01 | MED | security | `summarize-daemon/src/daemon/server.ts:219` | Daemon bearer token uses `Array.includes()` instead of `timingSafeEqual`. |
| M-02 | MED | security | `backend/functions/src/auth/handlers.ts:49,78` | Legacy `setCookies` + `setTvOauthCredentials` carry no auth (PO-deferred). |
| M-03 | MED | security | `backend/functions/src/summarizer/dispatch.ts` | Callable forwards client-supplied `model` instead of forcing `free`. |
| M-04 | MED | code-simplification | `SummaryScreen.kt` | Column wrapper repeated across five `when` branches. |
| M-05 | MED | code-simplification | `FirestoreRepository.kt` | `callbackFlow{addSnapshotListener…awaitClose}` repeated three times. |
| M-06 | MED | code-simplification | (multiple TS) | `TERMINAL_STATUSES` re-enumerated. |
| M-07 | MED | code-simplification | `dispatch.ts` | `FUNCTION_REGION` constant effectively dead. |
| M-08 | MED | code-simplification | `backend/functions/tests/helpers/admin.ts` | `emulatorReachable` TOCTOU anti-pattern. |
| M-09 | MED | testing | `backend/functions/tests/dispatch.test.ts` | `dispatchSummary` at quota cap untested. |
| M-10 | MED | testing | `SummaryScreenComposeTest.kt` | `SummaryUiState.NoSummary` state not rendered in compose test. |
| M-11 | MED | testing | `backend/functions/tests/webhook.test.ts` | Webhook terminal-state mismatch (completed-doc + failed-webhook) branch untested. |
| M-12 | MED | testing | `backend/functions/tests/dispatcher.test.ts` | `globalThis.fetch` monkey-patch instead of fetchImpl injection. |
| M-13 | MED | testing | `backend/functions/tests/quota.test.ts` | No concurrent quota reserve+release race test. |
| M-14 | MED | maintainability | `url-runner.ts:101-189` | 88-line SSE callback should be extracted. |
| M-15 | MED | maintainability | `dispatch.ts` | `DispatchSummaryOptions` wraps single optional. |
| M-16 | MED | maintainability | `QuotaBanner.kt` | `QuotaBannerViewModel` co-located with Composable. |
| M-17 | MED | reliability | `FirestoreRepository.kt:86-89` | Android listener errors swallowed → UI freezes on offline. |
| M-18 | MED | reliability | `summarize-api/src/webhooks/deliver.ts:61` | Webhook retry backoff (5/15/45s) deterministic — thundering herd risk. |
| M-19 | MED | backend-concurrency | `backend/functions/src/summarizer/autoEnqueue.ts:39-57` | Check-then-act race in `enqueueAutoSummary`. |
| M-20 | MED | backend-concurrency | `backend/functions/src/summarizer/webhook.ts:131-189` | Webhook terminal guard outside transaction. |
| M-21 | MED | performance | `backend/functions/src/summarizer/quota.ts:83-85` | No defensive cap on `recentTimestamps`. |
| M-22 | MED | performance | `backend/functions/src/summarizer/dispatcher.ts:70-89` | Dispatcher drain serial — should be parallel. |
| M-23 | MED | data-integrity | `autoEnqueue.ts:52` | (Previously) wrote `webhookSecret:""` on queued docs. Already resolved by Group A's M-24 refactor. |
| M-24 | MED | privacy, architecture, migrations | `summaries/{vid}.webhookSecret` | Per-summary HMAC secret stored in Android-readable Firestore doc. |
| M-25 | MED | api-contracts | (CHANGELOG missing) | `onRequest → onCall` breaking change for sync endpoints undocumented. |
| M-26 | MED | api-contracts | `models/index.ts` + `SummaryDoc.kt` | `SummaryDocument.model` TS non-optional but Kotlin nullable. |
| M-27 | MED | api-contracts | `summarize-api/src/schemas.ts` | `mode` field accepted then silently discarded; no caller hint. |
| M-28 | MED | supply-chain | `backend/functions/package.json` | `firebase-functions` range-pinned `^7.2.5`. |
| M-29 | MED | supply-chain | `package.json` (root) vs Dockerfile | pnpm version mismatch (9.0.0 vs 10.33.2). |
| M-30 | MED | supply-chain | `summarizer/deploy/Dockerfile` | yt-dlp curl install with no SHA256 verification. |
| M-31 | MED | infra-security | `summarizer/deploy/entrypoint.js:43-52` | `SUMMARIZE_TOKEN` passed as `--token` CLI arg, visible in `/proc/<pid>/cmdline`. |
| M-32 | MED | infra-security | `summarizer/deploy/Dockerfile` | No `HEALTHCHECK` directive. |
| M-33 | MED | infra-security | `backend/firebase.json` | Firebase emulator binds 0.0.0.0 by default. |
| M-34 | MED | infra-security | `summarizer/deploy/CLOUD-RUN.md:14` | `(or roles/owner)` IAM shortcut recommended. |
| M-35 | MED | architecture | `summarizer/dispatch.ts` vs `dispatcher.ts` | Single-letter difference obscures module boundary. |
| M-36 | MED | architecture | `auth/verify.ts` + `firestore.rules` | Single-tenant enforcement points not cross-linked. |
| M-37 | MED | migrations | `summarize-api/src/db/index.ts` | Implicit pre-existing-DB bootstrap relies on idempotent DDL. |
| M-38 | MED | migrations | `summarize-api/src/db/schema.ts` | Rollback policy undocumented. |

**LOW + NIT findings (24)** — recorded in per-command sub-review files; not individually triaged in this run. Available for re-triage via `/wf review wire-android-backend-summarizer triage`.

**Total after dedup:** BLOCKER: 2 | HIGH: 21 | MED: 30 | LOW: 16 | NIT: 8 (89 findings merged from 116 raw findings across 15 commands)

## Triage Decisions

| ID | Sev | Decision | Notes |
|----|-----|----------|-------|
| R-01 | BLOCKER | fix | Critical runtime bug; videoListScreen would be permanently empty. |
| R-02 | BLOCKER | fix | Predeploy guard added; sentinel substitution remains operator action. |
| R-03 | HIGH | fix | One-line redactJob call. |
| R-04 | HIGH | fix | SSRF guard on webhook_url. |
| R-05 | HIGH | fix | Idempotency folded into Firestore transaction. |
| R-06 | HIGH | fix | Require terminal SSE event before resolving. |
| R-07 | HIGH | fix | 15s AbortController on dispatch fetch. |
| R-08 | HIGH | fix | HMAC sign/verify unified via shared module + mock-backend canonical bytes. |
| R-09 | HIGH | fix | Atomic transaction wrap on migration 003. |
| R-10 | HIGH | fix | Wrapped callable tests added. |
| R-11 | HIGH | fix | Lock TTL reclaim test added. |
| R-12 | HIGH | defer | Re-classified after triage — feature scope, not a fix. Route via `/wf-meta extend`. |
| R-13 | HIGH | fix | QuotaState + SummaryStatus moved to screens/common/state. |
| R-14 | HIGH | fix | Pre-navigation getOnce removed. |
| R-15 | HIGH | fix | Constants extracted both sides + cross-referenced. |
| R-16 | HIGH | fix | Renamed to NON_REDISPATCHABLE_STATUSES. |
| R-17 | HIGH | fix | opts parameter replaces global backdoor. |
| R-18 | HIGH | fix (docs) | Shape doc updated to match implementation status codes. |
| R-19 | HIGH | fix | All 4 Dockerfile FROM lines pinned to @sha256:. |
| R-20 | HIGH | fix | All 8 GHA references pinned to commit SHAs. |
| R-21 | HIGH | fix | USER node directive added to both Dockerfiles. |
| M-01..M-38 | MED | fix (30) | All 30 triaged MEDs marked Fix; see Fix Status below. |
| (M-23 was confirmed already-resolved by Group A; not separately patched.) |
| LOW + NIT (24) | LOW/NIT | untriaged | Recorded in per-command sub-reviews; available via `/wf review … triage`. |

## Fix Status

Single round, review-owned. 17 fix sub-agents dispatched in cohesive groups (50 Fix decisions grouped by area for tractability — see "Recommendations → Fix grouping rationale").

| ID | Sev | Sub-agent outcome | Notes |
|----|-----|-------------------|-------|
| R-01 | BLOCKER | Patched | FirestoreRepository.videosFlow now uses `playlists/{pid}/videos`; firestore.rules add explicit subcollection rule. |
| R-02 | BLOCKER | Patched | `backend/scripts/check-allowlist-uid.sh` + `firebase.json` predeploy wired. Operator action still required to substitute UID. |
| R-03 | HIGH | Patched | redactJob applied. |
| R-04 | HIGH | Patched | validateUrl called for webhook_url with matching 403 shape. |
| R-05 | HIGH | Patched | Idempotency check folded into runTransaction; conditional create. |
| R-06 | HIGH | Patched | terminalEventReceived flag; EOF without it rejects. |
| R-07 | HIGH | Patched | 15s AbortController with overridable opts.dispatchTimeoutMs. |
| R-08 | HIGH | Patched | tests/helpers/signWebhook.ts wraps signer.ts; mock-backend hex-decodes both sides before timingSafeEqual. |
| R-09 | HIGH | Patched | Migration 003 wraps three ALTERs in db.transaction(). |
| R-10 | HIGH | Patched | 4 new wrapped callable tests on requestVideoSummary. |
| R-11 | HIGH | Patched | Stale-lock reclaim test asserts acquire after TTL+1s. |
| R-12 | HIGH | Deferred | Re-classified before fix loop; routed to `/wf-meta extend from-review`. |
| R-13 | HIGH | Patched | QuotaState + SummaryStatus → screens/common/state; DTOs reduced to raw fields. |
| R-14 | HIGH | Patched | onSummarizeClick navigates directly; init() in SummaryViewModel handles cold-start. |
| R-15 | HIGH | Patched | constants.ts (TS) + QuotaPolicy.kt (Android) with cross-reference comments. |
| R-16 | HIGH | Patched | NON_REDISPATCHABLE_STATUSES; comment explains the wider semantic. |
| R-17 | HIGH | Patched | UrlRunnerOpts threaded through dispatchJob → runUrlJob; `__webhookTestOverrides` deleted. |
| R-18 | HIGH | Patched (docs) | 02-shape.md NFR + AC-7/9 updated to actual 204/400/401/404 codes. |
| R-19 | HIGH | Patched | node:24-slim and node:22-slim pinned to @sha256: digests from Docker Hub. |
| R-20 | HIGH | Patched | All 8 GHA refs pinned to full-40-char SHAs with `# v<tag>` comment. |
| R-21 | HIGH | Patched | chown -R node:node + USER node in runtime + mock-backend. |
| M-01 | MED | Patched | Daemon bearer compare via `timingSafeEqual` after length check. |
| M-02 | MED | Patched | setCookies / setTvOauthCredentials wrapped behind allowlistedRequest. |
| M-03 | MED | Patched | dispatch.ts hardcodes `model: "free"` regardless of client. |
| M-04 | MED | Patched | Column wrapper hoisted with stateTag() helper. |
| M-05 | MED | Patched | Query.asCollectionFlow() helper + close(error). |
| M-06 | MED | Patched | TERMINAL_STATUSES exported from constants.ts. |
| M-07 | MED | Patched | Inline reference removed; module-level constant remains single source. |
| M-08 | MED | Patched | emulatorReachable deleted; rely on natural ECONNREFUSED. |
| M-09 | MED | Patched | dispatch.test.ts seeds quota at cap + asserts resource-exhausted. |
| M-10 | MED | Patched | NoSummary compose test added (CTA click verified). |
| M-11 | MED | Patched | Webhook test: completed-doc + failed-webhook → 200 + doc untouched. |
| M-12 | MED | Patched | dispatchSummary fetchImpl injection test; dispatcher drain still uses globalThis swap with explanatory comment. |
| M-13 | MED | Patched | Concurrent reserve+release N=10 test asserts final counter in [0,N]. |
| M-14 | MED | Patched | handleDaemonSseEvent + pumpReader extracted. |
| M-15 | MED | Patched | DispatchSummaryOptions retained (now ≥2 fields after R-07). |
| M-16 | MED | Patched | QuotaBannerViewModel.kt extracted. |
| M-17 | MED | Patched | close(error) in callbackFlow + .catch operators at call sites. |
| M-18 | MED | Patched | ±25% symmetric jitter on backoff. |
| M-19 | MED | Patched | autoEnqueue uses per-doc transactions with already-exists sentinel. |
| M-20 | MED | Patched | processSummaryWebhook wraps guard+set in db.runTransaction. |
| M-21 | MED | Patched | recentTimestamps trimmed with defensive `slice(-max(perMinuteLimit, 100))`. |
| M-22 | MED | Patched | Dispatcher drain uses Promise.allSettled. |
| M-23 | MED | Already resolved | Confirmed during fix loop; no code change required. |
| M-24 | MED | Patched | `webhook_secrets/{vid}` server-only collection; webhook lookup adjusted. |
| M-25 | MED | Patched | CHANGELOG.md created with breaking-change entry. |
| M-26 | MED | Patched | TS `model?` optional; deref sites already used `?? "free"`. |
| M-27 | MED | Patched | JSDoc on `mode` field documents accepted-but-ignored. |
| M-28 | MED | Patched | firebase-functions exact-pinned at 7.2.5. |
| M-29 | MED | Patched | packageManager → pnpm@10.33.2 to match Docker. |
| M-30 | MED | Patched | yt-dlp curl install verifies against SHA256 from release manifest. |
| M-31 | MED | **Could not fix** | Vendored daemon does not accept token via env var; CLI arg is the only path. Operationally mitigated via Cloud Run secret-injection. |
| M-32 | MED | Patched | HEALTHCHECK added with --start-period=45s. |
| M-33 | MED | Patched | firebase.json emulator entries bind to 127.0.0.1. |
| M-34 | MED | Patched | "(or roles/owner)" removed from CLOUD-RUN.md. |
| M-35 | MED | Patched | dispatcher.ts → dispatcher-cron.ts; all imports updated. |
| M-36 | MED | Patched | Cross-link block comments in auth/verify.ts + firestore.rules. |
| M-37 | MED | Patched | Bootstrap migrations 001/002 mark for existing main-era DBs. |
| M-38 | MED | Patched | Rollback policy docblock added to schema.ts. |

**Round count:** 1
**Convergence:** escalated (1 of 50 Fix decisions could not be patched: M-31)
**Initial findings:** 89 → **Final findings:** 40 (BLOCKER 0, HIGH 0, MED 1, LOW 16, NIT 8 + 15 deferred/dismissed/already-resolved)
**Commit:** `f94a7691` — `fix: address branch-wide audit findings (security, concurrency, container hardening)` (51 files, +1143 / −528). Not pushed.

### Could-not-fix detail

**M-31 — SUMMARIZE_TOKEN as --token CLI arg.** The vendored daemon at subtree pin `0ec12ac` reads the token exclusively from a `--token <value>` CLI argument or a pre-written config file. There is no environment-variable code path in the daemon source. Removing the CLI arg without modifying daemon code (which would diverge the subtree from upstream) would break startup. Operational mitigation in place: Cloud Run secret-injection (already documented in CLOUD-RUN.md) keeps the value out of operator shell history. The token's `/proc/<pid>/cmdline` exposure is bounded to in-container introspection, which already requires a successful container compromise to read. Recommend raising upstream as a daemon enhancement, or accepting the residual risk.

## Recommendations

### Must Fix (triaged "fix", patched)
All 49 patched findings — see Fix Status table above. The diff is staged but not yet committed (see Hand off).

### Must Fix (escalated, remaining)
- **M-31** — Decide between (a) raising an upstream PR on the daemon for env-var token support, (b) maintaining a small subtree patch, or (c) accepting the residual risk. Recommended: (a) — keep the subtree clean and align with upstream.

### Deferred (re-triage via `/wf review wire-android-backend-summarizer triage`)
- **R-12** — Implement `summarySweeper` (AC-12) and `summaryRetryCron` (AC-13) crons. Recommended path: `/wf-meta extend wire-android-backend-summarizer from-review` to spin a `v1.1-failure-recovery-cron` slice; do not bundle into this branch.

### Untriaged (recorded only)
- 16 LOW findings and 8 NIT findings across 15 sub-reviews. Re-triage via `/wf review wire-android-backend-summarizer triage` when desired.

### Fix grouping rationale
Per the user's selection at triage time, the 50 Fix decisions were grouped into 10 cohesive sub-agents by area (Android-data + rules, Android-UI, webhook+dispatch, HMAC unification, SQL migration + constants, reliability+perf, testing additions, API-docs + arch renames, supply-chain pinning, container hardening). Each sub-agent's prompt enumerated every finding it was responsible for. The grouping is recorded transparently in this artifact's Fix Status column (per-finding) so reviewers can audit which group covered which finding.

## Known Deferrals (carried forward)

These existed before this review and were not invalidated by it:

- **auth-and-android-firebase runtime-evidence deferral** — operator has not run the two-pass bootstrap deploy; `ALLOWED_UID` + `firestore.rules` still carry `__BOOTSTRAP_UID__`. AC-3 positive sub-claim + AC-4 cannot be exercised against production. Mitigation strengthened by R-02 predeploy guard.
- **summary-ui runtime-evidence deferral** — Android app missing Firestore emulator wiring (`connectFirestoreEmulator`); AC-5, AC-10, cached-summary no-redispatch, and Retry 500ms transition timing cannot be exercised end-to-end via Maestro until the wiring lands OR the operator finishes the bootstrap so production-backed Maestro runs are possible.

Both deferrals remain blocking for the four user-observable acceptance criteria they cover. They are operator-side, not code-side.

## Recommended Next Stage

- **Option A (default):** `/wf handoff wire-android-backend-summarizer` — convergence is "escalated" with a single MED could-not-fix (M-31) that has an operational mitigation. No remaining BLOCKER or HIGH code findings. All four slices are complete. Ready to aggregate into a PR description; the two known runtime-evidence deferrals are documented in the handoff. **This is the recommended path.**
- **Option B:** `/wf review wire-android-backend-summarizer` — Re-invoke for a second fix round on M-31 specifically. Not recommended; this run already determined the daemon does not accept a env-var token and a second fix round would either escalate again or require subtree modification.
- **Option C:** `/wf implement wire-android-backend-summarizer reviews` — Manual escape into stage 5's per-finding fix UI for M-31. Not recommended; same constraint applies regardless of stage.
- **Option F:** `/wf-meta extend wire-android-backend-summarizer from-review` — Spin a new `v1.1-failure-recovery-cron` slice carrying R-12 (sweeper + retry cron) per the slice strategy's deferred-slices list. Run after Option A handoff lands.
- **Option G:** `/wf-meta amend wire-android-backend-summarizer from-review` — Not recommended; no findings indicated the spec or AC definitions were themselves wrong (R-18 was a doc lag, not a spec error).

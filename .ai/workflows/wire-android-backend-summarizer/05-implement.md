---
schema: sdlc/v1
type: implement-index
slug: wire-android-backend-summarizer
status: complete
stage-number: 5
created-at: "2026-05-18T10:50:00Z"
updated-at: "2026-05-22T21:24:23Z"
slices-implemented: 5
slices-total: 5
metric-total-files-changed: 114
metric-total-lines-added: 6612
metric-total-lines-removed: 611
extension-rounds: 1
tags: [android, firebase, cloud-run, summarizer, openrouter, multi-component]
refs:
  index: 00-index.md
  plan-index: 04-plan.md
slices:
  - slug: auth-and-android-firebase
    status: complete
    implement: 05-implement-auth-and-android-firebase.md
  - slug: summarizer-container
    status: complete
    implement: 05-implement-summarizer-container.md
  - slug: summary-orchestration
    status: complete
    implement: 05-implement-summary-orchestration.md
  - slug: summary-ui
    status: complete
    implement: 05-implement-summary-ui.md
  - slug: failure-recovery-cron
    status: complete
    implement: 05-implement-failure-recovery-cron.md
    branch: feat/failure-recovery-cron
    source: from-review
    extension-round: 1
next-command: wf-verify
next-invocation: "/wf verify wire-android-backend-summarizer failure-recovery-cron"
---

# Implement Index — wire-android-backend-summarizer

## Status

| Slice | State | Record |
|-------|-------|--------|
| `auth-and-android-firebase` | complete | [05-implement-auth-and-android-firebase.md](05-implement-auth-and-android-firebase.md) |
| `summarizer-container` | complete | [05-implement-summarizer-container.md](05-implement-summarizer-container.md) |
| `summary-orchestration` | complete | [05-implement-summary-orchestration.md](05-implement-summary-orchestration.md) |
| `summary-ui` | complete | [05-implement-summary-ui.md](05-implement-summary-ui.md) |
| `failure-recovery-cron` (extension round 1, parallel branch) | complete | [05-implement-failure-recovery-cron.md](05-implement-failure-recovery-cron.md) |

## Cross-Slice Integration Notes

- **Allowlist gate landed.** `auth/verify.ts` exports `allowlistedCall<TIn, TOut>`
  + `requireAllowlistedUid` + `ALLOWED_UID` (`defineString` default
  `__BOOTSTRAP_UID__`). Slice 3's `summarizer/dispatch.ts` imports from this
  module unchanged.
- **Firestore rules in place.** `playlists/`, `videos/`, `sync_state/` read for
  allowlisted; `tokens/` hard-deny; catch-all `if false`. Slice 3 must insert
  `summaries/` + `quota/` rule blocks **before** the catch-all.
- **Emulator scaffold ready.** `firebase.json` emulator block + `vitest.config.ts`
  + `tests/setup.ts` + `tsconfig.test.json` already in place; slice 3 extends
  the test suite rather than creating it.
- **Bootstrap procedure documented.** `docs/operations/bootstrap-allowlisted-uid.md`
  is single source of truth for first-deploy bring-up. Slice 2 and slice 3 add
  their deploy steps to the same runbook.
- **Android Firebase plumbing landed.** BOM 33.4.0 (post verify-time pin),
  Hilt `AppModule` exposing `FirebaseAuth` / `FirebaseFirestore` /
  `FirebaseFunctions`, `FirebaseAuthBridge`, and the `FirestoreRepository`
  snapshot-listener pattern. Slice 4's `SummaryRepository` /
  `QuotaRepository` follow the same pattern.
- **`QuotaBanner` placeholder reserved.** Layout slot in PlaylistScreen present;
  slice 4 replaces the no-op body.
- **Summarizer container shipped.** Unified multi-stage Dockerfile at
  `summarizer/deploy/Dockerfile`, Cloud-Run-bound `entrypoint.js`,
  docker-compose harness with a signature-verifying mock backend, two
  YouTube fixtures, and a runbook. Subtree pinned at upstream `0ec12acc`
  (squash `8fcef862`).
- **Webhook signing contract is byte-exact.** Canonical bytes
  `${unix_t}.${raw_body}`, header `X-Summarizer-Signature: t=<n>,v1=<sha256-hex>`,
  300 s replay window. Slice 3's `summaryWebhook` verifier MUST read
  `req.rawBody` BEFORE any JSON parse and re-derive the same string —
  any whitespace or key-order drift breaks every in-flight webhook.
  The shared fixture vector lives in
  `summarizer/summarize-api/tests/webhook-signer.test.ts`; slice 3 should
  re-use it.
- **`client_job_id` round-trip is the idempotency contract.** Slice 3
  sends `videoId` as the client_job_id; the summarizer persists it on
  `jobs.client_job_id`; the webhook returns it; slice 3 looks up
  `summaries/{videoId}` by that value.
- **schema_migrations runner is in place.** Future db column additions
  add a new named entry to `NAMED_MIGRATIONS`; the runner is idempotent
  by name.
- **Summary orchestration wired end-to-end.** `requestVideoSummary`
  callable + `summaryWebhook` HMAC verifier + `summaryDispatcher` cron +
  `enqueueAutoSummary` hook into every sync entry point. Quota math is
  transactional (pessimistic-pre-increment). Slice 3's webhook verifier
  reads `req.rawBody` before any JSON parse — byte-exact contract with
  slice 2 holds.
- **Auto-enqueue lives in every sync path.** Sync helpers now return
  `videoIds: string[]`; `index.ts` strips it from the callable wire
  response but feeds it to `enqueueAutoSummary` (guarded by
  `autoEnqueueSafe` so auto-enqueue failure cannot fail a sync).
- **Slice 4 has its upstream.** `summaries/{videoId}` and
  `quota/openrouter` are now readable by the allowlisted operator (rule
  block lands here, before the catch-all). Android repository layer
  follows the same `callbackFlow + awaitClose` shape slice 1 set up.
- **Summary UI lands the user-visible head of the pipeline.**
  VideoDetailScreen + SummaryScreen + app-global QuotaBanner observe
  `summaries/{videoId}` + `quota/openrouter` via `SummaryRepository` +
  `QuotaRepository` (extensions of the slice-1 repository module). The
  Summarize affordance on every video tile reads
  `rememberQuotaState()` for its enabled/disabled binding so banner
  and CTAs move together. Markdown rendering via
  `com.github.jeziellago:compose-markdown:0.7.2` from JitPack (plan
  GAV `dev.jeziellago:...:0.5.7` did not exist on Maven Central —
  resolved to the upstream JitPack coordinate).
- **All 4 slices implemented.** This branch now carries the full
  Android-↔-Backend-↔-Summarizer wiring; verify pass for slice 4
  completes the slug.
- **Extension round 1: `failure-recovery-cron` landed on a parallel
  branch.** `feat/failure-recovery-cron` forked from the post-review
  HEAD of `feat/wire-android-backend-summarizer` and adds the hourly
  `summarySweeper` + daily `summaryRetryCron` covering AC-12 / AC-13 /
  AC-14 / AC-15 / AC-16 from the shape doc. The parallel branch does
  NOT gate v1.0 handoff/ship of the original four slices — it rebases
  to `main` after v1.0 merges. Surfaced one cross-slice find: the
  same `pending`-stranding race the retry rollback fixes also affects
  `dispatcher-cron.ts`'s per-minute-cap path (flagged for follow-up,
  not patched in this slice).

## Recommended Next Stage

- **Option A (default):** `/wf verify wire-android-backend-summarizer failure-recovery-cron` — verify the parallel-branch extension slice. All thirteen new tests green; remaining work is AC-coverage confirmation + triage of pre-existing test failures on the parent branch.
- **Option B:** `/wf verify wire-android-backend-summarizer summary-ui` — return to verifying slice 4 on the v1.0 branch. Compile + instrumented-test compile are green; live execution needs the Firebase emulator + a connected Android device/emulator.
- **Option C:** `/wf review wire-android-backend-summarizer summary-ui` — skip verify if the emulator-and-device gate is judged the verify scope's responsibility. Not recommended (AC-5's 500ms assertion is the verify gate).
- **Option D:** `/wf-quick probe wire-android-backend-summarizer` — clear the slice-1 runtime-evidence deferral after the operator runs the bootstrap two-pass deploy.

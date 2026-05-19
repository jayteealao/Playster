---
schema: sdlc/v1
type: implement-index
slug: wire-android-backend-summarizer
status: in-progress
stage-number: 5
created-at: "2026-05-18T10:50:00Z"
updated-at: "2026-05-19T21:35:43Z"
slices-implemented: 3
slices-total: 4
metric-total-files-changed: 78
metric-total-lines-added: 4657
metric-total-lines-removed: 586
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
    status: pending
    implement: 05-implement-summary-ui.md
next-command: wf-verify
next-invocation: "/wf verify wire-android-backend-summarizer summary-orchestration"
---

# Implement Index — wire-android-backend-summarizer

## Status

| Slice | State | Record |
|-------|-------|--------|
| `auth-and-android-firebase` | complete | [05-implement-auth-and-android-firebase.md](05-implement-auth-and-android-firebase.md) |
| `summarizer-container` | complete | [05-implement-summarizer-container.md](05-implement-summarizer-container.md) |
| `summary-orchestration` | complete | [05-implement-summary-orchestration.md](05-implement-summary-orchestration.md) |
| `summary-ui` | pending | — |

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

## Recommended Next Stage

- **Option A (default):** `/wf verify wire-android-backend-summarizer summary-orchestration` — boot the Firestore emulator and run `pnpm --filter functions test` against the new six suites. Implementation just landed; verify is the natural next gate.
- **Option B:** `/wf implement wire-android-backend-summarizer summary-ui` — start slice 4. Slice 3 is now its upstream contract producer; the Android side can observe `summaries/` and `quota/openrouter` via Firestore listeners.
- **Option G:** `/wf-quick probe wire-android-backend-summarizer` — clear the slice-1 runtime-evidence deferral after the operator runs the bootstrap two-pass deploy.

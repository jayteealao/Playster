---
schema: sdlc/v1
type: implement-index
slug: wire-android-backend-summarizer
status: in-progress
stage-number: 5
created-at: "2026-05-18T10:50:00Z"
updated-at: "2026-05-18T10:50:00Z"
slices-implemented: 1
slices-total: 4
metric-total-files-changed: 30
metric-total-lines-added: 1717
metric-total-lines-removed: 427
tags: [android, firebase, cloud-run, summarizer, openrouter, multi-component]
refs:
  index: 00-index.md
  plan-index: 04-plan.md
slices:
  - slug: auth-and-android-firebase
    status: complete
    implement: 05-implement-auth-and-android-firebase.md
  - slug: summarizer-container
    status: pending
    implement: 05-implement-summarizer-container.md
  - slug: summary-orchestration
    status: pending
    implement: 05-implement-summary-orchestration.md
  - slug: summary-ui
    status: pending
    implement: 05-implement-summary-ui.md
next-command: wf-verify
next-invocation: "/wf verify wire-android-backend-summarizer auth-and-android-firebase"
---

# Implement Index — wire-android-backend-summarizer

## Status

| Slice | State | Record |
|-------|-------|--------|
| `auth-and-android-firebase` | complete | [05-implement-auth-and-android-firebase.md](05-implement-auth-and-android-firebase.md) |
| `summarizer-container` | pending | — |
| `summary-orchestration` | pending | — |
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
- **Android Firebase plumbing landed.** BOM 34, Hilt `AppModule` exposing
  `FirebaseAuth` / `FirebaseFirestore` / `FirebaseFunctions`, `FirebaseAuthBridge`,
  and the `FirestoreRepository` snapshot-listener pattern. Slice 4's
  `SummaryRepository` / `QuotaRepository` follow the same pattern.
- **`QuotaBanner` placeholder reserved.** Layout slot in PlaylistScreen present;
  slice 4 replaces the no-op body.

## Recommended Next Stage

- **Option A (default):** `/wf verify wire-android-backend-summarizer auth-and-android-firebase` — verify slice 1 before opening slice 2.
- **Option B:** `/wf implement wire-android-backend-summarizer summarizer-container` — parallel-track slice 2 in a separate worktree. Slices 1 and 2 share no source files; safe to develop concurrently and merge sequentially.

---
schema: sdlc/v1
type: verify-index
slug: wire-android-backend-summarizer
status: in-progress
stage-number: 6
created-at: "2026-05-18T16:43:28Z"
updated-at: "2026-05-22T21:50:00Z"
status: complete
slices-verified: 5
slices-total: 5
extension-rounds: 1
tags: [android, firebase, cloud-run, summarizer, openrouter, multi-component]
refs:
  index: 00-index.md
  implement-index: 05-implement.md
next-command: wf-review
next-invocation: "/wf review wire-android-backend-summarizer failure-recovery-cron"
---

# Verify Index — wire-android-backend-summarizer

## Status

| Slice | Result | Convergence | Interactive | Record |
|-------|--------|-------------|-------------|--------|
| `auth-and-android-firebase` | partial | converged (1 round) | deferred (bootstrap state) | [06-verify-auth-and-android-firebase.md](06-verify-auth-and-android-firebase.md) |
| `summarizer-container`       | pass    | converged (2 extended fix-round bursts) | required (all 4 user-observable AC evidenced) | [06-verify-summarizer-container.md](06-verify-summarizer-container.md) |
| `summary-orchestration`      | pass    | converged (1 round) | required (all 5 user-observable AC evidenced) | [06-verify-summary-orchestration.md](06-verify-summary-orchestration.md) |
| `summary-ui`                 | partial | converged (1 round) | deferred (bootstrap + emulator wiring) | [06-verify-summary-ui.md](06-verify-summary-ui.md) |
| `failure-recovery-cron` (extension round 1, parallel branch) | pass    | not-needed (zero issues) | not-applicable (server-side only) | [06-verify-failure-recovery-cron.md](06-verify-failure-recovery-cron.md) |

## Cross-Slice Notes

- **Verify-owned fix landed (slice 1).** `firebase-bom` pinned `34.0.0` → `33.4.0`
  (commit `f4cf9422`) to match the project's Kotlin 1.9.22 toolchain.
  Sibling slices that touch Android dependencies should keep this pin
  in mind; a future Kotlin 2.x bump unlocks the newer BOM line.
- **Verify-owned fixes landed (slice 2).** Eight Dockerfile + compose +
  harness bringup defenses landed at verify-time:
  - native build deps (`python3 make g++`) in daemon-build + api-build
  - `CI=true` so `pnpm prune` doesn't TTY-prompt
  - `pnpm prune --prod --ignore-scripts` so the daemon's `prepare`
    lifecycle doesn't re-run `rimraf` after dev-dep pruning
  - top-level `.dockerignore` excluding `**/node_modules`, `**/dist`,
    verify-evidence + env paths
  - replace `pnpm --filter X prune` with `cd X && pnpm prune` (pnpm 10.x
    rejects implicit `--recursive` on prune)
  - **runtime layout fix**: copy api-build output to `/app/summarizer/
    summarize-api` + `/app/node_modules` so pnpm's relative `../../../`
    symlinks resolve. Entrypoint updated to `import("/app/summarizer/
    summarize-api/dist/index.js")`.
  - `python3` added to runtime apt-get — `yt-dlp` from GitHub Releases
    is a Python zipapp, not a true static binary.
  - host port `8080` → `18080` to sidestep a pre-existing host-side
    node listener; matching `SUMMARIZER_URL` update in `run-harness.mjs`.
  Two harness-budget tweaks (`JOB_TIMEOUT_MS=900000`, `waitForWebhook`
  12-min) document realistic upper bounds for OpenRouter free-tier
  latency; they do not change the slice's contract.
- **Daemon contract independently confirmed AND gateway SSE bug fixed
  (slice 2).** Initial verdict deferred AC-14 on OpenRouter free-tier
  latency framing; user pushback drove a deeper investigation that
  found a real gateway protocol bug. The vendored daemon emits `done`
  and `error` SSE events (per `summarizer/summarize-daemon/src/shared/
  sse-events.ts:32-40`) but the gateway only parsed `complete`. The
  in-tree test mock was emitting `complete` too, which is why CI never
  caught the drift. Patched in round 2; mock corrected; new test added
  to cover the `error`-event path; harness re-ran and passed both
  fixtures in 108 s end-to-end. **AC-14 is no longer deferred.**
- **One runtime-evidence deferral remains active.** Slice 1 AC-3
  positive + AC-4 (bootstrap two-pass deploy pending). `/wf ship` will
  hard-block until cleared via `/wf-quick probe` or by re-verifying in
  a post-bootstrap environment. Tracked under `00-index.md`
  `runtime-evidence-deferrals`.
- **Documentation drift surfaced for review (slice 2 DOC-1).** Shape
  and slice docs claim a "youtubei-first cascade" daemon. The actual
  daemon code path is HTML+captionTracks scrape → `yt-dlp` + ASR →
  Apify (per `summarizer/summarize-daemon/packages/core/src/content/
  transcript/providers/youtube.ts`). No `youtubei.js` usage. Review
  should decide whether to retroactively amend the shape doc.
- **Webhook signing contract is byte-exact and evidenced both ways.**
  Slice 2's signer (vitest fixture vector + mock-backend at-rest verify)
  matches the contract that slice 3's verifier must implement
  (`req.rawBody` before any JSON parse). Slice 3 should re-use the
  shared fixture vector from `summarizer/summarize-api/tests/
  webhook-signer.test.ts`.

## Cross-Slice Notes (slice 3)

- **Slice 3 verified.** Vitest 45/45 against live Firestore emulator
  on `127.0.0.1:8080` (project `demo-playster`). All 5 user-observable
  AC (AC-5, AC-7, AC-8, AC-9, AC-11) carry positive interactive
  evidence; all 5 code-only AC are covered by automated tests.
- **One verify-owned fix landed (TEST-1).** `autoEnqueueSafe` was
  hardened to accept `string[] | undefined`. Commit `5009bb3b`. The
  fix only patches a test-path crash caused by slice-3's new
  `videoIds` field missing from slice-1's `syncAll()` mock — no
  production behavior change.
- **Slice-1's `callable.test.ts` is now green again.** No further
  shared-file mutations needed in this slice's verify.
- **Lint warning provenance recorded.** The single `max-len` warning
  surviving in this verify is at `src/youtube/innertube-sync.ts:398`
  (slice-1 commit `7dad00cd`), not slice 3.

## Cross-Slice Notes (slice 4)

- **Slice 4 verified.** Live on `Medium_Phone_API_36.0` AVD: 4/0/0
  Compose UI tests for `SummaryScreenComposeTest` in 10.651s; APK
  installs + launches in 2054ms with no fatals; AuthScreen renders
  cleanly; the new app-global QuotaBanner correctly stays
  zero-height in `Healthy` state so layout is undisturbed.
- **One verify-owned fix landed (LINT-1).** Removed a stale
  `net.openid.appauth.RedirectUriReceiverActivity` from
  `AndroidManifest.xml`. AppAuth was no longer on the classpath; the
  manifest declaration was a 2024-02 dead reference (predates the
  workflow). Commit `19df9976`. `:app:lintDebug` now passes.
- **Two compounding deferrals added.** AC-5 (timing), AC-10 (quota
  banner live), the cached-summary no-redispatch AC, and the Retry
  500ms timing transition are all `interactive-verification:
  deferred` for the same root cause: (a) the app lacks
  `connectFirestoreEmulator` wiring in `android/app/src/main` (so
  even a running Firebase emulator can't be targeted by the
  installed APK), and (b) production `ALLOWED_UID` is still
  `__BOOTSTRAP_UID__` per slice 1's open deferral. Cleared by
  `/wf-quick probe` after the operator runs the bootstrap two-pass
  deploy AND a follow-up adds a debug-only build flavor that calls
  `connectFirestoreEmulator`.
- **New caveat surfaced.** App-global QuotaBanner attaches the
  `QuotaRepository` listener pre-sign-in (because MainActivity
  composes before AuthScreen completes), so logcat
  `playster.summary` shows `PERMISSION_DENIED` stream errors during
  cold launch. No leak (awaitClose fires correctly) — just noise.
  Worth a follow-up to gate the listener on `FirebaseAuth.getInstance().
  currentUser != null` or to relocate the subscription post-auth.

## Cross-Slice Notes (slice 5 — extension round 1)

- **Slice 5 verified on parallel branch `feat/failure-recovery-cron`.**
  Vitest 13/13 against live Firestore emulator on `127.0.0.1:8080`. All
  five AC (AC-12, AC-13, AC-14, AC-15, AC-16) partition as code-only;
  none names a UI surface or user action. No fix loop needed —
  initial issue count was zero.
- **No interactive verification ran.** Crons fire under the Cloud
  Scheduler service account inside Firebase Functions v2; the slice
  doc explicitly excludes Android surface coverage, so adapter
  selection dropped `android` from the otherwise-matched set. Service
  adapter was not driven separately because the cron-trigger surface
  is not HTTP; emulator-backed direct-call tests are the standard
  pattern this slice followed (consistent with `tests/dispatcher.test.ts`).
- **6 parent-branch test failures persist** in the full backend
  suite (`callable.test.ts` x2, `dispatcher.test.ts` M-12 inject,
  `rules.test.ts`, `rules-summaries.test.ts`, `webhook.test.ts` AC-8
  401-vs-404). `git diff feat/wire-android-backend-summarizer..HEAD`
  on those files is empty — failures are inherited, not introduced.
  Recorded in the per-slice verify under §Gaps for traceability; no
  slice-local fix required. These should be triaged either on the
  parent branch's re-verify, by `/wf review`'s slug-wide sweep, or
  after merging `feat/failure-recovery-cron` back to a re-verified
  `main`.
- **Cross-slice find from implement record carries forward.** The
  same `pending`-stranding race the retry-cron rollback fixes also
  exists in `dispatcher-cron.ts`'s per-minute-cap path. Not patched
  in this slice (kept blast radius narrow); lives as a known
  follow-up.

## Recommended Next Stage

- **Option A (default):** `/wf review wire-android-backend-summarizer failure-recovery-cron` — extension-round 1 slice converged with `result: pass`; ready for code review on the parallel branch. Compact recommended since verify produced log-heavy test output. Review proceeds independently of the v1.0 review chain still open on slices 1-4.
- **Option B:** `/wf review wire-android-backend-summarizer summary-ui` — slice 4 already verified `partial`; v1.0 review still pending. Two deferrals carry to handoff (bootstrap-pending + emulator wiring) which ship will hard-block on.
- **Option C:** `/wf review wire-android-backend-summarizer summary-orchestration` — slice 3 already verified `pass`; review still pending.
- **Option D:** `/wf review wire-android-backend-summarizer summarizer-container` — slice 2 already verified `pass`; review still pending.
- **Option E:** `/wf review wire-android-backend-summarizer auth-and-android-firebase` — slice 1 already verified `partial`; review still pending.
- **Option G:** `/wf-quick probe wire-android-backend-summarizer` — slug-wide runtime sweep once the operator (a) runs the bootstrap two-pass deploy and (b) adds debug-build `connectFirestoreEmulator` wiring. Will clear both slice 1's and slice 4's outstanding deferrals in one pass.

---
schema: sdlc/v1
type: verify-index
slug: wire-android-backend-summarizer
status: in-progress
stage-number: 6
created-at: "2026-05-18T16:43:28Z"
updated-at: "2026-05-19T22:30:08Z"
slices-verified: 3
slices-total: 4
tags: [android, firebase, cloud-run, summarizer, openrouter, multi-component]
refs:
  index: 00-index.md
  implement-index: 05-implement.md
next-command: wf-review
next-invocation: "/wf review wire-android-backend-summarizer summary-orchestration"
---

# Verify Index — wire-android-backend-summarizer

## Status

| Slice | Result | Convergence | Interactive | Record |
|-------|--------|-------------|-------------|--------|
| `auth-and-android-firebase` | partial | converged (1 round) | deferred (bootstrap state) | [06-verify-auth-and-android-firebase.md](06-verify-auth-and-android-firebase.md) |
| `summarizer-container`       | pass    | converged (2 extended fix-round bursts) | required (all 4 user-observable AC evidenced) | [06-verify-summarizer-container.md](06-verify-summarizer-container.md) |
| `summary-orchestration`      | pass    | converged (1 round) | required (all 5 user-observable AC evidenced) | [06-verify-summary-orchestration.md](06-verify-summary-orchestration.md) |
| `summary-ui`                 | —       | —                   | —                          | (not yet verified) |

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

## Recommended Next Stage

- **Option A (default):** `/wf review wire-android-backend-summarizer summary-orchestration` — slice 3 converged with `result: pass`; all 5 user-observable AC evidenced. Move into review.
- **Option B:** `/wf review wire-android-backend-summarizer summarizer-container` — slice 2 verify already converged; not yet reviewed.
- **Option C:** `/wf review wire-android-backend-summarizer auth-and-android-firebase` — slice 1 verify already converged; not yet reviewed. Carries a bootstrap-deploy-pending deferral that shipping will hard-block on.
- **Option (parallel):** `/wf implement wire-android-backend-summarizer summary-ui` — slice 4 is now anchored by slice 3's contract producer (`summaries/{videoId}` + `quota/openrouter` readable to the allowlisted operator). Safe to start in parallel with review.
- **Option G:** `/wf-quick probe wire-android-backend-summarizer` — slug-wide runtime sweep once the operator runs the bootstrap two-pass deploy. Will clear slice 1's outstanding deferral.

---
schema: sdlc/v1
type: verify-index
slug: wire-android-backend-summarizer
status: in-progress
stage-number: 6
created-at: "2026-05-18T16:43:28Z"
updated-at: "2026-05-19T13:37:17Z"
slices-verified: 2
slices-total: 4
tags: [android, firebase, cloud-run, summarizer, openrouter, multi-component]
refs:
  index: 00-index.md
  implement-index: 05-implement.md
next-command: wf-review
next-invocation: "/wf review wire-android-backend-summarizer summarizer-container"
---

# Verify Index — wire-android-backend-summarizer

## Status

| Slice | Result | Convergence | Interactive | Record |
|-------|--------|-------------|-------------|--------|
| `auth-and-android-firebase` | partial | converged (1 round) | deferred (bootstrap state) | [06-verify-auth-and-android-firebase.md](06-verify-auth-and-android-firebase.md) |
| `summarizer-container`       | partial | converged (extended fix loop) | deferred (AC-14 OpenRouter free-tier latency) | [06-verify-summarizer-container.md](06-verify-summarizer-container.md) |
| `summary-orchestration`      | —       | —                   | —                          | (not yet verified) |
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
- **Daemon contract independently confirmed (slice 2).** Direct probe
  of `POST /v1/summarize` against a non-YouTube URL emits the expected
  `Extracting → Summarizing → meta(model=openrouter/<free>)` SSE
  sequence. The unmet AC-14 happy-path is OpenRouter free-tier 120B
  model latency on residential Docker NAT egress, not slice code.
- **Two runtime-evidence deferrals active.** Slice 1 AC-3 positive +
  AC-4 (bootstrap two-pass deploy pending). Slice 2 AC-14 (OpenRouter
  free-model latency). `/wf ship` will hard-block until both clear via
  `/wf-quick probe` or a re-verify in a capable environment. Tracked
  under `00-index.md` `runtime-evidence-deferrals`.
- **Webhook signing contract is byte-exact and evidenced both ways.**
  Slice 2's signer (vitest fixture vector + mock-backend at-rest verify)
  matches the contract that slice 3's verifier must implement
  (`req.rawBody` before any JSON parse). Slice 3 should re-use the
  shared fixture vector from `summarizer/summarize-api/tests/
  webhook-signer.test.ts`.

## Recommended Next Stage

- **Option A (default):** `/wf review wire-android-backend-summarizer summarizer-container` — slice 2 converged with `result: partial`. Move into review with the AC-14 deferral acknowledged.
- **Option B:** `/wf review wire-android-backend-summarizer auth-and-android-firebase` — re-pick slice 1 review (slice 1 verify converged earlier; not yet reviewed). Both slices are now verify-complete.
- **Option G:** `/wf-quick probe wire-android-backend-summarizer` — slug-wide probe once the operator runs the bootstrap two-pass deploy (clears slice 1 deferral) and once the container is deployed somewhere with faster OpenRouter egress (helps clear slice 2 AC-14).
- **Option (parallel):** `/wf implement wire-android-backend-summarizer summary-orchestration` — slice 3 plan exists, its webhook-verifier contract is anchored by slice 2's verify-time evidence. Safe to start in parallel with review.

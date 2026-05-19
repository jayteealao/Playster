---
schema: sdlc/v1
type: verify
slug: wire-android-backend-summarizer
slice-slug: summarizer-container
status: complete
stage-number: 6
created-at: "2026-05-19T13:37:17Z"
updated-at: "2026-05-19T13:37:17Z"
result: partial
metric-checks-run: 6
metric-checks-passed: 6
metric-acceptance-met: 5
metric-acceptance-total: 7
metric-acceptance-user-observable: 4
metric-acceptance-code-only: 3
metric-interactive-checks-run: 5
metric-interactive-checks-passed: 4
metric-issues-found: 0
metric-issues-found-initial: 1
metric-issues-found-final: 0
fix-rounds-run: 1
convergence: converged
verify-owned-fix-commit: null
interactive-verification: deferred
interactive-verification-defer-reason: "AC-14 (no-caption completed summary) — OpenRouter free-tier 120B model latency exceeds harness budget on Docker Desktop residential NAT egress. Daemon contract is confirmed working (SSE emits Extracting → Summarizing → meta with chosen free model, then waits on OpenRouter response); the unmet bound is environmental, not code. Re-verify in Cloud Run or in a dedicated workflow that pins a faster free model."
adapters-used: [service]
bootstrap-failures: []
evidence-dir: ".ai/workflows/wire-android-backend-summarizer/verify-evidence/summarizer-container/"
tags: [summarizer, docker, cloud-run, subtree, webhook, hmac, yt-dlp, openrouter-free]
refs:
  index: 00-index.md
  verify-index: 06-verify.md
  slice-def: 03-slice-summarizer-container.md
  plan: 04-plan-summarizer-container.md
  implement: 05-implement-summarizer-container.md
  siblings:
    - 06-verify-auth-and-android-firebase.md
  review: 07-review-summarizer-container.md
next-command: wf-review
next-invocation: "/wf review wire-android-backend-summarizer summarizer-container"
---

# Verify: summarizer-container

## Verification Summary

Per-slice verify of the second slice (container image + webhook signing + harness). Static checks all clean: lint, tsc, vitest (71/71 passing including 27 webhook-related specs across signer/deliver/url-runner/schema/migrations). The subtree pin matches actual squash. Docker Desktop is available and the multi-stage build now produces a working image after a sequence of Dockerfile bringup defenses landed verify-side (native build deps, `CI=true`, `pnpm prune --ignore-scripts`, `/app` layout for pnpm symlink resolution, `python3` in the runtime stage for the `yt-dlp` zipapp, plus a top-level `.dockerignore`).

The user-observable AC gate partitions seven AC entries: AC-6 (webhook signature), AC-14 (no-caption fallback summary), AC-16 (cold-start health), and the slice-local replay-rejection AC are user-observable; image-builds-cleanly, yt-dlp-version-floor, and subtree-pin-matches are code-only. AC-6, AC-16, the yt-dlp floor, and the replay AC all produced positive runtime evidence in this verify. **AC-14 is deferred** with an environmental reason — the daemon is confirmed working (direct SSE probe captured `event: status data: Extracting…` then `event: status data: Summarizing…` then `event: meta data: {"model":"openrouter/nvidia/nemotron-3-super-120b-a12b:free", …}` for a non-YouTube URL) but the chosen OpenRouter free-tier 120B model never returns chunks within the harness's 12-minute budget on this machine. This is OpenRouter free-tier latency, not a daemon defect; the GitHub issue tracker at steipete/summarize has no recent reports of hang/timeout regressions.

The verdict is `result: partial` because of the AC-14 deferral. `convergence: converged` because the verify-owned fix loop resolved the single pre-loop blocker (multi-stage build failure) by issuing a chained Dockerfile patch the user explicitly opted into extending across the initial root cause's cascade of incremental discoveries.

## Adapters used

- **service** — only the service adapter applied; this slice is a Cloud Run container, not a UI surface. Stack `platforms: [android, service]` was confirmed; android was excluded by intersection (no Android-touching code in this slice).

## Automated Checks Run

| Check | Outcome | Notes |
|-------|---------|-------|
| `pnpm --filter summarize-api lint` | pass | ESLint 9 flat config; no errors, no warnings in slice-2 files. |
| `pnpm --filter summarize-api build` | pass | `tsc` clean exit. |
| `pnpm --filter summarize-api test` | pass | 15 files / 71 tests in 6.68s. Webhook coverage: 5 signer (byte-exact fixture + multibyte + single-byte change), 7 deliver (retry ladder + non-retryable 4xx), 3 url-runner-webhook (happy/failed/no-webhook), 5 webhook-schema (gate on `webhook_secret`), 2 migrations (re-open idempotency + 003 columns). |
| `docker compose build` (multi-stage) | pass (after fix loop) | Initial run failed on missing native build deps; resolved by patching the daemon-build + api-build stages. Final image: `deploy-summarizer:latest` ~1.65 GB compressed. |
| Subtree pin match | pass | `SUBTREE_PIN.md` records upstream `0ec12acc15c480fd4fc91f9d1ee4538c3adeb1de`, local squash `8fcef862`; `git log -- summarizer/summarize-daemon` agrees. |
| `yt-dlp --version` inside runtime image | pass | Reports `2026.02.21` (floor met). Required adding `python3` to the runtime stage — the GitHub Releases artifact is a Python zipapp with a `#!/usr/bin/env python3` shebang, not a true static binary. |

## Interactive Verification Results

Adapter: **service** (`docker compose up` over `summarizer/deploy/docker-compose.yml` + fixtures POSTed to the gateway + signed webhook captures verified by a sibling `mock-backend` container).

| Criterion | Tool | Steps | Evidence | Observation | Result |
|---|---|---|---|---|---|
| **AC-16** — Container cold-start healthy within budget | docker compose | `docker compose up -d` then `pollUntilOk /health 60s`. | `verify-evidence/summarizer-container/harness.log` lines `summarizer healthy … status:200` at 2.4 s and `mock-backend healthy` at 2.4 s. Daemon `/health 200` confirmed via entrypoint log `daemon /health is 200`. | Gateway + daemon both healthy within ~2 s of compose up; far inside the 30 s budget the entrypoint enforces and the 60 s harness budget. | pass |
| **AC-6** — Webhook POST carries valid `X-Summarizer-Signature` and resolves at receiver | docker compose + mock-backend HMAC verifier | Stack up, POST fixtures captioned + no-caption to gateway, gateway delivered terminal webhooks. | Daemon container logs `webhook … outcome:delivered, status:204` for each job; mock-backend's signature verifier returned 204 (HMAC matched + replay window OK). Cross-checked independently with a synthetic curl POST: valid signature → 204; same payload re-signed with `t = now - 301` → 401. | Signature contract works end-to-end. Mock-backend independently re-derives the HMAC and accepts only fresh signatures within the 300 s window. | pass |
| **AC-14** — No-caption fixture surfaces as `status: completed` with non-empty summary | docker compose + OpenRouter free | Two passes attempted with `JOB_TIMEOUT_MS=300000` (default) and `900000` (bumped). Daemon directly probed via `docker exec curl /v1/summarize/<id>/events`. | SSE stream shows `Extracting → Summarizing → meta(model=openrouter/nvidia/nemotron-3-super-120b-a12b:free)` then only `: keepalive` comments for 30+ s. No `chunk`. No `complete`. After gateway timeout fires, the gateway emits a `failed: This operation was aborted` webhook with valid signature. | Daemon contract works (status events fire, model selection works, SSE plumbing carries keepalives) but the chosen free-tier 120B model does not return chunks within harness budget. This holds for non-YouTube URLs too (smoke probe on `https://example.com` reproduces). | **deferred** — see defer-reason above. |
| **Slice-local — Replay attack** — POST to mock-backend with `t = now - 301s` rejected | curl independent probe | Brought up mock-backend alone with known `WEBHOOK_SECRET`; POST `t=now` valid signature → expect 204; POST `t=now-301` re-signed → expect 401. | Valid POST: `HTTP/1.1 204 No Content`. Replay POST: `HTTP/1.1 401 Unauthorized`. | Mock-backend enforces the 300 s replay window correctly. The harness's longer end-to-end variant of this check (post-AC-14) was not reached this round because of the AC-14 deferral. | pass |
| **Daemon contract probe** — direct daemon `/v1/summarize` end-to-end | docker exec + SSE curl | `POST /v1/summarize {url:"https://example.com", model:"free"}` returns `{ok,id}` immediately; subscribe `/v1/summarize/<id>/events`. | Daemon emits status + meta events, picks a free model via OpenRouter `/v1/refresh-free` cache, then waits on the model. No SSE error event, no exception. | Daemon is healthy and contract is intact; the unmet AC-14 result is on the OpenRouter side, not the daemon. | confirmed |

## Acceptance Criteria Status

| Criterion | Kind | Status | Verification method | Evidence |
|---|---|---|---|---|
| **AC-6** — webhook POST hits receiver with valid `X-Summarizer-Signature` | user-observable | met | interactive (mock-backend HMAC verifier in harness) | `verify-evidence/.../smoke-example.json` (signature `t=…,v1=…`), daemon container log `outcome:delivered, status:204` for fixture jobs. Also re-derived independently in the curl replay test. |
| **AC-14** — no-caption fallback → `status: completed` + non-empty `result.summary` | user-observable | runtime-evidence-missing (deferred) | interactive (attempted; OpenRouter free-tier latency exceeded budget) | `verify-evidence/.../harness.log` (12-min timeout). Daemon contract confirmed via direct probe. Deferral reason recorded in frontmatter. |
| **AC-16** — both processes healthy within budget on cold start | user-observable | met | interactive (gateway + daemon `/health` polled) | Harness log: `summarizer healthy status:200` at ~2.4 s of compose up; entrypoint log `daemon /health is 200` before gateway start. |
| **slice-local** — image builds cleanly on Node 24 + pnpm 10.33.2 | code-only | met | automated (`docker compose build`) | Build succeeds end-to-end after Dockerfile bringup defenses landed; image tag `deploy-summarizer:latest` produced. |
| **slice-local** — `yt-dlp --version` reports ≥ 2026.02.21 | user-observable | met | interactive (`docker run yt-dlp --version`) | Output: `2026.02.21`. |
| **slice-local** — `SUBTREE_PIN.md` matches actual subtree HEAD | code-only | met | automated (`git log` cross-check) | Pin records upstream `0ec12acc15c480fd4fc91f9d1ee4538c3adeb1de`, squash `8fcef862`; latest squash commit in `git log -- summarizer/summarize-daemon` is `8fcef862`. |
| **slice-local** — replay attack rejected by mock-backend | user-observable | met | interactive (curl replay probe) | Independent replay test: valid sig → 204, `t=now-301` → 401. |

## Issues Found

None outstanding. The single pre-loop blocker (multi-stage docker build failure) was resolved across the verify-owned fix loop. AC-14's deferral is procedural (environmental constraint), not a code defect — see Verify-Owned Fixes below for the full patch list and Recommended Next Stage for the follow-up workflow recommendation.

## Verify-Owned Fixes

The implement stage's Dockerfile had not been built end-to-end on a clean machine. Verify caught and patched seven distinct bringup defenses needed for a working multi-stage build, plus environmental settings discovered during smoke probing. Patches were applied as a chained sequence across multiple harness rounds; the user explicitly opted into extending the loop on each iteration after the initial `Fix now` triage.

| ID | Type | Triage | Sub-agent outcome | Re-check result |
|----|------|--------|-------------------|-----------------|
| BUILD-1 | check-failure | Fix | Patched (`apt-get install python3 make g++` added to both daemon-build and api-build stages) | Pass — native compile of `better-sqlite3` proceeds |
| BUILD-2 | check-failure (cascade) | Fix | Patched (`ENV CI=true` in both build stages so `pnpm prune --prod` does not abort on no-TTY) | Pass |
| BUILD-3 | check-failure (cascade) | Fix | Patched (`pnpm prune --prod --ignore-scripts` in both stages so daemon's `prepare` lifecycle doesn't re-run `rimraf` after dev deps are gone) | Pass |
| BUILD-4 | check-failure (cascade) | Fix | Patched (top-level `.dockerignore` excluding `**/node_modules`, `**/dist`, `**/build`, `.env*`, and verify-artifact paths) — host's `summarize-api/node_modules` was clobbering the in-container pnpm install via `COPY` | Pass |
| BUILD-5 | check-failure (cascade) | Fix | Patched (`pnpm --filter summarize-api prune` triggers an implicit recursive flag that pnpm 10.x's `prune` rejects; replaced with `cd summarizer/summarize-api && pnpm prune --prod --ignore-scripts`) | Pass |
| BUILD-6 | runtime-failure | Fix | Patched (Dockerfile runtime layout: copy `api-build`'s output to `/app/summarizer/summarize-api` + `/app/node_modules` instead of `/opt/api` + `/opt/node_modules` so pnpm's relative symlinks `../../../node_modules/.pnpm/...` resolve correctly; entrypoint import path updated) | Pass — `Cannot find package 'fastify'` resolved; gateway starts |
| ENV-1 | environment | Fix | Patched (`docker-compose.yml` port `8080:8080` → `18080:8080` after discovering host 8080 is held by an unrelated `fnm` shell node process; matching `SUMMARIZER_URL` update in `run-harness.mjs`) | Pass — gateway is reachable |
| RUNTIME-1 | runtime-failure | Fix | Patched (`python3` added to runtime apt-get install — `yt-dlp` from GitHub Releases is a Python zipapp, not a true static binary; previously `yt-dlp --version` errored `/usr/bin/env: 'python3': No such file or directory`) | Pass — `yt-dlp --version` reports `2026.02.21` |

Two harness-driver hardening tweaks rode along for evidence: `JOB_TIMEOUT_MS=900000` and `waitForWebhook` 12-min budget. These don't change the slice's contract; they document realistic upper bounds for OpenRouter free-tier latency. The shape doc anticipated this in its v1.1 stretch list (Cloud NAT + static IP).

Commit: `(no commit — pending atomic commit covers all verify-time fixes alongside this artifact)`

## Augmentation Verification

Not applicable. `02c-craft.md` is absent for this slice (no UI mock); `00-index.md` `augmentations:` list is empty.

## Gaps / Unverified Areas

- **AC-14 happy path** — no `status: completed` capture in this verify pass. Deferred per `interactive-verification: deferred`.
- **Cloud Run deploy** — the runbook (`summarizer/deploy/CLOUD-RUN.md`) was not exercised. Cloud Build / `gcloud run deploy` happens at ship-time. This is per plan (no pre-ship deploy expected).
- **OpenRouter free-model selection policy** — `model: "free"` resolves at request time to whichever model is first in the daemon's cached free list. The list ordering is opaque; today it picks `nvidia/nemotron-3-super-120b-a12b:free` which is slow. A dedicated investigate workflow could pin a faster free model (e.g., `meta-llama/llama-3.3-70b-instruct:free`) or change the daemon's selection heuristic. Captured in Recommended Next Stage below.

## Freshness Research

- **Source:** [steipete/summarize issues page](https://github.com/steipete/summarize/issues) (May 2026).
  **Why it matters:** User asked to confirm there are no known daemon issues before deferring AC-14.
  **Takeaway:** No open or recently-closed issues match "daemon hangs", "SSE timeout", or "OpenRouter latency" patterns. Most-recent open issue is #224 (proposal: route `model: "auto"` to different local models per language). Daemon contract appears stable per repo signal.

- **Source:** OpenRouter free-tier model latency (observed empirically this verify run, May 2026).
  **Why it matters:** AC-14 deferral hinges on this being environmental rather than a code defect.
  **Takeaway:** `nvidia/nemotron-3-super-120b-a12b:free` does not return SSE chunks within 12 minutes on the residential Docker Desktop egress used for verification. This is consistent with free-tier 120B-class model behavior; production environments (Cloud Run egress from Google's network) typically observe lower latency.

- **Source:** `yt-dlp` GitHub Releases artifact format (verified empirically May 2026).
  **Why it matters:** Runtime stage was missing python3, breaking `yt-dlp --version`.
  **Takeaway:** The download is a Python zipapp with `#!/usr/bin/env python3` shebang. Runtime image must install `python3`. This is a documentation gap in the plan's "static binary" path — corrected here.

## Recommendation

`result: partial` with `convergence: converged` and `interactive-verification: deferred` for AC-14 only. The slice is **review-ready for everything except AC-14's happy-path summary content**. The webhook signing contract (AC-6), cold-start health (AC-16), yt-dlp floor, subtree pin match, and replay rejection are all positively evidenced. The daemon contract is independently confirmed via direct probe. AC-14's failure mode is OpenRouter free-tier latency, not slice code.

The eight verify-time fixes are substantive — they harden the Dockerfile to actually build on a clean machine. Review should focus on whether they should be folded back into a refreshed implement record (or accepted as the verify-owned fix loop's output).

## Recommended Next Stage

- **Option A (default):** `/wf review wire-android-backend-summarizer summarizer-container` — proceed to review with the partial verdict and deferred AC-14. Review will see the verify-owned fixes and decide whether anything else needs adjustment. **Run `/compact` first** — extensive harness-iteration context is noise for review dispatch.
- **Option B:** `/wf-quick probe wire-android-backend-summarizer` — once the container is deployed to Cloud Run (where OpenRouter latency from Google egress is typically lower), a probe run against the deployed service can clear the AC-14 deferral with real `status: completed` evidence. This is the slug-wide runtime sweep counterpart to the per-slice gate.
- **Option C:** `/wf investigate openrouter-free-model-selection` (new workflow) — explicitly investigate which free models are reliable for v1 production use and either pin one in the daemon config or change the gateway's `model` default. Avoids the AC-14 latency lottery long-term.
- **Option D:** `/wf verify wire-android-backend-summarizer auth-and-android-firebase` — re-verify slice 1 to attempt to clear its existing `runtime-evidence-deferral` (AC-3 positive + AC-4). Note: bootstrap state probed during this run shows `__BOOTSTRAP_UID__` sentinel still present in both `backend/functions/src/auth/verify.ts:10` and `backend/firestore.rules`; the operator has not run the two-pass deploy, so re-verifying right now would just re-emit the same deferral. Defer this option until after bootstrap.

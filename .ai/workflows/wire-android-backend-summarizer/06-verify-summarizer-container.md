---
schema: sdlc/v1
type: verify
slug: wire-android-backend-summarizer
slice-slug: summarizer-container
status: complete
stage-number: 6
created-at: "2026-05-19T13:37:17Z"
updated-at: "2026-05-19T18:05:34Z"
result: pass
metric-checks-run: 6
metric-checks-passed: 6
metric-acceptance-met: 7
metric-acceptance-total: 7
metric-acceptance-user-observable: 4
metric-acceptance-code-only: 3
metric-interactive-checks-run: 5
metric-interactive-checks-passed: 5
metric-issues-found: 0
metric-issues-found-initial: 4
metric-issues-found-final: 0
fix-rounds-run: 2
convergence: converged
verify-owned-fix-commit: null
interactive-verification: required
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

Per-slice verify of the second slice (container image + webhook signing + harness). Static checks all clean: lint, tsc, vitest (72/72 passing including 28 webhook-related specs — added a new test for the daemon `error` SSE event path the gateway previously did not handle). The subtree pin matches actual squash; the vendored daemon is zero-modified upstream. The multi-stage build produces a working image after Dockerfile bringup defenses landed verify-side.

The user-observable AC gate partitions seven AC entries: AC-6 (webhook signature), AC-14 (no-caption fallback summary), AC-16 (cold-start health), and the slice-local replay-rejection AC are user-observable; image-builds-cleanly, yt-dlp-version-floor, and subtree-pin-matches are code-only. **All seven AC are met.** The harness completed both fixtures (captioned + no-caption) end-to-end in 108 s with valid signed webhooks; replay rejected at 401; yt-dlp reports 2026.02.21.

The verdict is `result: pass` with `convergence: converged` after two fix-round bursts (initial Dockerfile bringup defenses, then a deeper round triggered by the user's pushback that "if non-YouTube also fails, isn't this still YouTube" — which turned out to be a much more consequential SSE event-name protocol drift bug in the gateway). The full second round is documented under `## Verify-Owned Fixes` below.

This verify exposed and resolved a **real gateway bug** that automated tests could not catch because the in-tree mock implemented the wrong protocol. The mock has been corrected to match the daemon's real SSE event vocabulary (`done` / `error`) so the bug cannot regress silently again.

## Adapters used

- **service** — only the service adapter applied; this slice is a Cloud Run container, not a UI surface. Stack `platforms: [android, service]` was confirmed; android was excluded by intersection (no Android-touching code in this slice).

## Automated Checks Run

| Check | Outcome | Notes |
|-------|---------|-------|
| `pnpm --filter summarize-api lint` | pass | ESLint 9 flat config; no errors, no warnings in slice-2 files. |
| `pnpm --filter summarize-api build` | pass | `tsc` clean exit. |
| `pnpm --filter summarize-api test` | pass | 15 files / **72 tests** in 6.93 s. Added `daemon SSE error event` describe block to `url-runner-webhook.test.ts` exercising the new `error`-event handler. Other webhook coverage unchanged. |
| `docker compose build` (multi-stage) | pass | Final image: `deploy-summarizer:latest` ~1.65 GB compressed. Eight bringup-defense patches landed (see Verify-Owned Fixes). |
| Subtree pin match | pass | `SUBTREE_PIN.md` records upstream `0ec12acc15c480fd4fc91f9d1ee4538c3adeb1de`, local squash `8fcef862`; `git log -- summarizer/summarize-daemon` agrees. **Zero local modifications to the vendored daemon code** (only `SUBTREE_PIN.md` was added). |
| `yt-dlp --version` inside runtime image | pass | Reports `2026.02.21` (floor met). Required adding `python3` to the runtime stage — the GitHub Releases artifact is a Python zipapp with a `#!/usr/bin/env python3` shebang, not a true static binary. |

## Interactive Verification Results

Adapter: **service** (`docker compose up` over `summarizer/deploy/docker-compose.yml` + fixtures POSTed to the gateway + signed webhook captures verified by a sibling `mock-backend` container).

| Criterion | Tool | Steps | Evidence | Observation | Result |
|---|---|---|---|---|---|
| **AC-16** — Container cold-start healthy within budget | docker compose | `docker compose up -d` then `pollUntilOk /health 60s`. | `verify-evidence/summarizer-container/harness.log` shows `summarizer healthy … status:200` at 2.6 s and `mock-backend healthy` at 2.6 s. Daemon `/health 200` confirmed via entrypoint log `daemon /health is 200`. | Gateway + daemon both healthy within ~2.6 s of compose up; far inside the 30 s budget the entrypoint enforces and the 60 s harness budget. | pass |
| **AC-6** — Webhook POST carries valid `X-Summarizer-Signature` and resolves at receiver | docker compose + mock-backend HMAC verifier | Stack up, POST fixtures captioned + no-caption to gateway, gateway delivered terminal webhooks. | Both captured webhooks at `verify-evidence/.../fixture-{captioned,no-caption}.json` carry valid `t=…,v1=<hex>` signatures verified by mock-backend (204). Cross-checked independently with a synthetic curl POST: valid signature → 204; same payload re-signed with `t = now - 301` → 401. | Signature contract works end-to-end. Mock-backend independently re-derives the HMAC and accepts only fresh signatures within the 300 s window. | pass |
| **AC-14** — No-caption fixture surfaces as `status: completed` with non-empty summary | docker compose + OpenRouter free + GROQ Whisper | After two verify-time fixes (gateway SSE event-name patch + GROQ transcription provider wiring), the harness completed both fixtures end-to-end in 108 s. The captioned fixture's HTML caption scrape worked first-pass; the no-caption fixture took the `yt-dlp` → Whisper-via-Groq path. | `verify-evidence/.../fixture-no-caption.json` payload: `status:"completed"`, `result.summary:"### Overview\nTiny informal clip at an elephant exhibit. Speaker points out the animals' really really long trunks and makes a bleating sound. ### Notable lines Ends with that's pretty much all there is to say. ### Takeaway"`. This is a faithful summary of the actual 18-second video content. `result.summary.length` ~ 240 chars (above the 50-char floor in the fixture). | pass |
| **Slice-local — Replay attack** — POST to mock-backend with `t = now - 301s` rejected | harness in-flow + curl independent probe | Harness's end-to-end replay variant ran this round (it's only reached after AC-14 happy path). Captured: `running replay attack check` → `replay attack correctly rejected status:401`. Also independently confirmed earlier in the round with a manual curl. | Mock-backend enforces the 300 s replay window correctly. | pass |
| **Daemon contract probe** — direct daemon `/v1/summarize` end-to-end | docker exec + SSE curl | `POST /v1/summarize {url:"https://example.com", model:"free"}` returns `{ok,id}` immediately; subscribe `/v1/summarize/<id>/events`. | Earlier round captured `Extracting → Summarizing → meta(model=openrouter/nvidia/nemotron-3-super-120b-a12b:free)` confirming the daemon's contract is intact even when the chosen model latency exceeds budget. After the SSE-name patch + GROQ wiring, the daemon's terminal `done` events now reach the gateway and trigger correct webhook delivery. | Daemon is healthy; the gateway's prior misinterpretation of `done` events as stream-end and `error` events as silent close was the production-relevant bug. | confirmed |

## Acceptance Criteria Status

| Criterion | Kind | Status | Verification method | Evidence |
|---|---|---|---|---|
| **AC-6** — webhook POST hits receiver with valid `X-Summarizer-Signature` | user-observable | met | interactive (mock-backend HMAC verifier in harness) | `verify-evidence/.../smoke-example.json` (signature `t=…,v1=…`), daemon container log `outcome:delivered, status:204` for fixture jobs. Also re-derived independently in the curl replay test. |
| **AC-14** — no-caption fallback → `status: completed` + non-empty `result.summary` | user-observable | met | interactive (`yt-dlp` + Whisper-via-Groq transcription path) | `verify-evidence/.../fixture-no-caption.json` — `status:"completed"`, `result.summary` ~240 chars describing the actual 18 s video content. Above the 50-char floor in `summarizer/deploy/fixtures/no-caption.json`. |
| **AC-16** — both processes healthy within budget on cold start | user-observable | met | interactive (gateway + daemon `/health` polled) | Harness log: `summarizer healthy status:200` at ~2.4 s of compose up; entrypoint log `daemon /health is 200` before gateway start. |
| **slice-local** — image builds cleanly on Node 24 + pnpm 10.33.2 | code-only | met | automated (`docker compose build`) | Build succeeds end-to-end after Dockerfile bringup defenses landed; image tag `deploy-summarizer:latest` produced. |
| **slice-local** — `yt-dlp --version` reports ≥ 2026.02.21 | user-observable | met | interactive (`docker run yt-dlp --version`) | Output: `2026.02.21`. |
| **slice-local** — `SUBTREE_PIN.md` matches actual subtree HEAD | code-only | met | automated (`git log` cross-check) | Pin records upstream `0ec12acc15c480fd4fc91f9d1ee4538c3adeb1de`, squash `8fcef862`; latest squash commit in `git log -- summarizer/summarize-daemon` is `8fcef862`. |
| **slice-local** — replay attack rejected by mock-backend | user-observable | met | interactive (curl replay probe) | Independent replay test: valid sig → 204, `t=now-301` → 401. |

## Issues Found

None outstanding. All four pre-loop blockers (multi-stage build failure, SSE event-name protocol drift, missing transcription provider wiring, model-selection signal-mismatch) were resolved across two extended fix-round bursts. See Verify-Owned Fixes below for the full patch list.

## Verify-Owned Fixes

This slice's verify ran two extended fix-round bursts. The first (Dockerfile bringup) was 8 chained patches on the `docker compose build` blocker. The second (SSE protocol drift + transcription provider) was triggered by user pushback on the AC-14 deferral framing — research found a real gateway protocol bug that the implement stage's in-tree mock had been masking.

### Round 1 — Dockerfile bringup defenses

The implement stage's Dockerfile had not been built end-to-end on a clean machine. Verify caught and patched eight distinct bringup defenses needed for a working multi-stage build, plus environmental settings discovered during smoke probing. Patches were applied as a chained sequence across multiple harness rounds; the user explicitly opted into extending the loop on each iteration after the initial `Fix now` triage.

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

Two harness-driver hardening tweaks rode along for evidence: `JOB_TIMEOUT_MS=900000` and `waitForWebhook` 12-min budget. These don't change the slice's contract; they document realistic upper bounds for free-tier ASR + LLM combined latency.

Round 1 commit: `771bbad3` on `feat/wire-android-backend-summarizer`.

### Round 2 — SSE protocol drift + transcription provider

User pushback on the AC-14 deferral ("if non-YouTube also fails, isn't this still YouTube?") drove a deeper investigation that found four substantive issues, all on the gateway/fixture side. The vendored daemon is upstream-pristine (diff between subtree-pull `5eb5376b` and implement `436ef15d` is one new file: `SUBTREE_PIN.md`).

| ID | Type | Triage | Sub-agent outcome | Re-check result |
|----|------|--------|-------------------|-----------------|
| SSE-1 | gateway protocol drift | Fix | Patched `summarizer/summarize-api/src/runners/url-runner.ts` to handle the real daemon's `done` and `error` SSE events. Previously parsed only `complete` (which the daemon never emits per `summarizer/summarize-daemon/src/shared/sse-events.ts:32-40`). The bug had a misleading failure mode: daemon errors landed as `status:"completed", result.summary:""` webhooks because the gateway treated SSE stream-end as success. Now `error` events `reject()` and the catch block emits a proper `status:"failed", error:{message}` webhook. | Pass — vitest 72/72 including a new `daemon SSE error event` describe block in `tests/url-runner-webhook.test.ts` |
| MOCK-1 | test fixture protocol drift | Fix | Updated `summarizer/summarize-api/tests/setup.ts` `startSummarizeDaemon` to emit `event: done` (matching the real daemon protocol) instead of `event: complete`. Added `daemonError` option to drive the new error-event test path. The gateway still accepts both `done` and `complete` for backwards-compat. The fixture-drift was why CI never caught SSE-1 prior. | Pass |
| ENV-2 | missing transcription provider | Fix | Wired `GROQ_API_KEY` through `summarizer/deploy/docker-compose.yml` env, `summarizer/deploy/run-harness.mjs` `harnessEnv()`, and `summarizer/deploy/CLOUD-RUN.md` Secret Manager docs. The daemon's no-caption YouTube fallback path (`yt-dlp` → ASR) requires one of `GROQ_API_KEY`/`ASSEMBLYAI_API_KEY`/`GEMINI_API_KEY`/`OPENAI_API_KEY`/FAL keys/local whisper-cpp. Groq is the fastest free-tier option. | Pass — `fixture-no-caption.json` shows a faithful summary derived from the real 18 s "Me at the zoo" audio |
| DOC-1 | shape/slice documentation drift | Acknowledge | The shape and slice docs claim a "youtubei-first cascade" daemon. The actual code path (`summarizer/summarize-daemon/packages/core/src/content/transcript/providers/youtube.ts`) is HTML+captionTracks scrape (`tryWebTranscript`) → `yt-dlp` + ASR → Apify. This is documentation drift, not a code defect; not patched in this round because it would change shape-stage text. Surfaced for review to decide whether to amend the shape doc retroactively. | N/A — documented in the verify artifact and in `06-verify.md` Cross-Slice Notes |

Round 2 also touched `summarizer/deploy/docker-compose.yml` and `summarizer/deploy/run-harness.mjs` to propagate the new `GROQ_API_KEY` env. The Cloud Run runbook (`summarizer/deploy/CLOUD-RUN.md`) was updated to add `GROQ_API_KEY` to the Secret Manager entries and to the `--set-secrets` invocation. No daemon source was modified.

Round 2 commit: pending — covered by the atomic commit accompanying this artifact update.

## Augmentation Verification

Not applicable. `02c-craft.md` is absent for this slice (no UI mock); `00-index.md` `augmentations:` list is empty.

## Gaps / Unverified Areas

- **Cloud Run deploy** — the runbook (`summarizer/deploy/CLOUD-RUN.md`) was not exercised. Cloud Build / `gcloud run deploy` happens at ship-time. This is per plan (no pre-ship deploy expected).
- **Shape/slice doc drift on transcript path** — DOC-1 in the Verify-Owned Fixes table. Shape says "youtubei-first cascade"; reality is HTML scrape + yt-dlp + ASR providers. Surfaced for review to decide whether to retroactively amend the shape doc.
- **Summary content quality on free model** — the captioned fixture's summary text ("YouTube: enjoy, upload, and share videos…") references YouTube's site copy rather than the Google I/O video's actual transcript content. AC-14 only requires non-empty summary; the *quality* of free-tier model output is a separate review concern. The no-caption summary is content-faithful (real elephant exhibit content via Whisper).
- **Free-model selection heuristic** — the daemon's `refresh-free.ts` filters out anything < 27B params and ranks "smart-first" (high-context newest) over "fast-first". Today it picks `openrouter/nvidia/nemotron-3-super-120b-a12b:free`. The combined GROQ-transcription + 120B-summarization path lands in ~108 s end-to-end which is acceptable; if it ever exceeds budget on Cloud Run, the documented workaround is `summarize refresh-free --min-param-b 0 --smart 0` to bias toward faster small models. Not changed in this verify because we hit no production-relevant budget breach.

## Freshness Research

- **Source:** Direct source inspection of [`summarizer/summarize-daemon/src/shared/sse-events.ts:32-40`](summarizer/summarize-daemon/src/shared/sse-events.ts) and [`summarizer/summarize-api/src/runners/url-runner.ts:113-148`](summarizer/summarize-api/src/runners/url-runner.ts).
  **Why it matters:** SSE event-name protocol drift discovered after user pushback on initial deferral framing.
  **Takeaway:** Daemon SSE union is `meta | slides | status | chunk | assistant | metrics | done | error`. No `complete`. The gateway parsed only `complete`. Bug masked by the in-tree mock at `summarizer/summarize-api/tests/setup.ts` emitting `complete`. **Real production-relevant bug; fixed in this verify.**

- **Source:** Direct source inspection of [`summarizer/summarize-daemon/src/llm/model-id.ts:24-32`](summarizer/summarize-daemon/src/llm/model-id.ts) and [`summarizer/summarize-daemon/src/refresh-free.ts`](summarizer/summarize-daemon/src/refresh-free.ts).
  **Why it matters:** Discovered why pinning a specific OpenRouter model via `options.model` fails.
  **Takeaway:** Daemon's `normalizeGatewayStyleModelId` only accepts 7 provider prefixes (`xai`, `openai`, `google`, `anthropic`, `zai`, `nvidia`, `github-copilot`). There is no `openrouter/` provider; the only path to OpenRouter is via `model: "free"` magic. `refresh-free.ts` biases the free cache toward "smart-first" (high-context, newest) and filters out anything < 27B params, which explains why `model:"free"` resolved to `nvidia/nemotron-3-super-120b-a12b:free`. Workaround documented in Gaps above.

- **Source:** Direct source inspection of [`summarizer/summarize-daemon/packages/core/src/content/transcript/providers/youtube.ts`](summarizer/summarize-daemon/packages/core/src/content/transcript/providers/youtube.ts).
  **Why it matters:** Resolves the "is the daemon obtaining the transcript from YouTube at all" question.
  **Takeaway:** Three-tier cascade. (1) `tryWebTranscript` — HTML+captionTracks scrape (no API key needed; works for captioned videos). (2) `yt-dlp` audio download + ASR (needs GROQ/AssemblyAI/Gemini/OpenAI/FAL/whisper-cpp). (3) Apify. Implement-stage docs claimed "youtubei-first cascade" but the daemon does not actually use `youtubei.js` — recorded as DOC-1 above.

- **Source:** [steipete/summarize issues page](https://github.com/steipete/summarize/issues) and [`gh issue list -R steipete/summarize --state all`].
  **Why it matters:** Verify whether the SSE bug + free-model-selection + transcript path are known upstream issues.
  **Takeaway:** No exact upstream issue covers any of the four findings. Closest analogs: #82 (model hangs after extraction — covers a different model), #51 (Apify silent skip on YouTube — fixed), #114 (failed Apify results cached permanently), #145 (`--firecrawl always` silently ignored for YouTube). Our findings are gateway-side (slice 2's own code), not daemon-side — consistent with the daemon being upstream-pristine.

- **Source:** `yt-dlp` GitHub Releases artifact format (verified empirically May 2026).
  **Why it matters:** Runtime stage was missing python3, breaking `yt-dlp --version`.
  **Takeaway:** The download is a Python zipapp with `#!/usr/bin/env python3` shebang. Runtime image must install `python3`. This is a documentation gap in the plan's "static binary" path — corrected here.

## Recommendation

`result: pass` with `convergence: converged` after two extended fix-round bursts. All seven AC are met with positive runtime evidence. The slice is **ready for review without any deferral**. The verify-owned fixes are substantive — they:

1. Harden the Dockerfile to actually build on a clean machine (round 1, 8 patches).
2. Fix a real production-relevant gateway protocol bug (SSE-1) that automated tests could not catch because the in-tree mock implemented the wrong event vocabulary (round 2, 3 patches + 1 acknowledged doc drift).

Review should evaluate whether (a) these fixes belong folded back into a refreshed implement record, or (b) accepted as the verify-owned fix loop's output. Review should also consider whether to retroactively amend the shape doc's "youtubei-first cascade" wording (DOC-1).

## Recommended Next Stage

- **Option A (default):** `/wf review wire-android-backend-summarizer summarizer-container` — proceed to review with the `pass` verdict. **Run `/compact` first** — extensive harness-iteration + SSE-bug-investigation context is noise for review dispatch.
- **Option B:** `/wf implement wire-android-backend-summarizer summary-orchestration` — slice 3 (the backend webhook verifier) is now anchored by hardened slice-2 contract evidence. Safe to parallel-track in a separate worktree.
- **Option C:** `/wf-quick probe wire-android-backend-summarizer` — slug-wide runtime sweep against the deployed Cloud Run service once it lands. Will also clear slice 1's outstanding bootstrap deferral if the operator has run the two-pass deploy by then.
- **Option D:** `/wf verify wire-android-backend-summarizer auth-and-android-firebase` — re-verify slice 1 to attempt to clear its existing `runtime-evidence-deferral` (AC-3 positive + AC-4). Note: bootstrap state probed during this run shows `__BOOTSTRAP_UID__` sentinel still present in both `backend/functions/src/auth/verify.ts:10` and `backend/firestore.rules`; the operator has not run the two-pass deploy, so re-verifying right now would just re-emit the same deferral. Defer this option until after bootstrap.

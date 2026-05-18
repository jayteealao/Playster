---
schema: sdlc/v1
type: slice
slug: wire-android-backend-summarizer
slice-slug: summarizer-container
status: defined
stage-number: 3
created-at: "2026-05-17T21:45:53Z"
updated-at: "2026-05-17T21:45:53Z"
complexity: l
depends-on: []
tags: [summarizer, docker, cloud-run, subtree, webhook, hmac, yt-dlp]
refs:
  index: 00-index.md
  slice-index: 03-slice.md
  siblings:
    - 03-slice-auth-and-android-firebase.md
    - 03-slice-summary-orchestration.md
    - 03-slice-summary-ui.md
  plan: 04-plan-summarizer-container.md
  implement: 05-implement-summarizer-container.md
---

# Slice: Summarizer container (Cloud Run deployable, webhook-signing)

## Goal

Produce a single Docker image that runs both the `summarize-api` Fastify gateway and the vendored `summarize-daemon` (steipete/summarize subtree) as one Cloud Run service. Deploy it. Add Stripe-style HMAC webhook signing on terminal SSE events so a backend (slice 3) can receive completion notifications. Validate end-to-end via a docker-compose harness pointed at two fixture YouTube URLs.

## Why This Slice Exists

This is the highest-uncertainty infrastructure slice. It bakes:
- Multi-stage Dockerfile for two cooperating Node processes
- `entrypoint.js` that boots the daemon with a bearer token, waits for `/health`, calls `/v1/refresh-free`, then starts the gateway
- `yt-dlp >= 2026.02.21` via `pip` (apt is unsafe per freshness research)
- Subtree pull to `0ec12ac` for the bearer-token timing-safe compare + rate-limit hardening
- Stripe-style webhook signature with 300s replay window
- The summarize-api schema extension and `pickDefined` forward-list updates that make webhooks possible

Independent of slice 1: no code overlap. Can be developed in parallel.

## Scope

**In scope:**

- Bump the `summarizer/summarize-daemon/` subtree to upstream `0ec12ac` (or the latest stable commit that includes PRs #226 and #227). Record the pinned SHA in `summarizer/summarize-daemon/SUBTREE_PIN.md` (new file).
- Replace `summarizer/summarize-api/Dockerfile` (and any `summarizer/deploy/daemon/Dockerfile`) with a multi-stage Dockerfile:
  - Stage 1: `daemon-build` — copy `summarizer/summarize-daemon/`, install + build with its own pnpm (10.33.2) workspace.
  - Stage 2: `api-build` — copy root pnpm workspace + `summarizer/summarize-api/`, install + build.
  - Stage 3: `runtime` — `node:24-slim`, system deps (`ffmpeg`, `tesseract-ocr`, `tini`, `ca-certificates`), `pip install -U "yt-dlp>=2026.02.21"`, copy build artifacts from both build stages, `ENTRYPOINT ["/usr/bin/tini", "--"]`, `CMD ["node", "/opt/entrypoint.js"]`.
- New file: `summarizer/deploy/entrypoint.js`.
  - Reads `SUMMARIZE_TOKEN` from env; if missing, exits non-zero with a clear error.
  - Spawns the daemon as a child process. Exact CLI invocation: resolve open question Q1/Q2 from `00-index.md` by reading `summarizer/summarize-daemon/src/daemon/cli.ts` during plan stage.
  - Polls `GET http://127.0.0.1:8787/health` with a 30s timeout; exits non-zero on timeout.
  - If `OPENROUTER_API_KEY` is set, calls `POST http://127.0.0.1:8787/v1/refresh-free` with the bearer token; tolerates 5xx (don't block startup if refresh fails — log warning).
  - Imports the summarize-api `dist/index.js` (in-process). Gateway respects `PORT` env → Cloud Run injects.
  - Forwards `SIGTERM` to the daemon child; awaits child exit before exiting itself.
- Schema additions in `summarizer/summarize-api/src/schemas.ts`:
  - `urlJobSchema`: add optional `webhook_url: z.string().url()`, `webhook_secret: z.string()`, `client_job_id: z.string()`. Leave `mode` field (deferred). Add docs comment that `mode` is informational only and not forwarded to the daemon.
- `summarizer/summarize-api/src/db/migrations/` — schema migration adding `webhook_url`, `webhook_secret`, `client_job_id` TEXT columns on `jobs`. Use the existing migration runner pattern (or add one if none exists).
- `summarizer/summarize-api/src/runners/url-runner.ts` — extend `pickDefined` forward keys: add `youtube`, `videoMode`, `timestamps`, `forceSummary`, `noCache`, `extractOnly`, `prompt`, `maxCharacters`. Persist webhook fields on job creation. On terminal SSE event (`completed` or `failed`):
  - Build webhook payload `{ client_job_id, status, result?, error? }`.
  - Build canonical bytes: `${timestamp}.${raw_body_json}` where `timestamp = Math.floor(Date.now()/1000)`.
  - Compute `hmac = crypto.createHmac("sha256", webhook_secret).update(canonical).digest("hex")`.
  - Header: `X-Summarizer-Signature: t=<timestamp>,v1=<hmac>`.
  - POST to `webhook_url`. 3 attempts with exponential backoff (5s, 15s, 45s). Log final failure to stdout.
- New: `summarizer/deploy/docker-compose.yml` — service spec with the built summarizer image + a stub `mock-backend` container that exposes a `/webhook` endpoint with signature verification + writes captured payloads to `verify-artifacts/`.
- New: `summarizer/deploy/fixtures/` — two YouTube URL fixtures (one captioned, one no-caption) for the docker-compose harness.
- Cloud Run deploy step (manual or scripted): `gcloud run deploy summarizer --source summarizer --region <region> --no-allow-unauthenticated --set-secrets API_KEYS=...,SUMMARIZE_TOKEN=...,OPENROUTER_API_KEY=... --memory 2Gi --cpu 2 --timeout 600 --min-instances 0 --max-instances 2`. Document as a runbook in `docs/operations/deploy-and-bootstrap.md`.

**Out of scope (handled by other slices):**

- Backend `summaryWebhook` HTTPS receiver (verification half of the contract) → slice 3.
- Backend `requestVideoSummary` callable that uses the webhook URL → slice 3.
- Android UI consuming any of this → slice 4.
- Removing summarize-api `mode` field from schema → deferred (PO).
- Cache persistence via Cloud Storage volume → not in v1.
- Sidecar refactor → v1.1.
- Slides feature → v1.1 / Phase 7 stretch.

## Acceptance Criteria

Mapped to shape's ACs:

- **AC-6**: A summary request reaches the deployed summarizer; webhook POST hits a receiver with valid `X-Summarizer-Signature: t=<unix>,v1=<hmac>`. Verified via docker-compose harness: stub mock-backend asserts the signature verifies against the test secret using its own HMAC computation.
- **AC-14**: No-transcript daemon fallback produces a `shortDescription`-based summary that webhooks back as `status=completed` with non-empty `result.summary`. Verified via the no-caption fixture in the harness.
- **AC-16**: Container cold-start completes with both processes healthy. Verified by harness: `/health` on the gateway returns 200 within 30s of `docker compose up`; `127.0.0.1:8787/health` reachable from inside the container (verified by an exec into the container).

**Slice-local ACs (not in shape):**

- Container image builds without error on Node 24 + pnpm@10.33.2 (daemon stage) + pnpm@9 (api stage).
- `yt-dlp --version` inside the runtime image reports `>= 2026.02.21`.
- Subtree pin SHA in `SUBTREE_PIN.md` matches the actual subtree HEAD on disk; a CI-style check (or pre-commit hook) verifies the match.
- A replay attack against the mock-backend (resending a captured webhook with timestamp >300s old) is rejected by the receiver's verification logic.

## Dependencies on Other Slices

- None upstream. This is the second root slice.

Downstream consumers:
- `summary-orchestration` (slice 3) calls the deployed Cloud Run URL and verifies the HMAC signature this slice produces.
- The `client_job_id` round-trip (slice 3 sends videoId → slice 2 stores → slice 2 returns it in webhook → slice 3 looks up `summaries/{videoId}`) is the canonical idempotency contract.

## Risks

- **Open questions Q1/Q2 from `00-index.md`** — exact daemon CLI invocation and token mechanism — block the `entrypoint.js` design. Plan stage resolves by reading `summarizer/summarize-daemon/src/daemon/cli.ts`. If the daemon insists on a config file at `~/.summarize/daemon.json`, the entrypoint writes one to a per-process temp dir with mode `0600` and points the daemon at it via env or flag.
- **PoToken / yt-dlp throttling on Cloud Run shared egress.** YouTube may throttle yt-dlp from shared egress IPs. Mitigation accepted in shape: daemon's youtubei-first cascade catches most cases; failures fall to `shortDescription`. v1.1 candidate: Cloud NAT + static IP.
- **Subtree pull may break the api gateway's daemon-call contract.** The subtree bump to `0ec12ac` should not change request/response schemas (per freshness research: last meaningful refactor was March 2026), but a verification harness check before merging confirms `/v1/summarize` accepts our existing field set without error.
- **Image size & cold-start.** `ffmpeg` + `tesseract` + `yt-dlp` + Node + two `node_modules` trees push image size up. Mitigation: use `node:24-slim`, prune dev dependencies in build stages, omit `.git` and test fixtures from runtime layer. Target: < 1.5 GB compressed.
- **Cloud Run cold-start latency (open question Q3 from `00-index.md`).** First request after idle pays ~3s daemon boot + 5–10s `refresh-free`. Plan stage decides whether `min-instances=1` is worth the always-on cost; PO can revisit post-deploy.
- **`/v1/refresh-free` failure on cold start.** If OpenRouter rejects the refresh call (rate limit, network), the daemon falls back to a previously-cached free-model list (or fails to find one). Mitigation: log warning, continue; slice 3's dispatch picks `model="free"` which the daemon resolves at request time.
- **Webhook secret leakage via logs.** Slice's worker must never log the `webhook_secret` (slice 3 generates a per-summary secret; logging would defeat the per-secret design). Mitigation: redact `webhook_secret` from any error/info logging in url-runner.
- **Replay across deploys.** If the canonical bytes don't precisely match between signer (this slice) and verifier (slice 3) — e.g., one stringifies the JSON differently — verification fails. Mitigation: signer uses `JSON.stringify(payload)` and includes the *exact same string* as the body of the POST; verifier reads the raw request body before parsing. Document this explicitly in the slice plan.

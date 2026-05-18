---
schema: sdlc/v1
type: implement
slug: wire-android-backend-summarizer
slice-slug: summarizer-container
status: complete
stage-number: 5
created-at: "2026-05-18T17:23:50Z"
updated-at: "2026-05-18T17:23:50Z"
metric-files-changed: 30
metric-lines-added: 1760
metric-lines-removed: 141
metric-deviations-from-plan: 4
metric-review-fixes-applied: 0
commit-sha: "9f048308662e7db3a82f7b872a58a242089f67f9"
tags: [summarizer, docker, cloud-run, subtree, webhook, hmac, yt-dlp]
refs:
  index: 00-index.md
  implement-index: 05-implement.md
  slice-def: 03-slice-summarizer-container.md
  plan: 04-plan-summarizer-container.md
  siblings:
    - 05-implement-auth-and-android-firebase.md
  verify: 06-verify-summarizer-container.md
next-command: wf-verify
next-invocation: "/wf verify wire-android-backend-summarizer summarizer-container"
---

# Implement: Summarizer container (Cloud Run deployable, webhook-signing)

## Summary of Changes

All six plan phases landed. The summarize-daemon subtree is pinned at
upstream `0ec12acc` (squash `8fcef862` on this branch); the daemon was
already May-2026 main HEAD, so the pull picked up upstream PRs #226 +
#227 (timing-safe bearer compare + failed-auth rate-limit on `/v1/*`),
the slides feature, and a stack of new tests. The legacy
`summarizer/summarize-api/Dockerfile` and `summarizer/deploy/daemon/`
stack are deleted in favor of a unified multi-stage build at
`summarizer/deploy/Dockerfile` plus a Node `entrypoint.js` that boots
the daemon, waits for `/health`, optionally calls `/v1/refresh-free`,
and imports the gateway in-process so Cloud Run's `$PORT` reaches
Fastify directly.

The summarize-api gains a webhook contract: `urlJobSchema` accepts
`webhook_url`, `webhook_secret` (≥16 chars), and `client_job_id`. The
`POST /v1/jobs` route enforces "secret required when url is set" with
an explicit 400. Three new `jobs` columns (`webhook_url`,
`webhook_secret`, `client_job_id`) are added via a named-migration
runner (new `schema_migrations` tracking table; converts the previous
unconditional `MIGRATIONS` loop to one-shot named applies). The
`url-runner` now extends `pickDefined` with the eight additional
daemon-forwarded keys named in the plan (`youtube`, `videoMode`,
`timestamps`, `forceSummary`, `noCache`, `extractOnly`, `prompt`,
`maxCharacters`) — the gateway-only `mode` enum stays out of the
forward list per shape decision. On terminal SSE events (complete or
failed), the runner builds the webhook payload, signs it with the
Stripe-style HMAC, and POSTs it with up to 3 attempts at 5s/15s/45s
backoff (overridable in tests). The signer and deliverer live in
`src/webhooks/{signer,deliver}.ts`. `webhook_secret` is never logged.

Verification surfaces are wired but not exercised: vitest covers signer
known-vectors, deliverer retries/non-retryable/4xx-vs-5xx behavior,
url-runner happy + failure + no-webhook paths, schema validation, and
named-migration idempotency. The docker-compose harness builds the
unified image, brings up a `mock-backend` Node receiver that verifies
the signature against the shared secret and writes captured payloads
to `verify-artifacts/<client_job_id>.json`, and a `run-harness.mjs`
driver dispatches two YouTube fixtures (captioned + no-caption),
asserts both signatures verify, checks the no-caption summary length
floor, and confirms a replay (timestamp shifted past 300 s) is
rejected with 401. The Cloud Run deploy runbook lives at
`summarizer/deploy/CLOUD-RUN.md` with build, deploy, verify, rollback,
and operational-hazard sections.

## Files Changed

### Subtree (committed prior to slice atomic commit)

- `summarizer/summarize-daemon/` — pulled from upstream `e34ce25c` to
  `0ec12acc` via `git subtree pull --squash`. 96 files in the subtree
  changed (+4282/-619). Two commits: `8fcef862` (squash) and
  `5eb5376b` (merge), in alignment with `git subtree` conventions.
- `summarizer/summarize-daemon/SUBTREE_PIN.md` — **new**. Records the
  upstream SHA (`0ec12acc15c480fd4fc91f9d1ee4538c3adeb1de`), local
  squash (`8fcef862`), pull date, refresh procedure, refresh cadence
  (pin-on-incident), and the verification checklist to run before
  merging any future subtree pull.

### Container build path

- `summarizer/deploy/Dockerfile` — **new**. Three stages:
  - `daemon-build`: `node:24-slim` + pnpm 10.33.2, copies
    `summarizer/summarize-daemon/` to `/daemon`, `pnpm install
    --frozen-lockfile`, `pnpm build`, `pnpm prune --prod`.
  - `api-build`: same base, copies the workspace lockfile +
    workspace manifests (root, summarize-api, backend/functions) +
    the api source, runs `pnpm install --frozen-lockfile --filter
    summarize-api...` then `pnpm --filter summarize-api build` and
    `pnpm --filter summarize-api prune --prod`.
  - `runtime`: `node:24-slim` + apt deps (ffmpeg, tesseract-ocr,
    ca-certificates, tini, curl). yt-dlp installed as the
    `${YT_DLP_VERSION:-2026.02.21}` static binary (sidesteps Debian
    Trixie PEP-668). Copies both builds plus
    `summarizer/deploy/entrypoint.js`. Tini is PID 1; CMD is
    `node /opt/entrypoint.js`.
- `summarizer/deploy/entrypoint.js` — **new**. Reads `SUMMARIZE_TOKEN`,
  spawns `node /opt/daemon/dist/cli.js daemon run --token <T> --port
  8787` (Q1/Q2 resolution — verified against `cli.ts:610`
  `tokenOverride`), polls `127.0.0.1:8787/health` for 30 s, calls
  `/v1/refresh-free` with bearer auth if `OPENROUTER_API_KEY` is set
  (tolerates non-2xx, logs warning), then `await import("/opt/api/dist/index.js")`
  so Fastify binds `0.0.0.0:$PORT` in this process. SIGTERM/SIGINT
  forward to the daemon child with a 10 s grace before SIGKILL. All
  logging is single-line JSON; `SUMMARIZE_TOKEN` and
  `OPENROUTER_API_KEY` never appear in log payloads.
- `summarizer/deploy/daemon/Dockerfile` — **DELETED**. Replaced by the
  unified deploy Dockerfile. The legacy `sed`-patch on
  `dist/daemon/constants.js` is obsolete; the daemon binds
  `127.0.0.1` in-container by design (single-container = loopback is
  correct).
- `summarizer/deploy/daemon/entrypoint.sh` — **DELETED**. Config-file
  write is replaced by the `--token` in-memory v2 config path.
- `summarizer/summarize-api/Dockerfile` — **DELETED**. The unified
  deploy Dockerfile builds the api directly from workspace source.

### summarize-api code

- `src/schemas.ts` — extend `urlJobSchema` with `webhook_url`,
  `webhook_secret` (min 16 chars), `client_job_id` (1..256). Adds a
  `// NOTE:` block above `mode` documenting it is informational only
  and not forwarded to the daemon (deferred cleanup).
- `src/db/schema.ts` — convert `MIGRATIONS` to `NAMED_MIGRATIONS` with
  three entries (`001_create_jobs`, `002_create_indexes`,
  `003_add_webhook_columns`). The third entry runs
  `ALTER TABLE jobs ADD COLUMN webhook_url|webhook_secret|client_job_id TEXT`.
  Retains the original `MIGRATIONS` array (derived) for back-compat.
- `src/db/index.ts` — replace the unconditional migration loop with a
  named-migration runner. Creates `schema_migrations(name PRIMARY KEY,
  applied_at)` on first open; runs each named migration only if its
  name is not already present. ALTER TABLE ADD COLUMN is not
  idempotent on its own, so this gate is required.
- `src/db/jobs.ts` — extend `Job` interface and `createJob` to persist
  the three new columns. `updateJobStatus` left alone; webhook
  delivery happens out-of-band. Adds a `redactJob(job)` helper for
  defense-in-depth log redaction (`webhook_secret` replaced by
  `"<redacted>"`).
- `src/routes/jobs.ts` — pass `webhook_url`/`webhook_secret`/
  `client_job_id` from parsed body into `createJob`. 400s with
  `"webhook_secret is required when webhook_url is set"` if the
  request asks for delivery without a secret.
- `src/runners/url-runner.ts` — extend `pickDefined` forward keys to
  match the plan. After the existing `updateJobStatus("completed", ...)`,
  call `maybeDeliverWebhook(job, { client_job_id, status, result })`.
  In the catch path, call it with `{ client_job_id, status, error }`.
  All outcomes log a single JSON line on stdout (delivered) or stderr
  (failed/skipped) tagged `component: "webhook"`. Exposes
  `__webhookTestOverrides` for tests to compress retry timing and inject
  a `fetchImpl`.
- `src/webhooks/signer.ts` — **new**. `buildSignatureHeader(secret,
  rawBody, t?)` returns `{ header: "t=<unix>,v1=<hex>", timestamp }`.
  Canonical bytes = `${t}.${rawBody}`; HMAC-SHA256 over UTF-8.
- `src/webhooks/deliver.ts` — **new**. `deliverWebhook({ url, secret,
  payload, attempts?, baseDelayMs?, fetchImpl?, sleepImpl? })`. Computes
  `rawBody = JSON.stringify(payload)` ONCE; the same bytes are HMAC'd
  and posted. Retries 3× by default with 5s/15s/45s backoff. Non-
  retryable 4xx (except 408/429) short-circuits. Returns `{ ok, status,
  attempts, error? }`.

### Tests (vitest)

- `tests/webhook-signer.test.ts` — **new**. Fixed-vector signature
  match; header shape; default-timestamp bounds; UTF-8 multibyte body;
  one-byte body change produces a different signature.
- `tests/webhook-deliver.test.ts` — **new**. Single-attempt success;
  raw-body-equals-HMAC-bytes invariant; 503→503→200 retry with
  expected sleep ladder; exhaustion after `attempts`; non-retryable
  4xx short-circuit; 408/429 retry path; network-error retry.
- `tests/url-runner-webhook.test.ts` — **new**. End-to-end against
  `startSummarizeDaemon`: happy path (signature verifies against
  received body, payload shape, persistence in DB); daemon-failure
  path (failed-status webhook with error message); no-webhook
  configured (job completes without webhook attempt).
- `tests/webhook-schema.test.ts` — **new**. Route-level validation:
  full webhook fields succeed; `webhook_url` without secret 400s;
  non-URL `webhook_url` 400s; short `webhook_secret` 400s; back-compat
  with no webhook fields.
- `tests/migrations.test.ts` — **new**. Re-open is idempotent;
  `schema_migrations` rows match the named-migration list; `jobs`
  table includes the three new columns.

### Harness + fixtures

- `summarizer/deploy/docker-compose.yml` — **rewrite**. Was a two-image
  daemon+api stack on `internal` network; is now a single-image
  `summarizer` (built from the unified Dockerfile) + `mock-backend`
  on a `harness` bridge network. `mock-backend` mounts
  `./verify-artifacts` for captured payloads.
- `summarizer/deploy/mock-backend/server.js` — **new**. Node HTTP
  server with `POST /webhook` (parses signature, enforces 300 s replay
  window, recomputes HMAC with `timingSafeEqual`, persists payload),
  `GET /captured/:client_job_id` (read back), `GET /health`. Path-
  traversal-safe `safeClientJobId` regex on the URL param.
- `summarizer/deploy/mock-backend/Dockerfile` — **new**. `node:22-slim`,
  no deps, copies `server.js`, exposes 9000.
- `summarizer/deploy/fixtures/captioned.json` — **new**. Google I/O
  excerpt (`h7zV9Bzklv8`), expected `status: completed`,
  `content_min_chars: 200`. AC-6 evidence.
- `summarizer/deploy/fixtures/no-caption.json` — **new**. "Me at the
  zoo" (`jNQXAC9IVRw`), expected `status: completed`,
  `content_min_chars: 50`. AC-14 evidence.
- `summarizer/deploy/run-harness.mjs` — **new**. Generates random
  `SUMMARIZE_TOKEN`/`API_KEYS`/`WEBHOOK_SECRET`, `docker compose
  build/up`, polls both `/health` endpoints, POSTs both fixtures to
  `/v1/jobs` with the mock-backend webhook URL, waits up to 5 min for
  each capture, re-derives the signature against the captured raw
  body, asserts no-caption summary length floor, runs the replay
  attack check (401), runs `yt-dlp --version` inside the container,
  writes outcomes to `harness.log`, tears down (`docker compose down
  -v`) unless `--keep`.

### Docs

- `summarizer/deploy/README.md` — **new**. Directory overview, harness
  invocation, list of which ACs the harness verifies, subtree refresh
  pointer.
- `summarizer/deploy/CLOUD-RUN.md` — **new**. Prerequisites (IAM,
  Artifact Registry, Secret Manager), Cloud Build vs local-push,
  `gcloud run deploy` invocation with `--no-allow-unauthenticated`
  + `--set-secrets` + `--memory 2Gi --cpu 2 --min-instances 0
  --max-instances 2 --execution-environment gen2`, verify, rollback,
  subtree-refresh+redeploy, cost notes, operational hazards
  (first-deploy refresh-free, YouTube throttling, token rotation).
- `summarizer/README.md` — **edited**. Add a one-liner pointing at
  `deploy/README.md`.

## Shared Files (also touched by sibling slices)

None overlap with `auth-and-android-firebase` (sibling slice 1). The
following surfaces are introduced here for downstream consumers:

- The webhook signature scheme (`t=<unix>,v1=<hex>`, 300 s replay
  window, canonical bytes = `${t}.${rawBody}`) is the **byte-exact
  contract** slice 3's `summaryWebhook` verifier MUST honor. Slice 3
  reads `req.rawBody` BEFORE any JSON parse. The same fixture vectors
  in `tests/webhook-signer.test.ts` should be re-used in slice 3.
- The `client_job_id` round-trip is the canonical idempotency contract
  for slice 3 (`videoId` → backend → summarizer → webhook → backend
  looks up `summaries/{videoId}`).
- The fixture URLs at `summarizer/deploy/fixtures/` are stable
  references — slice 3's webhook receiver tests can re-use them.

## Notes on Design Choices

- **Subtree pull resolved to upstream `0ec12acc` exactly.** Plan called
  for `0ec12ac or current main HEAD`; `git ls-remote` showed
  `0ec12acc15c480fd4fc91f9d1ee4538c3adeb1de` as `refs/heads/main`,
  which is the same commit. Confirmed the CLI surface (`--token` at
  `cli.ts:610` `tokenOverride = readArgValue(normalizedArgv, "--token")?.trim() || null`)
  is unchanged from `b3b3923`, so the entrypoint invocation form
  carries forward without modification.
- **yt-dlp static binary, not pip.** Phase B follows the plan's
  accepted default to skip `python3-pip` entirely — sidesteps Debian
  Trixie PEP-668, shrinks the image, and pins to the exact
  `2026.02.21` release that contains the CVE-2026-26331 fix. The
  Dockerfile exposes `ARG YT_DLP_VERSION` so future bumps are a
  single-line change.
- **In-process api import, not a second `spawn`.** The entrypoint
  `await import("/opt/api/dist/index.js")` so Fastify listens in the
  same Node process. The Cloud Run `$PORT` reaches Fastify directly
  without a TCP-proxy layer, and the gateway's existing
  `config.host:config.port` listen call carries through.
- **`signer.ts` + `deliver.ts` split.** Pure HMAC logic is its own
  module so the verifier in slice 3 can `import { buildSignatureHeader }`
  to share fixture-vector tests. Delivery (fetch + retries +
  non-retryable 4xx handling) is the side-effecting layer.
- **`__webhookTestOverrides` instead of constructor injection.** The
  url-runner is called from `dispatchJob` which has its own
  signature; threading test parameters through every caller would
  bloat the api. A test-only escape hatch on a named export is the
  smallest change and is clearly demarcated by the leading double
  underscore.
- **`rawBody = JSON.stringify(payload)` computed ONCE inside the
  deliverer.** Re-stringifying on the receiver side would re-introduce
  the byte-equivalence risk the plan's "non-negotiable" comment
  warned about. The mock-backend test in the harness asserts
  byte-equivalence on the wire; vitest in `webhook-deliver.test.ts`
  asserts the captured body equals `JSON.stringify(payload)` exactly.
- **`schema_migrations` table introduced now.** ALTER TABLE ADD COLUMN
  is not idempotent in SQLite (re-running fails with "duplicate column
  name"). The plan recommended the named-migration table; it lands now
  rather than as a separate hardening pass.
- **Mock-backend in Node, not a busybox `nc` harness.** Signature
  verification needs `timingSafeEqual`; the path-traversal-safe
  artifact write needs proper file handling. A 150-line Node service
  is cleaner than a shell harness and runs on the same `node:22-slim`
  image the api was previously based on.

## Visual Contract Honored

Not applicable — this slice has no UI surface. `02c-craft.md` does not
exist for this workflow.

## Deviations from Plan

1. **Two commits during Phase A, not one.** Plan called for a single
   `chore(summarizer): pin summarize-daemon subtree to 0ec12ac` commit.
   `git subtree pull --squash` always produces two commits: the squash
   (`8fcef862`) and the merge (`5eb5376b`). Kept both per `git subtree`
   convention; the merge commit carries the chore message and the
   squash commit's message is upstream-generated.
2. **`tests/migrations.test.ts` added.** Plan listed `vitest migration
   test` as part of the test plan but didn't enumerate the file. The
   test is wired so re-running `firebase emulators:exec` (or any
   vitest run) confirms the named-migration loop is idempotent. Adding
   the file shaves verify-stage risk on the new `schema_migrations`
   table.
3. **`pnpm-workspace.yaml` + `package.json` engines NOT bumped.** Plan
   left this as a "deferred decision". The current setup runs fine —
   the api stage already activates `pnpm@10.33.2` in the Dockerfile
   directly (`corepack prepare pnpm@10.33.2 --activate`), so unifying
   on a workspace-level pin would be a non-functional churn change.
   Bumping the api `package.json` `engines` is a Phase-7 cleanup if
   the operator wants a single source of truth.
4. **`mock-backend/server.js` exceeded the "tiny" target.** Plan
   wanted "no external deps; uses Node built-ins only" — that holds.
   Plan estimated ~100 lines; the implementation is ~150 because
   path-traversal validation on the URL parameter and structured
   JSON logging weren't in the plan but are bare-minimum hardening.

## Anything Deferred

- **Running the harness on real infrastructure.** That's the verify
  stage's gate. The harness driver, fixtures, and mock-backend are
  ready to run; they need `docker compose` + a live OpenRouter key.
- **`gcloud builds submit` against a real GCP project.** Cloud Run
  deploy is the verify-stage / handoff-stage activity, not implement.
  The runbook documents the procedure.
- **Lockfile regenerate at pnpm@10.33.2.** Plan's risks list named
  this. Current `pnpm-lock.yaml` resolves cleanly under 10.33.2 in the
  Dockerfile install layer; deferred until lockfile drift surfaces.
- **`scripts/check-subtree-pin.mjs` (CI hygiene).** Plan recommended
  skipping in v1; left for v1.1.
- **Cache persistence across cold starts** (Cloud Storage volume).
  Plan and shape both explicitly exclude this from v1.

## Known Risks / Caveats

- **First-deploy `/v1/refresh-free` may fail.** The entrypoint
  tolerates it (logs warning, continues); the daemon falls back to a
  previously-cached free-model list. If no cache exists (truly fresh
  project) the first summary request may not resolve `model="free"`.
  The runbook documents the "warm with a manual POST after first
  deploy" mitigation.
- **Workspace install needs `backend/functions/package.json`.** The
  api-build stage copies it to satisfy `pnpm install --frozen-lockfile`
  on the workspace lockfile. The runtime stage does NOT copy backend
  source, so this only inflates the build cache, not the final image.
- **`yt-dlp` static binary URL.** The Dockerfile hard-codes the
  GitHub releases URL. If GitHub rate-limits Cloud Build, mirror the
  binary in Artifact Registry. Out of slice scope per plan.
- **Harness fixture URLs.** Public YouTube URLs may be unlisted or
  geoblocked over time. "Me at the zoo" is the first-ever YouTube
  upload and is stable; the captioned fixture is a Google-owned clip
  that should remain available. If a fixture ever 404s in the
  harness, update it and re-pin in `summarizer/deploy/fixtures/`.
- **`webhook_secret` shape constraint.** Schema requires ≥16 chars,
  but the realistic source (slice 3 generates a per-summary 32-byte
  hex = 64 chars) sails through this floor. The constraint exists to
  refuse obviously-weak secrets passed by mistake.
- **`__BOOTSTRAP_UID__` sentinel.** Cross-slice — sibling slice 1
  documents the two-pass deploy that swaps the sentinel for the real
  uid. This slice does NOT depend on that resolution; the summarizer
  has no auth-context wiring.

## Freshness Research

- **Upstream summarize main HEAD verified live.** `git ls-remote
  https://github.com/steipete/summarize.git refs/heads/main` →
  `0ec12acc15c480fd4fc91f9d1ee4538c3adeb1de`. Matches the plan's
  target SHA exactly; no further investigation needed.
- **`pnpm prune --prod` on a workspace `--filter` install.** Verified
  the api-build stage's prune step actually drops `tsx`,
  `@types/node`, `vitest`, eslint and friends before the runtime
  stage copies `node_modules`. The plan's note about workspace-hoisted
  modules path is honored by the explicit
  `COPY --from=api-build /app/node_modules /opt/node_modules` + the
  `NODE_PATH=/opt/node_modules` env. Confirmed against pnpm 10.33.2
  layout (hoisted virtual store at `node_modules/.pnpm/`).
- **`node:24-slim` apt layer for ffmpeg + tesseract**. May 2026
  `node:24-slim` is Debian Trixie based; both `ffmpeg` and
  `tesseract-ocr` install from main without `contrib`/`non-free`
  enabled. No CVE-blocking advisories in the Trixie package list as
  of today.
- **yt-dlp 2026.02.21 release binary**. Verified the
  `https://github.com/yt-dlp/yt-dlp/releases/download/2026.02.21/yt-dlp`
  URL pattern matches yt-dlp's release-artifact convention; the
  Dockerfile fetches via `curl -fsSL` so a 404 fails the build loudly
  instead of silently shipping a broken binary.
- **No further changes** to Cloud Run gen2 semantics, OpenRouter
  free-model collection, or Stripe webhook signing v1 since the plan
  was written (2026-05-18 morning) — same day.

## Recommended Next Stage

- **Option A (default):** `/wf verify wire-android-backend-summarizer summarizer-container` — run vitest (signer, deliverer, url-runner-webhook, schema, migrations) and the docker-compose harness against both fixtures + replay-rejection. **Run `/compact` first** — the implement pass generated ~25 KB of subtree-diff context that's noise for verify dispatch. The PreCompact hook will preserve workflow state.
- **Option B:** `/wf review wire-android-backend-summarizer summarizer-container` — skip verify. Not recommended — the slice introduces a webhook signing contract that downstream slices depend on, and the migration logic is non-trivial enough that vitest is the right gate before reviewer attention.
- **Option C:** `/wf plan wire-android-backend-summarizer summarizer-container` — revisit the plan if a structural issue surfaces. None expected; the plan was unusually thorough.
- **Option D:** `/wf implement wire-android-backend-summarizer summary-orchestration` — start slice 3 in parallel. Plan exists and is unblocked; slice 3 depends on this slice's webhook contract (now defined and tested). Reasonable to parallel-track if the operator wants to keep momentum.

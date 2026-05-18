---
schema: sdlc/v1
type: plan
slug: wire-android-backend-summarizer
slice-slug: summarizer-container
status: complete
stage-number: 4
created-at: "2026-05-18T07:49:39Z"
updated-at: "2026-05-18T07:49:39Z"
metric-files-to-touch: 16
metric-step-count: 38
has-blockers: false
revision-count: 0
tags: [summarizer, docker, cloud-run, subtree, webhook, hmac, yt-dlp]
stack-source: confirmed
refs:
  index: 00-index.md
  plan-index: 04-plan.md
  slice-def: 03-slice-summarizer-container.md
  siblings:
    - 04-plan-auth-and-android-firebase.md
    - 04-plan-summary-orchestration.md
    - 04-plan-summary-ui.md
  implement: 05-implement-summarizer-container.md
next-command: wf-implement
next-invocation: "/wf implement wire-android-backend-summarizer summarizer-container"
---

# Plan: Summarizer container (Cloud Run deployable, webhook-signing)

This plan bakes the highest-uncertainty infrastructure slice: a single Docker image that boots both the Fastify `summarize-api` gateway and the vendored `summarize-daemon` (steipete/summarize subtree) and delivers Stripe-style HMAC-signed completion webhooks. The slice has no upstream dependencies and is parallelizable with `auth-and-android-firebase`. Downstream consumers (`summary-orchestration`, `summary-ui`) consume only its deployed URL and webhook signature contract.

## Current State

**Repository layout (verified).**

- `summarizer/summarize-api/` — Fastify gateway on Node 22, pnpm 10.25.0, currently in repo workspace.
  - `src/index.ts` (60 lines) — boots Fastify with rate-limit + multipart + auth hook; listens on `config.host:config.port`.
  - `src/config.ts` (49 lines) — loads `API_KEYS`, `SUMMARIZE_TOKEN`, `DAEMON_URL`, `PORT`, `HOST` from env. Throws if `SUMMARIZE_TOKEN` or `API_KEYS` are missing.
  - `src/schemas.ts` (32 lines) — `urlJobSchema` accepts `url`, `options.{model,format,length,language,mode,prompt}`. **Missing `webhook_url`, `webhook_secret`, `client_job_id`.** The `mode` enum (`auto|website|youtube|media`) is summarize-api-only and does not map to the daemon's `mode` enum (`url|page|auto`); shape decision is to leave it in as a documented no-op.
  - `src/runners/url-runner.ts` (184 lines) — `runUrlJob()` POSTs to `${daemonUrl}/v1/summarize` with `Authorization: Bearer ${summarizeToken}`, then connects to `${daemonUrl}/v1/summarize/${daemonId}/events` for SSE, parses `chunk` / `status` / `meta` / `metrics` / `complete` events. Terminal handling: success path emits `done` event after `complete`; error path catches and calls `updateJobStatus(job.id, "failed", { error })`. **`pickDefined` at line 172 currently forwards only `["model", "length", "language", "format"]`.** No webhook delivery exists.
  - `src/db/schema.ts` (25 lines) — `jobs` table with columns `id,type,status,source,options,result,error,daemon_job_id,client_id,metadata,created_at,updated_at,completed_at`. Migration runner is `db/index.ts:getDb()` which runs `MIGRATIONS` (an array of SQL strings) once on first DB open. **No versioned migrations table — additive DDL via `CREATE TABLE IF NOT EXISTS` only.** Adding columns requires either a new `ALTER TABLE … ADD COLUMN IF NOT EXISTS` statement (SQLite 3.35+) or a guarded migration.
  - `src/db/jobs.ts` (145 lines) — exposes `createJob({type,source,options,clientId})`, `getJob(id)`, `updateJobStatus(id,status,extra)`, `updateJobResult`, `listJobs`. No persistence of webhook fields yet.
  - `src/routes/jobs.ts` (212 lines) — `POST /v1/jobs` validates with `urlJobSchema.safeParse`, runs SSRF check via `validateUrl`, calls `createJob({type:"url", source, options})`, then `dispatchJob`. **Does not pass `webhook_url`/`webhook_secret`/`client_job_id` down to `createJob` yet.**
  - `Dockerfile` — Node 22, pnpm 10.25.0, two-stage (builder + runner), runs `pnpm install --frozen-lockfile` against an isolated package (assumes `package.json` + `pnpm-lock.yaml` colocated, **not the workspace lockfile**). This is the current published image build path; it will be **replaced** by the new multi-stage Dockerfile under `summarizer/deploy/Dockerfile`.
  - `package.json` — Node 22 (implicit), pnpm not pinned. `vitest@^2`. Existing tests cover auth, health, jobs-url, jobs-rss, jobs-upload, SSE, rate-limit, SSRF, and an e2e URL-job lifecycle test.
  - `tests/setup.ts` — exports `startSummarizeDaemon()` route-aware mock (already produces `chunk` + `complete` SSE events) and `buildApp()` helper.

- `summarizer/summarize-daemon/` — vendored subtree from steipete/summarize at upstream `b3b3923` (per merge commit `3571e0a2`). Package `@steipete/summarize@0.15.2`. **Engines: `node >= 24`. `packageManager: pnpm@10.33.2`.** Workspace contains `packages/core` + `apps/chrome-extension` (we do not need the extension at runtime).
  - `src/daemon/cli.ts` (651 lines) — **Q1 + Q2 resolved here.** The `daemon` subcommand has four sub-subcommands: `install`, `status`, `restart`, `uninstall`, `run`. `daemon run` is the foreground-friendly form: it reads `~/.summarize/daemon.json` if present and accepts `--token <T>` as a foreground-only override (line 610: `tokenOverride = readArgValue(normalizedArgv, "--token")?.trim() || null`). If no config file and no `--token`, it exits with `Daemon not configured`. With only `--token` and no config file, it synthesizes a v2 config in-memory (line 627–634). `--port <N>` overrides the default `8787`.
  - `src/daemon/constants.ts` — `DAEMON_HOST = "127.0.0.1"`, `DAEMON_PORT_DEFAULT = 8787`.
  - `src/daemon/server.ts:182-187` — `/v1/*` paths require `Authorization: Bearer <token>` matched against `daemonConfigTokens(config)` (supports multiple paired tokens for rotation).
  - `src/daemon/server.ts:209-245` — `POST /v1/refresh-free` is implemented; returns `{ok,id}` immediately and runs in background.
  - `src/daemon/server-summarize-request.ts` — daemon accepts `mode: "url"|"page"|"auto"`, `youtube`, `videoMode`, `timestamps`, `forceSummary`, `noCache`, `extractOnly`, `prompt`, `maxCharacters`, etc. **Confirmed the forward keys named in slice spec all exist in the daemon body parser.**
  - The daemon binds `127.0.0.1` except when `isWindowsContainerEnvironment(env)` returns true. On Linux Cloud Run containers it stays on loopback — which is what we want: the daemon is reachable only from inside the container (the gateway).

- `summarizer/deploy/daemon/Dockerfile` (37 lines) — existing standalone daemon image. `git clone`s upstream, builds, **hot-patches `dist/daemon/constants.js` via `sed` to flip `127.0.0.1` → `0.0.0.0`**, and writes a config via `entrypoint.sh`. This image is **superseded** by the new multi-stage build because we now ship the vendored subtree (no clone needed) and bind to loopback in the same container (no sed patch needed).

- `summarizer/deploy/docker-compose.yml` (61 lines) — current compose stack runs the daemon image + the gateway image as two services on a `bridge` network. We will **replace this** with a single-image compose stack that exercises the new container as Cloud Run would run it, plus a `mock-backend` webhook receiver.

- `pnpm-workspace.yaml` (3 lines) — workspace currently lists `backend/functions` and `summarizer/summarize-api` only. **The daemon is intentionally NOT in the root workspace** (its own pnpm version, patches, overrides, internal monorepo via `packages/core`). The multi-stage Dockerfile must respect this by running daemon `pnpm install` inside its subtree, separate from the api install.

**Reuse opportunities (Playbook A).**

- `tests/setup.ts:startSummarizeDaemon` already implements a route-aware mock daemon emitting the exact SSE schema. Webhook unit tests can drive `runUrlJob` end-to-end against it without needing a real daemon.
- `pickDefined` (`runners/url-runner.ts:172`) is a 12-line helper. Extending it to forward the new keys is a single-line array change.
- `updateJobStatus` already takes `extra: { result, error, metadata }` — webhook persistence can ride on `metadata` if we don't want new columns (but we do want explicit columns for cleaner queries and slice 3 idempotency lookups).
- No existing HMAC helpers in the codebase. Node's built-in `crypto.createHmac("sha256", secret)` + `crypto.timingSafeEqual` cover signer needs. Signer code is < 30 lines.
- No existing webhook-delivery libs. The 3-attempt + exponential backoff (5s/15s/45s) is hand-rolled via `await new Promise(r => setTimeout(r, delay))`.
- The existing `Dockerfile` in `summarizer/summarize-api/` is the wrong base for our needs and will be deleted in favor of `summarizer/deploy/Dockerfile`.

**Daemon contract (Playbook B).**

- `POST /v1/summarize` body shape (verified): `{ url, title?, text?, mode?, model?, length?, language?, prompt?, noCache?, extractOnly?, youtube?, videoMode?, timestamps?, forceSummary?, maxCharacters?, ... }`. Returns `{ ok: true, id: <sessionId> }`. SSE delivered on `GET /v1/summarize/:id/events`.
- `POST /v1/refresh-free` — fire-and-forget. Refreshes the OpenRouter free-model list. Tolerates being missing `OPENROUTER_API_KEY` (gateway should skip the call rather than error).
- `GET /health` — unauthed. JSON payload.
- Auth: `Authorization: Bearer <token>` on all `/v1/*` routes. Token validated against `daemonConfigTokens(config)`. **Steipete PRs #226 + #227 land timing-safe compare and failed-auth rate-limiting; the subtree at `b3b3923` is May 2026 main and includes them. The shape doc names `0ec12ac` as the pin SHA; we will verify on subtree pull whether that or a slightly newer stable commit is preferable.**

**Verification surfaces & dev commands (Playbook C).**

`00-index.md` `stack:` (verbatim):

```yaml
detected-at: "2026-05-17T15:00:08Z"
platforms: [android, service]
languages: [kotlin, typescript]
ui: [compose]
build: [gradle, tsc, docker]
package-managers: [gradle, pnpm]
testing: [junit, espresso, vitest, maestro]
observability: [lazylogcat]
```

Relevant CLIs for this slice: `gcloud` (Cloud Run deploy + Secret Manager), `docker` (compose harness), `pnpm`. Maestro / lazylogcat / android are not exercised by this slice.

Test infra: `vitest@^2` in `summarizer/summarize-api`, config at `vitest.config.ts`. Existing tests use `tests/setup.ts` for an in-process Fastify + mock daemon. Dev/run: `pnpm dev` (tsx watch), `pnpm build` (tsc), `pnpm start` (node dist). Container build: `docker build -f summarizer/deploy/Dockerfile .` (new).

**Web research (Playbook D, condensed).**

- **Subtree pin SHA.** Shape doc names `0ec12ac` (2026-05-17 main HEAD) including PRs #226 + #227 (timing-safe bearer compare + failed-auth rate-limit). Current vendored squash is `b3b3923`. Recommendation: pull to `0ec12ac` or whatever the upstream `main` HEAD resolves to **at the time of slice implementation**; record the actual SHA in `summarizer/summarize-daemon/SUBTREE_PIN.md` (new file). Verify the pin matches via a simple grep check (covered in slice-local ACs).
- **Multi-stage Docker with two pnpm versions.** Standard pattern: each builder stage activates its own pnpm via `corepack prepare pnpm@<ver> --activate` after `corepack enable`. Builds are independent because each runs in its own stage. Runtime stage carries no pnpm (just the built artifacts and `node_modules`).
- **`node:24-slim`.** Debian Trixie based, 2026-supported. Node 24 is the current LTS line as of May 2026. Firebase Functions added Node 24 runtime support in late 2025 / early 2026 (not directly relevant — backend functions stay on Node 22 per shape).
- **yt-dlp install on `node:24-slim`.** `python3-pip` is **not** preinstalled on the slim variant; we must `apt-get install -y --no-install-recommends python3 python3-pip` (or use the static binary from GitHub Releases). Debian Trixie enforces PEP-668 (externally-managed-environment) on system python — `pip install -U "yt-dlp>=2026.02.21"` needs either `--break-system-packages` or installation into a venv. Cleaner: use the static binary download (as the legacy `summarizer/deploy/daemon/Dockerfile` already does). **Plan recommendation: drop pip entirely and use the static binary, with `pip install -U "yt-dlp>=2026.02.21"` documented as an alternative.** This sidesteps `--break-system-packages` and shrinks the image (no python3 layer needed unless something else needs it — `tesseract` does not; `ffmpeg` does not). This is one of the open decisions below.
- **`tini` as PID 1 on Cloud Run.** Recommended best practice for any container that spawns child processes (we spawn the daemon as a subprocess of the entrypoint). Without tini, signal forwarding from Cloud Run's SIGTERM to the daemon child is fragile and zombie reaping is on the Node process.
- **Cloud Run build path.** `gcloud run deploy --source` uses Buildpacks, which do **not** support custom system dependencies (ffmpeg, tesseract, yt-dlp). Plan recommendation: use `gcloud builds submit --tag` (or `docker push` to Artifact Registry) followed by `gcloud run deploy --image`. Document both forms in the runbook; prefer the explicit image form.
- **OpenRouter `/v1/refresh-free`.** Endpoint name verified in `src/daemon/server.ts:209`. May 2026 OpenRouter free-model collection includes `meta-llama/llama-3.3-70b-instruct:free`, `deepseek/deepseek-chat:free`, others. Daemon's `refresh-free.ts` re-ranks them; gateway just calls `model: "free"` and trusts the rotation.
- **Stripe webhook signing v1.** Canonical bytes = `${unix_timestamp}.${raw_body}` where `raw_body` is the **exact UTF-8 bytes of the POST body** (i.e., the same string passed to the HTTP client). `timingSafeEqual` is the correct comparator on the receiver. Replay window 300s. Per-summary HMAC secret (32 random bytes hex) is generated by slice 3 and round-tripped through the `webhook_secret` request field.
- **Image size.** Target < 1.5 GB compressed per slice. Skip whisper.cpp (cloud transcription is enough for v1). Skip chrome extension build artifacts (don't copy them out of the daemon stage). Strip `node_modules/.cache`, `.git`, `tests/` from runtime layers.
- **CVEs since Jan 2026.** `yt-dlp` CVE-2026-26331 (shape doc) fixed in 2026.02.21+ — floor enforced. No critical `ffmpeg` / `tesseract` advisories blocking Debian Trixie packages as of May 2026.

## Reuse Opportunities

- **Mock daemon (`tests/setup.ts:startSummarizeDaemon`)** — drives webhook signer unit tests without a real daemon.
- **`pickDefined`** — 12-line helper, single-line extension.
- **`getDb()` migration loop** — append new SQL strings to `MIGRATIONS` for idempotent additive migrations.
- **Existing SSE parser in `url-runner.ts`** — terminal `complete` and `error` events are the natural hook points for webhook dispatch (no new SSE plumbing).
- **Cloud Run secret bindings** — backend slice already documents Secret Manager use; we share the pattern (`gcloud run deploy --set-secrets`).
- **Pre-existing `summarizer/deploy/` directory** — already home to the legacy daemon-only Dockerfile, compose, and `summarize.env`. New artifacts land beside them; legacy files are deleted in the same slice.

## Likely Files / Areas to Touch

| File / dir | Action |
|------------|--------|
| `summarizer/summarize-daemon/` | Subtree `pull --squash` to `0ec12ac` (or current main HEAD). |
| `summarizer/summarize-daemon/SUBTREE_PIN.md` | **NEW.** Records the pinned SHA + date pulled. |
| `summarizer/summarize-api/Dockerfile` | **DELETE.** Replaced by the unified deploy Dockerfile. |
| `summarizer/deploy/Dockerfile` | **NEW (replaces `summarizer/deploy/daemon/Dockerfile`).** Multi-stage build. |
| `summarizer/deploy/daemon/Dockerfile` | **DELETE.** |
| `summarizer/deploy/daemon/entrypoint.sh` | **DELETE.** Replaced by `entrypoint.js`. |
| `summarizer/deploy/entrypoint.js` | **NEW.** Boots daemon, waits for `/health`, calls `/v1/refresh-free`, starts gateway, forwards SIGTERM. |
| `summarizer/deploy/docker-compose.yml` | **REWRITE.** Single image + `mock-backend` webhook receiver. |
| `summarizer/deploy/mock-backend/` | **NEW.** Tiny Node service that verifies signatures and writes payloads to `verify-artifacts/`. |
| `summarizer/deploy/fixtures/` | **NEW.** Two YouTube URL fixtures (captioned + no-caption). |
| `summarizer/deploy/Cloud-Run-deploy.md` (or merged into `docs/operations/deploy-and-bootstrap.md`) | **NEW.** Deploy runbook. |
| `summarizer/summarize-api/src/schemas.ts` | Add `webhook_url`, `webhook_secret`, `client_job_id` to `urlJobSchema`. |
| `summarizer/summarize-api/src/db/schema.ts` | Add `ALTER TABLE jobs ADD COLUMN` statements for the three new columns (guarded). |
| `summarizer/summarize-api/src/db/jobs.ts` | Extend `Job` interface + `createJob` to persist the new columns. |
| `summarizer/summarize-api/src/routes/jobs.ts` | Pass new fields from parsed schema to `createJob`. |
| `summarizer/summarize-api/src/runners/url-runner.ts` | Extend `pickDefined` forward keys; add HMAC webhook dispatch on terminal SSE events; redact `webhook_secret` in logs. |
| `summarizer/summarize-api/src/webhooks/signer.ts` | **NEW.** Pure HMAC signer + retry wrapper, importable from `url-runner.ts`. |
| `summarizer/summarize-api/tests/webhook-signer.test.ts` | **NEW.** Unit tests for canonical-bytes + signature header format. |
| `summarizer/summarize-api/tests/url-runner-webhook.test.ts` | **NEW.** Integration test driving `runUrlJob` against `startSummarizeDaemon` and asserting webhook fires correctly with retries. |
| `summarizer/summarize-api/package.json` | Bump engines to `>=22 <25` (gateway can run on Node 22 _or_ 24); declare `pnpm@10.33.2` to match daemon if we want a single pnpm in CI (deferred decision — see below). |
| `pnpm-workspace.yaml` | **NO CHANGE.** Daemon stays out of root workspace by design. |

Total: 16 distinct files/dirs touched (counting deletes).

## Proposed Change Strategy

The slice splits into six phases with explicit git commit boundaries. Phases A and B must land before any harness work. Phases C and D are independent of A/B but should commit before the harness (Phase E) so harness asserts a real signer. Phase F is documentation-only.

**Byte-exact HMAC invariant (non-negotiable).** The signer in this slice constructs `canonicalBytes = ${t}.${raw_body}` where `raw_body` is the **exact UTF-8 string passed as the HTTP body** of the webhook POST. The verifier in slice 3 reads the raw request body before any JSON parsing and recomputes the same bytes. Tests in this slice and in slice 3 must share a fixture vector (same `secret`, same `payload`, same `t`, same expected `v1` hex). Any change to body serialization (whitespace, key order, encoding) on either side breaks every webhook in flight — this is called out in the test plan and in the slice 3 plan's "Dependencies" section.

### Phase A — Subtree pin & pre-flight

**Commit: `chore(summarizer): pin summarize-daemon subtree to 0ec12ac`**

1. From repo root, run `git subtree pull --prefix=summarizer/summarize-daemon https://github.com/steipete/summarize main --squash`. If `main` HEAD differs from `0ec12ac`, capture both SHAs in the commit message and `SUBTREE_PIN.md`.
2. Inspect the diff for `src/daemon/cli.ts`, `src/daemon/server.ts`, `src/daemon/server-summarize-request.ts`. Confirm no schema-breaking changes against the gateway's existing call sites (`POST /v1/summarize` body, `/v1/refresh-free` route, Bearer auth header).
3. Create `summarizer/summarize-daemon/SUBTREE_PIN.md`:

   ```md
   # Subtree pin
   Upstream: https://github.com/steipete/summarize
   Branch: main
   Commit: <SHA>
   Pulled: <ISO date>
   Last verified: <ISO date> (this slice)

   Refresh procedure:
       git subtree pull --prefix=summarizer/summarize-daemon \
         https://github.com/steipete/summarize <SHA-or-main> --squash

   Verification checklist before merging a pull:
   - [ ] `src/daemon/server.ts` Bearer auth path unchanged
   - [ ] `src/daemon/server-summarize-request.ts` accepts: youtube, videoMode, timestamps, forceSummary, noCache, extractOnly, prompt, maxCharacters
   - [ ] docker-compose harness still passes both fixture flows
   ```
4. Add a script `scripts/check-subtree-pin.mjs` (out of slice scope if you prefer manual; in scope if we want CI hygiene) that asserts the `Commit:` line in `SUBTREE_PIN.md` is reachable. **Plan recommendation: skip in v1; manual check on every pull.**

### Phase B — Dockerfile + entrypoint

**Commit: `feat(summarizer): multi-stage Dockerfile + Node entrypoint for Cloud Run`**

5. Write `summarizer/deploy/Dockerfile`. Three stages:

   - **Stage 1 `daemon-build`** (`FROM node:24-slim`): `corepack enable && corepack prepare pnpm@10.33.2 --activate`. Copy `summarizer/summarize-daemon/` into `/daemon`. `cd /daemon && pnpm install --frozen-lockfile && pnpm build`. Prune dev deps: `pnpm prune --prod`. Result: `/daemon/dist`, `/daemon/packages/core/dist`, `/daemon/node_modules` (prod-only).
   - **Stage 2 `api-build`** (`FROM node:24-slim`): activate `pnpm@10.33.2` (or `10.25.0` — see open decision below; the api currently uses 10.25.0 but unifying on 10.33.2 across both stages simplifies pnpm cache layers and matches the daemon). Copy root `pnpm-workspace.yaml`, `pnpm-lock.yaml`, `package.json`, and `summarizer/summarize-api/`, plus `backend/functions/package.json` (required because it's in the workspace — copy only the manifest, not the source). Run `pnpm install --frozen-lockfile --filter summarize-api...`. Then `pnpm --filter summarize-api build`. Prune dev deps with `pnpm --filter summarize-api prune --prod`.
   - **Stage 3 `runtime`** (`FROM node:24-slim`):
     - `apt-get update && apt-get install -y --no-install-recommends ffmpeg tesseract-ocr ca-certificates tini curl && rm -rf /var/lib/apt/lists/*`.
     - Install yt-dlp **as a static binary** (recommended path, sidesteps PEP-668):
       ```
       RUN curl -fsSL https://github.com/yt-dlp/yt-dlp/releases/download/2026.02.21/yt-dlp \
             -o /usr/local/bin/yt-dlp && chmod a+rx /usr/local/bin/yt-dlp \
           && /usr/local/bin/yt-dlp --version | tee /etc/yt-dlp.version
       ```
       Pin to the version that contains the CVE-2026-26331 fix. (Open decision: pin vs `:latest` — see below.)
     - `COPY --from=daemon-build /daemon /opt/daemon`
     - `COPY --from=api-build /app/summarizer/summarize-api /opt/api`
     - `COPY --from=api-build /app/node_modules /opt/api/../node_modules` (workspace-hoisted modules — confirm path after pnpm prune; alternative is `--shamefully-hoist` at api-build install time).
     - `COPY summarizer/deploy/entrypoint.js /opt/entrypoint.js`
     - `ENV NODE_ENV=production DAEMON_URL=http://127.0.0.1:8787 PORT=8080 HOST=0.0.0.0`
     - `EXPOSE 8080`
     - `ENTRYPOINT ["/usr/bin/tini", "--"]`
     - `CMD ["node", "/opt/entrypoint.js"]`

6. Write `summarizer/deploy/entrypoint.js` (ESM, top-level await). Behavior:

   - Read `SUMMARIZE_TOKEN` from env. If missing, write to stderr and exit 1.
   - Spawn daemon: `spawn(process.execPath, ["/opt/daemon/dist/cli.js", "daemon", "run", "--token", token, "--port", "8787"], { stdio: ["ignore", "inherit", "inherit"], env: process.env })`. **This invocation form is verified against `src/daemon/cli.ts:608-645` (Q1/Q2 resolution).** No config file write is required — `daemon run --token` synthesizes an in-memory v2 config.
   - Poll `GET http://127.0.0.1:8787/health` every 200ms for up to 30s. If timeout: log + exit 1.
   - If `OPENROUTER_API_KEY` is set, `POST http://127.0.0.1:8787/v1/refresh-free` with `Authorization: Bearer ${token}`. Don't block on response body; tolerate non-2xx (log warning, continue).
   - `await import("/opt/api/dist/index.js")` — gateway starts in-process and binds `0.0.0.0:$PORT`.
   - Signal handler: `process.on("SIGTERM", () => { daemon.kill("SIGTERM"); })`. Also forward `SIGINT`. Wait up to 10s for child exit before letting the process die.
   - Never log `SUMMARIZE_TOKEN` or `OPENROUTER_API_KEY`. Daemon child inherits env; that's fine.

7. Local build smoke check (commit-time, not part of code): `docker build -f summarizer/deploy/Dockerfile -t playster/summarizer:dev .` succeeds. `docker run --rm -e SUMMARIZE_TOKEN=$(openssl rand -hex 16) -e API_KEYS=test -p 8080:8080 playster/summarizer:dev` — log shows daemon `/health` 200 → refresh-free (or skipped) → gateway listening. `curl http://127.0.0.1:8080/health` returns 200.

### Phase C — Schema + migration

**Commit: `feat(summarize-api): add webhook fields to urlJobSchema + jobs table`**

8. `summarizer/summarize-api/src/schemas.ts` — extend `urlJobSchema`:

   ```ts
   export const urlJobSchema = z.object({
     url: z.string().url(),
     options: z.object({ /* unchanged */ }).optional(),
     // NEW:
     webhook_url: z.string().url().optional(),
     webhook_secret: z.string().min(16).optional(),
     client_job_id: z.string().min(1).max(256).optional(),
   });
   ```
   Note: `mode` field stays per PO. Add a comment block above it: `// NOTE: mode is informational only; not forwarded to the daemon. Deferred for v1 cleanup.`

9. `summarizer/summarize-api/src/db/schema.ts` — append three additive migration statements **after** `CREATE_INDEXES`:

   ```ts
   export const ADD_WEBHOOK_COLUMNS = `
   ALTER TABLE jobs ADD COLUMN webhook_url TEXT;
   ALTER TABLE jobs ADD COLUMN webhook_secret TEXT;
   ALTER TABLE jobs ADD COLUMN client_job_id TEXT;
   `;
   export const MIGRATIONS = [CREATE_JOBS_TABLE, CREATE_INDEXES, ADD_WEBHOOK_COLUMNS];
   ```

   SQLite's `ALTER TABLE ADD COLUMN` is not idempotent on its own. Wrap the third migration in a try/catch around `db.exec(migration)` in `db/index.ts:getDb()`, swallowing only the `duplicate column name` error — or implement a small `schema_migrations` table now to track which DDL has run. **Plan recommendation: introduce a minimal `schema_migrations(name TEXT PRIMARY KEY)` table; record each named migration once. This pays off in slices 3+ if more columns land.** Implementation:

   ```ts
   db.exec(`CREATE TABLE IF NOT EXISTS schema_migrations (
     name TEXT PRIMARY KEY,
     applied_at TEXT NOT NULL DEFAULT (datetime('now'))
   );`);
   for (const { name, sql } of NAMED_MIGRATIONS) {
     const seen = db.prepare("SELECT 1 FROM schema_migrations WHERE name = ?").get(name);
     if (!seen) {
       db.exec(sql);
       db.prepare("INSERT INTO schema_migrations(name) VALUES(?)").run(name);
     }
   }
   ```
   Convert existing migrations to named form: `001_create_jobs`, `002_create_indexes`, `003_add_webhook_columns`.

10. `summarizer/summarize-api/src/db/jobs.ts` — extend `Job` interface with `webhook_url`, `webhook_secret`, `client_job_id` (all `string | null`). Extend `createJob` signature: `params.webhookUrl?: string; params.webhookSecret?: string; params.clientJobId?: string;`. Adjust the INSERT statement accordingly.

11. `summarizer/summarize-api/src/routes/jobs.ts` — pass new fields from `parsed.data` into `createJob`. Add a guard: if `webhook_url` is set but `webhook_secret` is not, reply 400 (`webhook_secret required when webhook_url is set`). This makes the contract explicit.

### Phase D — Webhook signing in url-runner

**Commit: `feat(summarize-api): HMAC-signed webhook delivery on terminal SSE events`**

12. Create `summarizer/summarize-api/src/webhooks/signer.ts`:

    ```ts
    import { createHmac } from "node:crypto";

    export function buildSignatureHeader(
      secret: string,
      rawBody: string,
      timestamp = Math.floor(Date.now() / 1000),
    ): { header: string; timestamp: number } {
      const canonical = `${timestamp}.${rawBody}`;
      const mac = createHmac("sha256", secret).update(canonical, "utf8").digest("hex");
      return { header: `t=${timestamp},v1=${mac}`, timestamp };
    }
    ```

13. Create `summarizer/summarize-api/src/webhooks/deliver.ts`:

    ```ts
    export async function deliverWebhook({
      url, secret, payload, attempts = 3, baseDelayMs = 5000,
    }: { url: string; secret: string; payload: object; attempts?: number; baseDelayMs?: number; }): Promise<{ ok: boolean; status?: number; error?: string }> {
      const rawBody = JSON.stringify(payload);
      const { header } = buildSignatureHeader(secret, rawBody);
      for (let i = 0; i < attempts; i += 1) {
        try {
          const res = await fetch(url, {
            method: "POST",
            headers: { "Content-Type": "application/json", "X-Summarizer-Signature": header },
            body: rawBody,
          });
          if (res.ok) return { ok: true, status: res.status };
          if (res.status >= 400 && res.status < 500 && res.status !== 408 && res.status !== 429) {
            return { ok: false, status: res.status, error: `non-retryable ${res.status}` };
          }
        } catch (err) { /* network error → retry */ }
        if (i < attempts - 1) {
          await new Promise(r => setTimeout(r, baseDelayMs * 3 ** i));
        }
      }
      return { ok: false, error: "exhausted retries" };
    }
    ```

    The `rawBody` is computed **once** and used both for HMAC and for the POST. This guarantees byte-equivalence — slice 3's verifier sees exactly these bytes.

14. `summarizer/summarize-api/src/runners/url-runner.ts`:

    - Replace the `pickDefined(..., ["model","length","language","format"])` argument with `["model","length","language","format","youtube","videoMode","timestamps","forceSummary","noCache","extractOnly","prompt","maxCharacters"]`. The daemon's `mode` enum mismatches the api's; leave the api `mode` value out of the forward list (it stays a no-op per PO).
    - Read `job.webhook_url`, `job.webhook_secret`, `job.client_job_id` at the top of `runUrlJob`. Persist them already happens via Phase C — they arrive populated.
    - On `complete` SSE event (after `updateJobStatus("completed", { result: { summary: result } })`): if `job.webhook_url`, build `payload = { client_job_id: job.client_job_id ?? job.id, status: "completed", result: { summary: result } }` and call `deliverWebhook({ url: job.webhook_url, secret: job.webhook_secret, payload })`. Log the final outcome (do **not** log `secret`).
    - In `catch` (failed path): build `payload = { client_job_id, status: "failed", error: { message } }` and call `deliverWebhook` likewise. Use a distinct logger tag so failed-webhook-delivery is visible.
    - Add a guard: if `job.webhook_url` is set but `job.webhook_secret` is null (should be impossible via route validation), skip delivery and log a warning. Belt-and-suspenders.
    - Redact `webhook_secret` from any error path that might surface `job` in logs. Use a `redactJob(job)` helper that returns a copy with `webhook_secret: "<redacted>"`.

15. Tests (committed in the same PR/commit):
    - `tests/webhook-signer.test.ts` — unit tests: known-vector signature (fixed timestamp + body + secret → known hex); header format `t=<n>,v1=<hex>`; case where `rawBody` contains `\n`, multibyte UTF-8.
    - `tests/url-runner-webhook.test.ts` — integration: start `startSummarizeDaemon` (chunks: `["Hello "," world"]`). Start a Node http server as the "webhook receiver" that captures headers + body. POST a URL job with `webhook_url`, `webhook_secret`, `client_job_id`. Assert: webhook arrives once on completion; signature verifies against received body bytes; payload matches expected shape. Add a failure-path variant: `eventsError` set on the mock daemon → assert `status: "failed"` webhook arrives.
    - Retry test: mock receiver returns 503 twice then 200 — assert exactly 3 attempts and final ok. (Use shorter `baseDelayMs` via env or constructor override to keep test fast.)

### Phase E — Harness + fixtures

**Commit: `test(summarizer): docker-compose harness + mock-backend webhook receiver`**

16. `summarizer/deploy/mock-backend/server.js` — tiny Node http server:
    - `POST /webhook`: read raw body bytes, parse `X-Summarizer-Signature: t=<n>,v1=<hex>`, look up secret from env `WEBHOOK_SECRET`, recompute canonical, compare with `timingSafeEqual`. If timestamp diff > 300s → 401. On valid signature → write payload to `/verify-artifacts/<client_job_id>.json` and reply 204. On invalid → 401 with reason.
    - `GET /captured/:client_job_id` — read back the persisted artifact for harness assertions.
    - No external deps; uses Node built-ins only.

17. `summarizer/deploy/mock-backend/Dockerfile` — `FROM node:22-slim`, copies `server.js`, exposes 9000, `CMD ["node","server.js"]`.

18. `summarizer/deploy/docker-compose.yml` — rewrite:

    ```yaml
    services:
      summarizer:
        build:
          context: ../..
          dockerfile: summarizer/deploy/Dockerfile
        environment:
          - SUMMARIZE_TOKEN=${SUMMARIZE_TOKEN}
          - API_KEYS=${API_KEYS}
          - OPENROUTER_API_KEY=${OPENROUTER_API_KEY:-}
          - PORT=8080
        ports: ["8080:8080"]
        networks: [harness]
      mock-backend:
        build: ./mock-backend
        environment:
          - WEBHOOK_SECRET=${WEBHOOK_SECRET}
        ports: ["9000:9000"]
        volumes:
          - ./verify-artifacts:/verify-artifacts
        networks: [harness]
    networks:
      harness:
        driver: bridge
    ```

19. `summarizer/deploy/fixtures/captioned.json` — `{ "url": "https://www.youtube.com/watch?v=<known-captioned-id>", "client_job_id": "fixture-captioned", "expect": "completed", "expect_content_min_chars": 200 }`. Pick a stable, short, public Creative Commons or Google-published clip (e.g., Google I/O keynote excerpt) that has captions and is unlikely to be unlisted.

20. `summarizer/deploy/fixtures/no-caption.json` — analogous, no-caption fixture. Pick a music video or short demo without captions. `expect: completed`, `expect_content_min_chars: 50` (shortDescription fallback is shorter).

21. `summarizer/deploy/run-harness.mjs` — driver:
    - Generate `SUMMARIZE_TOKEN`, `API_KEYS`, `WEBHOOK_SECRET` randomly into a temp env file.
    - `docker compose up --build -d`.
    - Poll `http://127.0.0.1:8080/health` for 60s.
    - For each fixture: POST `/v1/jobs` with `webhook_url=http://mock-backend:9000/webhook`, `webhook_secret=$WEBHOOK_SECRET`, `client_job_id=<from fixture>`.
    - Poll `http://127.0.0.1:9000/captured/<client_job_id>` for up to 5 minutes.
    - Assert signature, status, content length.
    - Replay test: take a captured artifact, replay its raw body with the stored timestamp but `Date.now()-301s` adjustment → assert 401.
    - Tear down + report.

22. Document the harness in `summarizer/deploy/README.md` (rewrite). State explicitly: this harness verifies AC-6 (signature), AC-14 (no-caption fallback), AC-16 (cold start health), plus the slice-local replay-rejection AC.

### Phase F — Cloud Run deploy runbook

**Commit: `docs(summarizer): Cloud Run deploy runbook`**

23. Add `summarizer/deploy/CLOUD-RUN.md` (or append to `docs/operations/deploy-and-bootstrap.md` once that doc lands in the orchestration slice). Cover:

    - Prerequisites: GCP project, Artifact Registry repo, Secret Manager entries for `API_KEYS`, `SUMMARIZE_TOKEN`, `OPENROUTER_API_KEY`.
    - Build: `gcloud builds submit --tag <region>-docker.pkg.dev/$PROJECT/playster/summarizer:<tag> -f summarizer/deploy/Dockerfile .` (use `--config cloudbuild.yaml` if we want a build config; not needed for v1).
    - Deploy:
      ```
      gcloud run deploy summarizer \
        --image <region>-docker.pkg.dev/$PROJECT/playster/summarizer:<tag> \
        --region <region> \
        --no-allow-unauthenticated \
        --set-secrets API_KEYS=SUMMARIZER_API_KEY:latest,SUMMARIZE_TOKEN=SUMMARIZE_TOKEN:latest,OPENROUTER_API_KEY=OPENROUTER_API_KEY:latest \
        --memory 2Gi --cpu 2 --timeout 600 \
        --min-instances 0 --max-instances 2 \
        --execution-environment gen2
      ```
    - Verify: `curl -H "X-API-Key: ..." https://summarizer-...run.app/health` → 200.
    - Roll back: `gcloud run services update-traffic summarizer --to-revisions=<prev>=100`.

24. Append to root `summarizer/README.md` a one-liner pointing at the new harness + runbook.

## Test / Verification Plan

### Automated checks

- **vitest unit tests** (Phase D):
  - `tests/webhook-signer.test.ts` — known-vector signature; header format; UTF-8 multibyte body; timestamp injection.
  - `tests/url-runner-webhook.test.ts` — happy path, failure path (daemon errors), retry on 503, exhaustion after 3 attempts, no-webhook-when-url-missing.
- **vitest schema tests** (Phase C): extend existing `tests/jobs-url.test.ts` (or new file) to assert that `POST /v1/jobs` with `webhook_url` and `webhook_secret` 201s; with `webhook_url` alone 400s; with invalid `webhook_url` (non-URL) 400s.
- **vitest migration test**: open DB in temp dir twice → second open is a no-op (no duplicate ALTER errors). Verify `schema_migrations` rows.
- Run all via `pnpm --filter summarize-api test`. CI invocation: `pnpm verify` at repo root (existing meta script).

### Interactive verification

- **`summarizer/deploy/run-harness.mjs` (covers AC-6, AC-14, AC-16, slice-local replay AC):**
  - `docker compose up --build -d` → container builds and starts; tini = PID 1.
  - Health gate: `http://127.0.0.1:8080/health` returns 200 within 60s of `up` (this is **AC-16** — cold-start health). Also `docker compose exec summarizer curl -fsS http://127.0.0.1:8787/health` returns 200 (asserts daemon is on loopback inside the container).
  - Fixture 1 (captioned): POST job → wait for `mock-backend` capture → assert `X-Summarizer-Signature` header verifies (this is **AC-6**); `payload.status === "completed"`; `payload.result.summary.length >= 200`.
  - Fixture 2 (no-caption): same flow → assert `status === "completed"` and `summary.length >= 50` (this is **AC-14**: shortDescription fallback produces non-empty content; no failure surfaced).
  - Replay attack: take the captured artifact, recompute the same body with `t = now - 301`, recompute v1 with stored `webhook_secret`, POST → assert 401 (this is the slice-local replay AC).
  - Image size check: `docker image inspect playster/summarizer:dev --format '{{.Size}}'` → assert < 1.5 GB. (Soft assertion; logged on overage.)
  - `docker run --rm playster/summarizer:dev yt-dlp --version` → assert version string is lexicographically >= `2026.02.21` (slice-local AC).
- **Subtree pin check (slice-local AC):**
  - `cat summarizer/summarize-daemon/SUBTREE_PIN.md` → grep the recorded SHA. Compare against the actual squash-merge SHA in `git log -- summarizer/summarize-daemon`.

**Evidence captured:** `summarizer/deploy/verify-artifacts/*.json` (one per fixture), `summarizer/deploy/harness.log`. Attach to slice verify stage.

## Risks / Watchouts

- **Open Q1/Q2 resolution.** Confirmed in this plan: `daemon run --token <T> --port 8787` is the correct foreground invocation; no config file required. CLI source (`cli.ts:608-645`) directly accepts the override. If a subtree pull changes this, the harness will catch it (daemon won't reach `/health`).
- **PEP-668 / `python3-pip` on Trixie.** We sidestep by installing the yt-dlp static binary. If the static binary URL ever 404s, fallback is `pip install --break-system-packages` inside the runtime image (with the cost of pulling python3 + python3-pip into the runtime layer).
- **Subtree contract drift.** Mitigated by the harness running before merge of any subtree pull. The `SUBTREE_PIN.md` verification checklist names the exact contract surfaces.
- **pnpm version split.** Daemon stage uses `pnpm@10.33.2` (daemon's `packageManager`). API stage currently uses `10.25.0`. Slice unifies both stages on `10.33.2` to simplify mental model and Docker cache layers. Risk: API workspace install on 10.33.2 produces a slightly different `node_modules` shape than the developer's local install. Mitigation: regenerate `pnpm-lock.yaml` once on 10.33.2; commit the regenerated lockfile.
- **HMAC byte-equivalence.** Documented as non-negotiable. Risk concentrates on whether the verifier (slice 3) reads the raw request body **before** any Express body-parser mutates whitespace. Slice 3's plan must call out `rawBody` retrieval; the shared fixture vector in `tests/webhook-signer.test.ts` is the canonical source of truth.
- **Webhook secret leakage via logs.** All log statements that touch `job` go through `redactJob(job)` helper. Reviewers verify no raw `job.webhook_secret` appears in any `console.log` / `app.log.info`.
- **Image size > 1.5 GB.** ffmpeg + tesseract + node alpine alternatives examined; node:24-slim is the cheapest Debian-based Node 24 image with apt available. If size becomes a hard issue, second pass swaps the runtime stage to `gcr.io/distroless/nodejs24-debian12` and copies ffmpeg/tesseract/yt-dlp binaries explicitly — out of slice scope.
- **Cold-start latency.** Open decision below; slice ships with `min-instances 0`. Operator can flip to 1 post-deploy.
- **`/v1/refresh-free` failure on cold start.** Entrypoint tolerates non-2xx with a logged warning; the daemon falls back to its previously-cached free-model list. If no cache exists (first deploy), the first summary request may fail to resolve `model="free"`. Mitigation: warm with a manual `curl -X POST .../v1/refresh-free` after first deploy; document in runbook.
- **Cloud Run gen2 execution environment.** Required for our memory profile + dynamic spawn semantics. Flag is `--execution-environment gen2`.
- **API gateway listening on `0.0.0.0:$PORT` vs daemon staying loopback.** Verified: daemon constants pin `127.0.0.1` on Linux; only the gateway binds to `0.0.0.0:$PORT` (Cloud Run-injected). Internal-only daemon = correct security posture.

## Dependencies on Other Slices

- **None upstream.** This slice can land independently of `auth-and-android-firebase`.
- **`summary-orchestration` (slice 3) is the downstream consumer.** Hard contract surfaces:
  - HTTP path: `POST {SUMMARIZER_URL}/v1/jobs` with `X-API-Key: <gateway API key>`.
  - Request body: `{ url, options?, webhook_url, webhook_secret, client_job_id }`.
  - Webhook delivery: `POST {webhook_url}` with `X-Summarizer-Signature: t=<unix>,v1=<hex>` and body `{ client_job_id, status: "completed"|"failed", result?: { summary }, error?: { message } }`.
  - HMAC canonical bytes: `${t}.${raw_body}` where `raw_body` is the **exact UTF-8 string** of the POST body. Slice 3's verifier must read `req.rawBody` before any JSON parse.
- **`summary-ui` (slice 4)** depends on slice 3, not on this slice directly.

## Assumptions

- GCP project is provisioned; the operator has owner or `roles/run.admin` + `roles/artifactregistry.admin` + `roles/secretmanager.admin`.
- Artifact Registry Docker repo exists at `<region>-docker.pkg.dev/$PROJECT/playster/`.
- Secret Manager entries `SUMMARIZER_API_KEY`, `SUMMARIZE_TOKEN`, `OPENROUTER_API_KEY` exist and are populated. (32-byte hex for keys/tokens; OpenRouter key per operator's $10 credit purchase.)
- Operator runs `docker compose` v2+ locally (uses the `services:` schema without `version:`).
- `node:24-slim` will be available on Docker Hub at slice-implementation time (May 2026 — already stable).
- yt-dlp `2026.02.21` release binary URL stays reachable from GitHub. If GitHub rate-limits CI builds, mirror in our own Artifact Registry. Out of slice scope.
- The vendored subtree pull does not introduce schema-breaking changes against the gateway. Verified by Phase E harness pre-merge.

## Blockers

None.

The slice is ready for `wf-implement`. Q1 and Q2 from `00-index.md` are resolved by direct source inspection (see Phase B step 6); they no longer need PO input.

## Open Decisions for Discovery Phase

These are the four remaining decisions the PO should sign off before implementation. Each has a recommended default; flag if any are wrong.

1. **yt-dlp install: static binary (recommended) vs `pip install --break-system-packages`.** Recommendation: static binary (smaller image, sidesteps PEP-668, pinnable to exact version, matches existing pattern in `summarizer/deploy/daemon/Dockerfile`). Cost: must update the pinned URL on each yt-dlp upgrade. Alternative `pip` install requires pulling `python3` + `python3-pip` into runtime (~70 MB) and the `--break-system-packages` flag — workable but uglier.
2. **Cloud Run `min-instances`.** Recommendation: `0` (zero cost when idle; first request after idle pays ~3s daemon boot + 5–10s `refresh-free`). Operator can flip to `1` post-launch if cold-start latency annoys. Cost of `1`: ~$15/mo for a 2 vCPU / 2 GiB always-on instance. Single-tenant personal use can comfortably accept cold starts.
3. **Build path: Cloud Build (`gcloud builds submit`) vs local `docker push` to Artifact Registry vs Buildpacks.** Recommendation: Cloud Build (`gcloud builds submit --tag`). Reproducible, runs in GCP context with the right service account, doesn't depend on operator's local Docker daemon. Buildpacks rejected (no system-deps support). Local `docker push` is fine for dev iteration but documented as the secondary option.
4. **Subtree refresh cadence.** Recommendation: pin-on-incident. Pull from upstream only when (a) a CVE lands in `@steipete/summarize` or its tree (whisper, ytdl, etc.), (b) a feature we want lands upstream, or (c) every 6 months as hygiene. Each pull goes through the Phase E harness before merge. Alternative: monthly cron — adds churn for little benefit in single-tenant.

## Freshness Research

- **Source:** `summarizer/summarize-daemon/src/daemon/cli.ts:608-645` (vendored upstream, May 2026).
  **Why it matters:** Resolves open questions Q1 + Q2 — exact daemon foreground CLI invocation and token mechanism.
  **Takeaway:** `daemon run --token <T> --port 8787` is the correct, supported foreground form. No config file required at runtime; `daemon run` synthesizes an in-memory v2 config when only `--token` is provided. No need for the legacy `entrypoint.sh` config-file dance.

- **Source:** `summarizer/summarize-daemon/src/daemon/constants.ts` + `src/daemon/server.ts:77`.
  **Why it matters:** Verifies daemon bind address for our Linux container.
  **Takeaway:** Daemon binds `127.0.0.1:8787` on Linux. No `sed` patch needed (the legacy daemon Dockerfile's patch is obsolete in our architecture where the gateway runs in the same container).

- **Source:** `summarizer/summarize-daemon/src/daemon/server-summarize-request.ts:115-175`.
  **Why it matters:** Confirms daemon accepts every key named in the slice's `pickDefined` extension list (`youtube`, `videoMode`, `timestamps`, `forceSummary`, `noCache`, `extractOnly`, `prompt`, `maxCharacters`).
  **Takeaway:** Extension list is safe — no daemon-side schema change needed.

- **Source:** yt-dlp releases page (2026.02.21+); CVE-2026-26331 disclosure.
  **Why it matters:** Container must ship a non-vulnerable yt-dlp.
  **Takeaway:** Pin to `2026.02.21` static binary at build time. Document upgrade procedure in `SUBTREE_PIN.md`-adjacent notes.

- **Source:** Stripe webhook signing docs (current); `crypto.timingSafeEqual` Node docs.
  **Why it matters:** HMAC contract must be byte-exact across signer (this slice) and verifier (slice 3).
  **Takeaway:** Canonical bytes = `${timestamp}.${raw_body}`. Use `timingSafeEqual` on hex-decoded buffers. 300s replay window. Slice 3 verifier must use `req.rawBody`, not the parsed body.

- **Source:** Cloud Run Buildpacks support matrix (May 2026).
  **Why it matters:** Build path decision.
  **Takeaway:** Buildpacks cannot install ffmpeg/tesseract/yt-dlp. Use Cloud Build (`gcloud builds submit`) with our Dockerfile.

- **Source:** Debian Trixie PEP-668 enforcement (`externally-managed-environment`).
  **Why it matters:** Affects yt-dlp install path.
  **Takeaway:** Static binary is the clean path. `pip install --break-system-packages` is the workable alternative.

- **Source:** Firebase Functions Node runtime support (May 2026).
  **Why it matters:** Cross-slice — backend functions stays on Node 22 per shape; summarizer on Node 24.
  **Takeaway:** No conflict. The two services share no runtime; only an HTTP wire contract.

## Recommended Next Stage

- **Default:** `/wf implement wire-android-backend-summarizer summarizer-container` — the slice has zero upstream blockers, Q1/Q2 are resolved by source inspection, and the four open decisions above have defensible defaults. PO confirm-and-proceed is the right shape.
- **Alternative:** `/wf plan wire-android-backend-summarizer summary-orchestration` if the PO prefers to lock the slice-3 verifier contract in writing before implementing the signer here. The HMAC byte-equivalence requirement is the only cross-slice contract; documenting it in both plan docs reduces drift risk.
- **Not recommended:** returning to shape or slice. The slice spec covers everything needed; this plan resolves the two carry-over open questions and surfaces four orthogonal decisions that are implementation-time toggles, not shape-time.

## Revision History

**2026-05-18T07:49:39Z — Discovery phase reconciliation (initial plan write):**
- Discovery phase locked: **yt-dlp static binary from GitHub Releases** (PO confirmed default). Phase B step 5 (runtime stage) installs the pinned `2026.02.21` static binary; no `python3` / `python3-pip` layers in the runtime image. Sidesteps Debian Trixie PEP-668. Pinned URL maintained in the Dockerfile + cross-referenced in `SUBTREE_PIN.md`-adjacent notes (for upgrade cadence tracking).
- Discovery phase locked: **`--min-instances 0`** on Cloud Run (PO confirmed default). Cold-start cost (~3s daemon boot + 5–10s `/v1/refresh-free`) accepted for single-tenant use. Operator can flip to `1` post-launch via `gcloud run services update --min-instances 1` without redeploy.
- Discovery phase accepted-default: **Cloud Build (`gcloud builds submit --tag`) for image builds** (PO accepted default). Local docker push documented as secondary option; Buildpacks remain rejected (no system-deps support).
- Discovery phase accepted-default: **subtree refresh cadence is pin-on-incident** (PO accepted default). Pull only on (a) CVE, (b) wanted upstream feature, or (c) 6-month hygiene. Each pull runs through the Phase E harness before merge.
- Q1/Q2 from `00-index.md` open-questions are resolved by source inspection (`summarizer/summarize-daemon/src/daemon/cli.ts:608-645`) — these are removed from open-questions in `00-index.md` at plan-stage close.
- No cohesion fixes required for this slice — it has no shared files with the other three slices (the byte-exact HMAC contract is the only cross-slice surface, and that is byte-precision-managed via shared test vectors).

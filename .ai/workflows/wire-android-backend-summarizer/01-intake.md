---
schema: sdlc/v1
type: intake
slug: wire-android-backend-summarizer
status: complete
stage-number: 1
created-at: "2026-05-17T15:00:08Z"
updated-at: "2026-05-17T15:00:08Z"
tags: [android, firebase, cloud-run, summarizer, openrouter, single-tenant, multi-component]
refs:
  index: 00-index.md
  next: 02-shape.md
  source-doc: ../../../docs/internal/2026-05-17-wire-android-backend-summarizer.md
next-command: wf-shape
next-invocation: "/wf shape wire-android-backend-summarizer"
---

# Intake

## Restated Request

Wire the three projects in this monorepo — the Android Compose app, the Firebase Cloud Functions backend, and the summarizer service (Fastify API gateway + vendored steipete/summarize daemon) — into a single end-to-end pipeline for personal YouTube content management. Android becomes a Firestore-backed live view, backend owns all YouTube auth and now dispatches per-video summary jobs to the summarizer, and the summarizer (deployed as a single Cloud Run container running both processes) returns LLM-generated summaries via webhook. Use OpenRouter free models to keep LLM cost at zero. Operate as single-tenant: one allowlisted Firebase Auth uid, singleton Firestore documents, no per-user keying.

A detailed planning document already exists at `docs/internal/2026-05-17-wire-android-backend-summarizer.md` (gitignored). It is the source of truth for proposed architecture, phasing, code sketches, and tradeoffs studied during plan revision. This intake formalises that doc into the SDLC workflow control surface — it does not replace it.

## Intended Outcome

The operator (sole user, signed in with a single Google account) opens the Android app, sees Firestore-synced playlists and videos, taps "Summarize" on a video, and a markdown summary appears live as the summarizer completes. Behind the scenes: backend verifies the Firebase Auth allowlist, runs OpenRouter quota pre-flight, dispatches a job to the Cloud Run summarizer with a webhook callback, persists the result to `summaries/{videoId}`. Android observes that Firestore document and renders updates in real time. No API keys ever leave the backend; the Android device never holds a YouTube credential after Phase 2.

## Primary User / Actor

Single operator (`ALLOWED_UID`) — the user's own Google account, allowlisted in backend config and Firestore rules. No other users in the system. Cloud Scheduler also acts as a non-human actor for periodic playlist sync (existing behaviour, unchanged).

## Known Constraints

- **Single-tenant** by explicit choice. Singleton Firestore paths (`tokens/youtube`, `tokens/innertube-oauth`, `playlists/{id}`, `videos/{id}`, `summaries/{id}`, `quota/openrouter`). Migration to multi-tenant would require moving everything under `users/{uid}/...` later.
- **LLM cost ceiling = $0.** OpenRouter free-tier models only (`model: "free"` rotating list, or specific `*:free` IDs). Daily and per-minute caps tracked operator-wide in `quota/openrouter`.
- **Android never speaks to the summarizer.** API key stays server-side; all summary dispatch goes through `requestVideoSummary` callable.
- **Backend never holds SSE connections.** Communication with summarizer is fire-and-forget over HTTP; completion arrives via webhook (HMAC-SHA256 signed with per-summary secret).
- **Summarize daemon vendored as `git subtree --squash`** from `github.com/steipete/summarize` at `summarizer/summarize-daemon/`. Refresh manually via `git subtree pull --squash`; pin to known-good commits.
- **Single Docker container** hosts both the public-facing summarize-api gateway (`0.0.0.0:$PORT`) and the daemon (`127.0.0.1:8787`). Daemon staying localhost-only is treated as a security feature, not a limitation.
- **Node version split:** backend functions on Node 22 (Firebase Functions runtime); summarizer container on Node 24+ (daemon requirement). No shared runtime.
- **Cloud Run filesystem is ephemeral.** Cache and slide artifacts vanish on cold start — accepted in MVP.
- **Already-decided technical choices** (from planning doc, ratified by PO during intake): webhook over SSE, singleton Firestore docs, drop `mode` field from summarize-api schema and rely on daemon URL auto-detection, default `model: "free"`, HMAC body signing.

## Assumptions

- The PO already has a Firebase project provisioned with Firestore and Functions enabled, plus Google OAuth client credentials for the existing backend auth flow.
- Google Cloud project (for Cloud Run) is either the same Firebase project or accessible via the same `gcloud` CLI context.
- `OPENROUTER_API_KEY` will be provisioned by the PO at deployment time; obtaining it is out of scope for the workflow.
- System dependencies in the summarizer container image are reachable from Debian apt + pip (`ffmpeg`, `yt-dlp`, `tesseract-ocr`); no special build is required.
- Existing backend cron `scheduledSync` continues to operate without auth context — it stays as-is; only HTTPS-triggered handlers are converted to callable + allowlisted.
- Maestro is reachable from this environment for Android end-to-end acceptance flows; lazylogcat is available for log capture during verification.

## Product Owner Questions Asked

- Branch strategy, appetite, review scope (Batch A — structured).
- Stack fingerprint confirmation, outcome confirmation, success-criteria confirmation, non-goals confirmation, constraints confirmation, open-questions confirmation (Batch B — freeform).

## Product Owner Answers

See `po-answers.md` for full text. Summary:

- Branch: dedicated, `feat/wire-android-backend-summarizer` off `main`.
- Appetite: large.
- Review scope: slug-wide.
- Stack: detected stack accepted; PO added `android`, `gcloud`, `firebase`, `lazylogcat`, `maestro` CLIs as available session tooling.
- Outcome: confirmed.
- Phase-by-phase success criteria: PO declined to lock them in at intake — treat as draft, refine in shape.
- Non-goals: confirmed (multi-tenant, slides MVP, cache persistence, legacy `setCookies` cleanup deferred to Phase 6, no backend SSE).
- Constraints: confirmed as listed in planning doc; no changes.
- Open questions: 4 carried forward (daemon CLI invocation, token mechanism, cold-start tolerance, subtree pull cadence).

## Unknowns / Open Questions

These move into `00-index.md`'s `open-questions` and should be resolved in shape or plan:

1. **Daemon foreground CLI in non-installed mode.** The vendored daemon normally registers a launchd/systemd/Scheduled-Task service; running it as a plain foreground process inside the container needs verification. Likely `summarize daemon run --token <T>` but read `summarizer/summarize-daemon/src/daemon/cli.ts` first.
2. **Token-passing mechanism.** Daemon supports paired tokens via `~/.summarize/daemon.json`. Confirm `--token` flag (or `SUMMARIZE_TOKEN` env) is the right knob so we never write the secret to disk inside the container.
3. **Cloud Run cold-start tolerance.** First request after idle incurs daemon boot (~3s) + `refresh-free` benchmark sweep (~5–10s) + summarization latency. Decide MVP posture: accept the latency or pre-warm via `min-instances=1` (~$/month cost).
4. **Subtree maintenance.** Cadence for `git subtree pull` from upstream; pin policy for known-good commits; smoke-test surface (daemon HTTP routes + request schema) to gate upgrades.

## Dependencies / External Factors

- **External services:** Firebase Auth, Firestore, Cloud Functions, Cloud Run, Cloud Scheduler, Secret Manager, Google OAuth 2.0, OpenRouter API. Optionally Groq / AssemblyAI / Gemini / OpenAI / FAL as transcription fallbacks (daemon supports any one).
- **Upstream code:** `github.com/steipete/summarize` (vendored as subtree). HTTP route surface and request schema in `src/daemon/server.ts` and `src/daemon/server-summarize-request.ts` are the contract.
- **Build/runtime tooling:** Node 22 (backend), Node 24+ (summarizer container), pnpm@9 (root workspace), pnpm@10.33.2 (daemon's own workspace), Docker, gradle/AGP (Android), Firebase CLI, gcloud CLI.
- **Existing data:** `tokens/youtube` and `tokens/innertube-oauth` singletons must survive Phase 1's auth lockdown — they're populated by existing OAuth flows.
- **No third-party billing in MVP** beyond Firebase Spark and Cloud Run free tier; OpenRouter free models hold inference cost at zero.

## Risks if Misunderstood

- **Hardcoding the wrong uid in Firestore rules** would lock the PO out of their own Firestore. Bootstrap flow (sign in first, capture uid, then update rules+config) must be sequenced explicitly in Phase 1.
- **Schema mismatch between summarize-api `mode` and daemon `mode`** silently drops fields today; if we extend `pickDefined` without remapping, requests will fail at the daemon. Plan calls for dropping the field; verify in implementation.
- **Webhook signature verification regression** would let any leaked URL forge summary results into Firestore. HMAC-SHA256 with per-summary secret is the contract — test must cover bad-signature rejection explicitly.
- **OpenRouter free-model rate-limit surprise.** Per-minute caps (~20 RPM) plus daily caps (~200 RPD) per model — the sliding-window quota check has to be transactional or two concurrent requests can both pass and then both fail at the provider.
- **Cloud Run cold-start latency** on the first summary after idle could read as "the app is broken" if Android UI doesn't communicate `pending`/`running` state clearly.
- **Subtree drift.** Upstream `steipete/summarize` API surface could change between pulls; missing the contract check could break our gateway silently.
- **Container memory floor.** `ffmpeg` + `yt-dlp` on long videos can blow past Cloud Run's default 512Mi — the plan calls for 2Gi, but verifying it's enough for the longest videos the PO cares about needs a real test.

## Success Criteria

PO declined to commit to phase-by-phase acceptance at intake; the planning doc's per-phase acceptance lines are draft material to be refined in shape. Workflow-level success at MVP completion (Phases 1–6, not 7):

- Operator can sign in on Android and see their playlists/videos from Firestore with no direct YouTube Data API call from the device.
- Operator can tap "Summarize" on a video and observe a Firestore-driven status transition (pending → running → completed) culminating in a rendered markdown summary, with no API key shipped to the device.
- Backend rejects any callable/HTTP request that isn't from the allowlisted uid.
- LLM inference cost stays at $0 under normal use; quota exhaustion surfaces as a clear UX state, not a silent failure.
- Webhook tampering attempts (bad signature, unknown `client_job_id`) are rejected.

Detailed acceptance per phase will be authored in shape.

## Out of Scope for Now

- Multi-tenant data model (per-user Firestore paths).
- Slides extraction feature (timestamped scene screenshots + OCR) — deferred to Phase 7 stretch.
- Persisting daemon cache across Cloud Run cold starts (no GCS volume mount in MVP).
- Removing legacy `setCookies` HTTP endpoint (Phase 6 cleanup).
- Holding long-lived SSE connections in backend — backend integrates via webhook only.
- Migration from Firebase Spark to Blaze, or any billing-tier change.
- Background summary pre-fetching / auto-summarize on sync.
- A web frontend.

## Freshness Research

Skipped at intake. The planning doc already incorporates inspection of the daemon source (HTTP routes, request schema, YouTube cascade, OpenRouter integration). External research that *would* be valuable in shape:

- **OpenRouter free-tier limits as of 2026-05.** Verify current per-minute and per-day caps for the free models the daemon's `refresh-free` flow picks; the doc's `~20 RPM / 200 RPD` numbers are estimates.
- **Cloud Run pricing for 2 vCPU / 2Gi / min-instances=1.** Decide whether the cold-start fix is acceptable.
- **Firebase Functions v2 cold-start posture.** Confirm `onCall` cold-start tail for the summary dispatch path is tolerable.
- **steipete/summarize upstream commit** to pin against. Capture the SHA at intake time as the baseline.

Move these into stage 2 (shape) freshness research; defer here to avoid pre-decision.

## Recommended Next Stage

- **Option A (default):** `/wf shape wire-android-backend-summarizer` — A 7-phase, multi-component, multi-runtime initiative with non-trivial cross-cutting acceptance is the textbook case for shape. Phase-by-phase acceptance was explicitly deferred from intake; shape is where it gets nailed down, where the 4 open questions get researched, and where the slug-wide review surface gets defined.
- **Option B:** `/wf plan wire-android-backend-summarizer` — Not recommended. Skipping shape would force the implementation plan to embed product decisions (e.g. UX state on quota exhaustion, retry semantics, which phase ships first) that the PO hasn't yet ratified at the spec level.
- **Option C:** Blocked — none. All required PO answers are captured; no `awaiting-input` on intake.

---

*Tip: `.ai/workflows/INDEX.md` does not yet exist in this repo. Run `/wf-meta sync` once to bootstrap the registry — future intakes will gain collision detection against the registry.*

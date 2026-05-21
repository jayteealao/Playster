---
schema: sdlc/v1
type: shape
slug: wire-android-backend-summarizer
status: complete
stage-number: 2
created-at: "2026-05-17T18:32:21Z"
updated-at: "2026-05-17T18:32:21Z"
docs-needed: true
docs-types: [reference, how-to, readme]
tags: [android, firebase, cloud-run, summarizer, openrouter, single-tenant, multi-component, auto-summarize, webhook]
refs:
  index: 00-index.md
  intake: 01-intake.md
  next: 03-slice.md
  source-doc: ../../../docs/internal/2026-05-17-wire-android-backend-summarizer.md
next-command: wf-slice
next-invocation: "/wf slice wire-android-backend-summarizer"
---

# Shape

## Problem Statement

Three projects in the Playster monorepo (Android Compose app, Firebase Functions backend, summarizer service comprising a Fastify gateway + vendored steipete/summarize daemon) currently have zero runtime coupling. The Android app talks to the YouTube Data API directly; the backend sync runs on unauthenticated HTTP endpoints; the summarizer is undeployed and has never been called by anyone. The operator cannot manage their personal YouTube library or get LLM summaries of videos from a single app.

We need to wire the three together into one pipeline so that the operator can: sign in once on Android, see their playlists/videos rendered live from Firestore, tap into a video, and read a generated summary — with all API keys held server-side and zero LLM cost at steady state. The system must be locked to a single operator, run on free/no-cost infrastructure tiers (with one $10 OpenRouter credit purchase as a documented prerequisite), and survive Cloud Run cold starts plus YouTube/OpenRouter rate limits without operator intervention.

## Primary Actor / User

Single operator (`ALLOWED_UID`) — the project owner's own Google account. Allowlisted by uid via Firebase Auth at the backend and by hardcoded uid string in Firestore security rules. No other human actors. Cloud Scheduler acts as a non-human actor for periodic sync (every 6h) and for the new cron functions introduced by this shape (daily summary retry; hourly stuck-job sweep; auto-summary dispatch on sync completion).

## Desired Behavior

### Sign-in & view
1. Operator opens the app, taps Google Sign-In. Auth bridges to Firebase Auth via `GoogleAuthProvider.getCredential`. On first sign-in their uid is captured into `ALLOWED_UID` config + Firestore rules string.
2. Once signed in, the playlist screen is a live Firestore listener on `playlists/{playlistId}`. Each tile renders `PlaylistDocument`. No YouTube Data API calls happen on the device.
3. Tapping a playlist tile opens the playlist's video list (Firestore listener on `videos/{videoId}` filtered by playlist). Each row is a video tile.
4. Tapping a video tile opens **VideoDetailScreen** (new in v1). The screen has tabs; one tab is **Summary** (the new SummaryScreen).

### Manual summarize
5. Inside the Summary tab (or via a Summarize affordance on the tile itself), the operator triggers `requestVideoSummary(videoId)` — a Firebase callable. The screen immediately renders the in-progress state and starts a Firestore listener on `summaries/{videoId}`.
6. Backend: verifies allowlisted uid, runs OpenRouter quota pre-flight, checks for existing non-failed summary (returns it if present), reserves a `summaries/{videoId}` doc with status=pending, dispatches a job to the Cloud Run summarizer with `client_job_id=videoId` and a per-summary HMAC secret + webhook URL, then updates the doc to status=running with the summarizer's session id.
7. Summarizer (gateway → daemon): daemon does its native YouTube cascade (`youtubei` → `captionTracks` → `yt-dlp` → `videoDetails.shortDescription` fallback) and produces a markdown summary via OpenRouter `model="free"`. Gateway consumes SSE; on terminal event, POSTs the result to the backend webhook with Stripe-style signature.
8. Backend webhook: verifies HMAC signature (timestamp-bound, 300s replay window, constant-time compare). On valid completion: updates `summaries/{videoId}` to status=completed with markdown content. On valid failure: status=failed with errorCode + transient-or-permanent classification. Unknown `client_job_id` → 404 + log.
9. Android: Firestore listener auto-updates the SummaryScreen. Operator reads markdown.

### Auto-summarize
10. The 6h cron `scheduledSync` (existing) and the pull-to-refresh-triggered `syncAllPlaylists` callable (new) both, after writing fresh `videos/{videoId}` docs, enqueue auto-summary jobs for any video that has **no** existing `summaries/{videoId}` doc. The enqueue is a write to a `summaries/{videoId}` doc with status=queued (NOT pending — that's reserved for actively-dispatched).
11. A new cron `summaryDispatcher` runs every 5 minutes, takes the next batch of queued summaries (bounded by per-minute and remaining-daily OpenRouter quota), promotes them to status=pending, dispatches to summarizer, and increments the quota counter. Drains naturally over hours/days.
12. When the operator next opens a video whose summary completed in the background, the Firestore listener already has the content.

### Failure & recovery
13. **No transcript:** daemon falls back to `videoDetails.shortDescription`. Resulting summary is still produced and rendered; UX makes no distinction.
14. **Transient failure (network / 5xx / timeout):** doc state failed-transient. Manual Retry button on SummaryScreen. A daily cron `summaryRetryCron` also re-dispatches failed-transient summaries (subject to quota).
15. **Permanent failure (quota exhausted / daemon hard-rejected / dispatch 4xx):** doc state failed-permanent. No Retry button. Different copy.
16. **Stuck job (no webhook within 1h):** hourly cron `summarySweeper` flips status=running docs older than 1h to failed-transient. Daily retry cron picks them up.

### Quota
17. Operator has purchased a one-time $10 OpenRouter credit before launch → 1000 RPD cap on free models. Backend tracks daily count + sliding 60s window in `quota/openrouter` doc with transactional reads.
18. When the daily counter reaches 1000 (or per-minute window reaches 20): the entire app shows a banner at the top, all Summarize controls disable, callable returns `resource-exhausted`. Auto-summary dispatcher pauses. State clears at midnight UTC.

### Settings & administration
19. No new settings UI in v1 beyond what already exists. Single-tenant means no per-user config; the daily quota state and the allowlisted uid are both static at deploy time.

## Acceptance Criteria

| # | Criterion | Verification |
|---|-----------|-----|
| AC-1 | Given the operator is not signed in, when they call any callable function from the Android app, then the response is `unauthenticated` and no Firestore reads succeed. | `automated` — Firebase emulator suite test; assert callable rejects + Firestore rules deny anonymous reads. |
| AC-2 | Given a non-allowlisted Firebase Auth uid token, when calling any allowlisted callable, the response is `permission-denied`. | `automated` — Firebase emulator test with two test users. |
| AC-3 | Given the operator signs in with the allowlisted Google account, when the playlist screen loads, then playlists render from Firestore with zero network calls to `youtube.googleapis.com` from the device. | `interactive` — Maestro flow; capture network log via lazylogcat (filter on `youtube.googleapis.com`); assert zero matches. |
| AC-4 | Given the operator pulls-to-refresh on the playlist screen, when sync completes, then `playlists/{id}` and `videos/{id}` docs reflect the latest YouTube state. | `interactive` — Maestro flow; assert Firestore doc `lastSyncedAt` advances after pull gesture. |
| AC-5 | Given the operator taps a video tile, when the VideoDetailScreen Summary tab opens for a video with no prior summary, then `requestVideoSummary` is invoked and the in-progress UI renders within 500ms. | `interactive` — Maestro flow with timing assertion. |
| AC-6 | Given a summary request reaches the summarizer Cloud Run service, when the daemon completes, then a webhook POST hits `summaryWebhook` with a valid `X-Summarizer-Signature: t=<unix>,v1=<hmac>` header. | `automated` — docker-compose harness: run summarizer container against a fixed YouTube fixture URL; mock-backend captures the webhook + asserts signature verifies. |
| AC-7 | Given a webhook arrives with valid signature and known `client_job_id`, when handled, then `summaries/{videoId}` transitions running → completed and markdown content is non-empty; response is HTTP 204. | `automated` — Firebase emulator test invoking the HTTPS function directly with signed body. |
| AC-8 | Given a webhook arrives with valid signature but unknown `client_job_id`, when handled, then response is HTTP 404 and no Firestore doc is created. | `automated` — emulator test. |
| AC-9 | Given a webhook arrives with malformed or missing signature header, when handled, then response is HTTP 400 and no doc update occurs. Given a webhook arrives with a valid signature but timestamp older than 300 seconds, when handled, then response is HTTP 401 and no doc update occurs. | `automated` — emulator test (malformed-header + replay-window enforcement). |
| AC-10 | Given the OpenRouter daily counter is at the configured cap (1000), when the operator taps Summarize, then the app shows a top banner "Daily summary limit reached" and the Summarize CTA is disabled. | `interactive` — Maestro flow with backend pre-seeded `quota/openrouter` doc at cap. |
| AC-11 | Given the auto-summarize dispatcher is running and the queue contains newly-synced videos, when the dispatcher fires (every 5m), then it dispatches up to the per-minute cap (20) and never exceeds the daily cap. | `automated` — emulator test with a stubbed clock; assert dispatcher math respects both caps. |
| AC-12 | Given a summary has status=running with `requestedAt` older than 1 hour, when `summarySweeper` cron runs, then the doc transitions to status=failed-transient. | `automated` — emulator test with cron triggered manually. |
| AC-13 | Given a summary has status=failed-transient, when `summaryRetryCron` runs (daily), then the doc is re-dispatched and transitions back to status=pending (or stays failed-transient if quota exhausted). | `automated` — emulator test. |
| AC-14 | Given the daemon cannot extract a transcript (no captions, region-blocked, private), when the summary completes, then status=completed with content drawn from the video's `shortDescription` fallback and no failure is surfaced to the user. | `interactive` — docker-compose harness against a known no-caption fixture; verify `summaries/{videoId}.content` is non-empty. |
| AC-15 | Given Firestore security rules are deployed, when any client other than the allowlisted uid attempts to read `playlists/`, `videos/`, `summaries/`, or `quota/`, then rules deny the read. | `automated` — `firebase emulators:exec` with rule-test fixture covering the allowlisted uid (passes) and a stranger uid (denies). |
| AC-16 | Given the summarizer container starts cold on Cloud Run, when the first request arrives, then the daemon is reachable on `127.0.0.1:8787`, `/v1/refresh-free` has been called once, and the gateway is serving on `0.0.0.0:$PORT`. | `interactive` — docker-compose harness; assert container logs show both processes healthy and `/health` returns 200. |

## Non-Functional Requirements

- **Cost ceiling.** Steady-state LLM cost = $0 (OpenRouter free models). One-time $10 OpenRouter credit purchase is the only direct LLM spend. Cloud Run + Cloud Functions + Firestore stay within free quotas under normal personal usage.
- **Latency targets** (not hard SLOs; expectations):
  - Pull-to-refresh sync completion: ≤ 30s for ~10 playlists.
  - Cold-start first summary dispatch: ≤ 15s (daemon boot ~3s + refresh-free 5–10s).
  - Warm summary dispatch round-trip: ≤ 90s for a typical YouTube video with captions.
  - Webhook round-trip (summarizer → backend → Firestore visible to Android): ≤ 2s after daemon completes.
- **Security.** Webhook signature: Stripe-style `X-Summarizer-Signature: t=<unix>,v1=<hex-hmac-sha256>`. HMAC body = `${timestamp}.${raw_body}`. Replay window = 300s. Compare with `crypto.timingSafeEqual`. Per-summary secret (32 random bytes hex) so a single leaked secret compromises only one summary. Daemon Bearer token verified upstream by the (upcoming) timing-safe compare landed in steipete/summarize `0ec12ac`. Response codes: `204` on success (new write or idempotent re-delivery), `400` for malformed/missing signature header or bad JSON, `401` for valid-format signature that fails verification or whose timestamp exceeds the 300s replay window, `404` for unknown `client_job_id`.
- **Auth.** All backend HTTPS triggers either (a) carry an allowlisted Firebase Auth ID token via `onCall`, or (b) are Cloud Scheduler cron triggers (no auth context). `setCookies` legacy endpoint remains undeleted but stays out of any new code paths.
- **Quota.** Transactional read+write on `quota/openrouter` to prevent two concurrent requests both passing the cap check then both burning quota. Sliding 60s timestamp array (max 20 entries). Daily counter resets at UTC midnight.
- **Data integrity.** Idempotency on `summaries/{videoId}` keyed by videoId. Webhook reception is idempotent (multiple deliveries of the same `client_job_id` + same status do not double-write).
- **Observability.** Backend uses `firebase-functions/logger` with structured fields (`videoId`, `summarizerJobId`, `status`, `quotaRemaining`). Summarizer container logs to stdout (Cloud Run picks up). Android uses `lazylogcat` filterable tags `playster.auth`, `playster.sync`, `playster.summary`.
- **Dependency hygiene.** Resolve the `firebase-functions: ^6.3.0` + `firebase-admin: ^13.0.0` peer conflict in Phase 1 (bump `firebase-functions` to `^7` is the recommended direction). yt-dlp installed via `pip install -U "yt-dlp>=2026.02.21"` in the container; never apt.

## Edge Cases / Failure Modes

- **Subtree drift breaks daemon contract.** A subtree pull lands schema-breaking changes in `server-summarize-request.ts`. Mitigation: pin to known-good commits (`0ec12ac` for v1); a smoke test (in the docker-compose harness) validates the daemon's `mode`, `youtube`, and `extractOnly` fields before merging the pull.
- **PoToken / yt-dlp throttling on Cloud Run shared egress.** YouTube may throttle yt-dlp from Cloud Run egress IPs. Mitigation: fall back to youtubei first (daemon already does); long-tail risk is accepted in MVP.
- **OpenRouter free-model rotation.** Models in the `:free` ID list change. Mitigation: rely on `model="free"` + daemon's `refresh-free` rotation, never hardcode a specific `:free` ID.
- **OpenRouter credit exhaustion** (extremely unlikely on $10 = ~1000/day × many days). Mitigation: surface in the quota banner if the daily counter or actual provider 402 occurs.
- **First sync produces hundreds of queued summaries.** With 1000/day cap and 20/min, draining 500 videos takes ≈25 minutes of dispatcher firing. Acceptable.
- **YouTube rate-limits the backend's `youtubei.js` calls.** Mitigation: existing exponential backoff in `innertube-sync.ts`; not changed by this shape.
- **Cron clock drift / overlap.** `summaryDispatcher` 5-min cron could overlap if a previous run is still inflight. Mitigation: per-run lock doc `locks/summaryDispatcher` with transactional acquire + 4-minute TTL.
- **User signs out and back in with a different Google account.** They get a different Firebase Auth uid; their callable calls return `permission-denied`. Mitigation: documented behavior; surface a friendly "Wrong account — sign in with the allowlisted account" banner.
- **Webhook delivered while backend is mid-deploy.** Cloud Functions tolerate this; the new revision will pick up the next attempt. Summarizer's webhook retry policy should be: 3 attempts with exponential backoff, then give up and mark the gateway job failed (which generates a separate failed webhook). Out-of-MVP if not already present.
- **Concurrent manual + auto-summarize for the same video.** Idempotency check on `summaries/{videoId}` collapses the second one into the first.

## Affected Areas

**Android (`android/app/src/main/`):**
- `screens/auth/authViewModel.kt` — extend to bridge Google Sign-In to Firebase Auth (`GoogleAuthProvider.getCredential`, `signInWithCredential`).
- `screens/playlist/PlaylistScreen.kt` — replace direct YouTube Data API calls with Firestore listener on `playlists/`. Drop `GoogleAccountCredential` usage.
- `app/build.gradle.kts` — drop `google-api-services-youtube`, `google-api-client-android` (planning doc §2.3 cleanup). Add Firebase BOM: `firebase-auth-ktx`, `firebase-firestore-ktx`, `firebase-functions-ktx`.
- NEW: `screens/videoDetail/VideoDetailScreen.kt` (tabbed container).
- NEW: `screens/videoDetail/summary/SummaryScreen.kt` + `SummaryViewModel.kt` (state machine over Firestore listener).
- NEW: `data/firestore/` Kotlin DTOs mirroring `PlaylistDocument`, `VideoDocument`, `SummaryDocument`.
- NEW: Top-of-app QuotaBanner Composable observing `quota/openrouter`.
- NEW: `google-services.json` placed under `android/app/`.

**Backend (`backend/functions/src/`):**
- `auth/verify.ts` (NEW) — `requireAllowlistedUid` + `allowlistedCall` wrapper using `defineString("ALLOWED_UID")`.
- `index.ts` — convert `syncAllPlaylists`, `syncPlaylist`, `syncWatchLater` from `onRequest` to `onCall` wrapped in `allowlistedCall`. Leave `scheduledSync` as `onSchedule` (no auth context).
- `summarizer/` (NEW directory):
  - `dispatch.ts` — `requestVideoSummary` callable; pre-flight quota; dispatch with HMAC secret + webhook URL; persists `summaries/{videoId}`.
  - `webhook.ts` — `summaryWebhook` HTTPS function. Stripe-style signature verification; 300s replay window; idempotent doc update.
  - `quota.ts` — transactional `assertOpenRouterQuotaAvailable` + `incrementOpenRouterQuota` on `quota/openrouter`.
  - `autoSummarize.ts` — hook called from sync handlers after writing `videos/{videoId}` to enqueue status=queued summary docs.
  - `dispatcher.ts` (cron, every 5m) — drain queued summaries within quota.
  - `sweeper.ts` (cron, hourly) — flip stuck running docs to failed-transient.
  - `retry.ts` (cron, daily 04:00 UTC) — re-dispatch failed-transient summaries.
- `models/index.ts` — add `SummaryDocument`, `QuotaDocument` shapes.
- `package.json` — bump `firebase-functions` from `^6.3.0` to `^7` to resolve admin peer conflict.
- NEW: `backend/firestore.rules` — hardcoded uid allow rule (Admin SDK only for writes).

**Summarizer (`summarizer/`):**
- `summarize-api/src/schemas.ts` — extend `urlJobSchema` with `webhook_url`, `webhook_secret`, `client_job_id`. `mode` field left in but documented as no-op (per PO decision to defer cleanup).
- `summarize-api/src/runners/url-runner.ts` — extend `pickDefined` forward-list with daemon-native keys (`youtube`, `videoMode`, `timestamps`, `forceSummary`, `noCache`, `extractOnly`, `prompt`, `maxCharacters`). Add webhook delivery on terminal SSE event. HMAC body = `${timestamp}.${raw_body}`. 3 attempts with backoff.
- `summarize-api/src/db/migrations/` — schema migration adding `webhook_url`, `webhook_secret`, `client_job_id` columns on `jobs`.
- `summarize-api/Dockerfile` — replace with multi-stage build (daemon-build, api-build, runtime). yt-dlp via `pip install -U "yt-dlp>=2026.02.21"`. System deps: `ffmpeg`, `tesseract-ocr`, `tini`. Node 24.
- NEW: `summarizer/deploy/entrypoint.js` — boots daemon with `--token`, waits for `/health`, calls `/v1/refresh-free`, starts summarize-api.
- `summarizer/summarize-daemon/` — subtree pull to `0ec12ac` (or latest stable + bearer hardening) before Phase 3 ships.
- NEW: `summarizer/deploy/docker-compose.yml` (or update existing) — for the local end-to-end harness.

**Cross-cutting:**
- NEW: Maestro flows under `android/maestro/` (or `maestro/`) for the 5 interactive ACs.
- NEW: `firebase.json` / `.firebaserc` if not yet present; emulator config covering `firestore`, `auth`, `functions`.
- NEW: GitHub Actions (or local script) wiring Maestro + Firebase emulator + docker-compose harness as `pnpm verify:e2e`.

## Dependencies / Sequencing Notes

The 7 phases in the planning doc map to 6 slices in this shape (Phase 7 / slides is out of scope; first-pass quota cap correction folds into Phase 6).

**Strict ordering:**

1. **Phase 1 — Backend auth + lockdown** unblocks 2, 4. Includes `firebase-functions` bump to v7 to resolve peer conflict.
2. **Phase 2 — Android Firebase Auth + Firestore view** unblocks 5. Depends on Phase 1 (rules deployed).
3. **Phase 3 — Summarizer container + Cloud Run + webhook signing** unblocks 4. Includes subtree pull to `0ec12ac`, Stripe-style HMAC, yt-dlp pip install, multi-stage Dockerfile.
4. **Phase 4 — Backend summary orchestration** (callable, webhook, quota, dispatcher cron, sweeper cron, retry cron, auto-summarize hook) depends on 1 and 3.
5. **Phase 5 — Android summary UI** (VideoDetailScreen + SummaryScreen + QuotaBanner) depends on 4.
6. **Phase 6 — Cleanup** (remove Android youtube-data-api; remove unauth `onRequest` sync endpoints — note: setCookies and summarize-api `mode` deferred by PO). Depends on 5 being verified.

**Parallelizable:** Phases 2 and 3 can run in parallel once 1 lands.

**External prerequisites (operator action, not in any phase):**
- Operator purchases $10 OpenRouter credit and provisions `OPENROUTER_API_KEY` in Secret Manager.
- Operator provisions `SUMMARIZER_API_KEY`, `SUMMARIZE_TOKEN` (32-byte hex each).
- Operator signs in once on Android before Phase 1 rules are enforced, so the uid can be captured into `ALLOWED_UID` config and rules.

## Questions Asked This Stage

Five rounds × four questions = 20 questions across the interaction model, behavioral dynamics, surface area, failure modes, and boundaries. Recorded in `po-answers.md` under stage 2.

## Answers Captured This Stage

See `po-answers.md` for full text. Highlights driving the spec:

- Tile + Detail entry point ⇒ scope adds `VideoDetailScreen`.
- App-decides-everything ⇒ no model/length/prompt pickers in v1 UI; daemon's `model="free"` is canonical.
- Auto-summarize-all + 1000/day cap ⇒ new `summaryDispatcher` cron + `summaries/` queued state + auto-enqueue from sync handlers.
- Manual + daily retry ⇒ new `summaryRetryCron`.
- Hourly stuck sweep ⇒ new `summarySweeper`.
- API-key only on summarizer (no IAM/IP allowlist) ⇒ explicit accepted risk.
- Four verification surfaces required: Maestro, lazylogcat, Firebase emulator, docker-compose harness.
- $10 OpenRouter credit is an MVP operational prereq.
- Deferred cleanups: legacy `setCookies` endpoint and summarize-api `mode` field — stay in code as no-ops, not removed in v1.

## Out of Scope

Hard out-of-scope for v1:

- **Slides extraction** (Phase 7 in planning doc) — daemon's slides feature stays unused. No `slides: true` requests, no slides-related Firestore fields.
- **Multi-tenant data model** — singleton Firestore paths only; no `users/{uid}/...` keying. Migration cost noted in `01-intake.md` Risks.
- **Push notifications (FCM)** — auto-summary completion is silent. No FCM topic/token setup.
- **Per-playlist auto-summary opt-out** — all newly-synced videos are auto-summarized; no per-playlist toggle.
- **User-facing options** — no model picker, no length picker, no prompt picker, no language picker, no force-refresh button. v1 surfaces no daemon options.
- **In-app notifications panel** — completion is discovered passively.
- **Stale-summary detection** — if a video changes after its summary was generated, no banner; the old summary stands.
- **Regenerate / re-summarize** — opening an existing non-failed summary never dispatches. Manual regeneration is not exposed.
- **Web frontend** — Android only.
- **Cache persistence across Cloud Run cold starts** — daemon cache is ephemeral.
- **Cloud Run sidecar refactor** — sidecars are now GA but v1 ships with the single-container + `entrypoint.js` orchestrator from the planning doc. Sidecars are a future migration.
- **Stripe-style HMAC key rotation (`v0`+`v1` parallel verification)** — v1 ships a single active key version.
- **Removing the legacy `setCookies` HTTP endpoint** — deferred by PO.
- **Removing summarize-api's `mode` field** — deferred by PO; field stays in schema as no-op.
- **OAuth re-prompt or token rotation flows** — existing OAuth flow stays as-is.
- **Settings / preferences UI for the operator** — no new settings.

Soft out-of-scope (could land if straightforward, otherwise deferred):

- "Summary ready" tile badge (planning is "no notification" but a passive badge is consistent and cheap — leave it to plan stage to decide).
- Quota banner ETA display (banner shows "Daily limit reached" without a precise countdown; a relative countdown is a nice-to-have).

## Definition of Done

- All 16 ACs verified via their stated method, evidence captured.
- Phases 1–6 (per Sequencing Notes) complete; no leftover unauthenticated HTTP endpoints introduced by this work; no direct YouTube Data API calls from the Android device.
- Operator can complete the golden flow end-to-end (sign in → see playlists → pull to refresh → open video → see summary appear) on a real device with a real Cloud Run deployment.
- Maestro suite runs green locally; lazylogcat artifacts attached to verify outputs.
- Firebase emulator test suite green (rules + callables + webhook + cron + quota).
- Docker-compose harness green against at least 2 fixture videos (one with captions, one no-caption fallback).
- `firestore.rules` deployed with the operator's actual uid hardcoded; rules-test suite covers allowlisted + stranger paths.
- `OPENROUTER_API_KEY`, `SUMMARIZER_API_KEY`, `SUMMARIZE_TOKEN`, `ALLOWED_UID` all provisioned in production Secret Manager / config.
- Subtree pinned to `0ec12ac` (or later stable with bearer-token hardening); pin SHA recorded in `summarizer/summarize-daemon/SUBTREE_PIN.md`.
- Documentation deliverables in `## Documentation Plan` are produced.
- Open questions from `00-index.md` resolved or formally deferred.
- A green CI / verify pass on `feat/wire-android-backend-summarizer`.

## Verification Strategy

**Automated checks** (Vitest + Firebase emulator + rules-test + docker-compose, run via `pnpm verify`):

- Backend allowlist enforcement (AC-1, AC-2): Firebase emulator suite invoking callables with no token / wrong token / allowlisted token.
- Firestore rules deny strangers (AC-15): `@firebase/rules-unit-testing` against `backend/firestore.rules`.
- Webhook signature + replay window (AC-7, AC-8, AC-9): emulator test invoking `summaryWebhook` HTTPS function directly with signed bodies, varying signature/timestamp.
- Quota transactional cap (AC-11): emulator test with stubbed `Date.now()`; assert dispatcher math at the boundary.
- Stuck-job sweep (AC-12), retry cron (AC-13): emulator test with manual cron triggers.

**Interactive verification** (Android side):

- **Platform:** Android (target device or AVD; `targetSdk = 34`).
- **Primary tool:** Maestro flows under `android/maestro/`. Flows:
  1. `signin-and-see-playlists.yaml` — verifies AC-3.
  2. `pull-to-refresh.yaml` — verifies AC-4.
  3. `manual-summary-fresh.yaml` — verifies AC-5 (timing assertion via Maestro's `assertVisible` with timeout).
  4. `quota-exhausted-banner.yaml` — verifies AC-10 (with backend pre-seeded quota doc).
- **Companion tool:** `lazylogcat` filtering on tags `playster.auth`, `playster.sync`, `playster.summary`. Captured during each Maestro run, attached as evidence.
- **Network verification:** lazylogcat filter on `youtube.googleapis.com` (zero matches expected post-Phase 2) plus on the new summarizer Cloud Run URL (matches expected during summary flows).

**Interactive verification** (service side):

- **Platform:** Cloud Run (local via Docker Desktop for verification; production deploy verified post-merge).
- **Primary tool:** `docker-compose` harness at `summarizer/deploy/docker-compose.yml`:
  - `summarizer-api` container + `summarize-daemon` (same image) launched together.
  - `mock-backend` container with a webhook receiver that verifies signatures and writes a JSON artifact.
  - `summary-test` script POSTs against `/v1/jobs` with two fixture videos: one captioned (≈10min Sundar Pichai keynote clip) and one no-caption (a music video). Asserts webhook arrives with correct signature and `summaries/{videoId}.content` non-empty for both.
- **Evidence:** container logs to `verify-artifacts/`, webhook capture JSON, signature-verification trace.

**Human-in-the-loop checks:**

- Sign-in with the actual allowlisted Google account (Firebase Auth uid capture happens here; cannot be emulated).
- $10 OpenRouter credit purchase + key provisioning in Secret Manager.
- Hardcoding the captured uid into `backend/firestore.rules` and deploying.
- Visual eyeball pass on the SummaryScreen markdown rendering for a known summary (e.g., does it look readable, do code blocks render, do headings come through).

## Documentation Plan

**1. Reference — `docs/architecture/summarize-pipeline.md`**
- Type: reference / explanation hybrid.
- Audience: maintainer (future-you).
- Must cover: data flow Android → Firebase Auth → callable → quota → summarizer → daemon → webhook → Firestore → listener; Firestore document shapes (`summaries`, `quota`); the webhook signature scheme; the cron timing and locking; the subtree maintenance procedure.
- Must NOT cover: code-level details (those belong in inline doc-comments where needed) or operational deploy steps (separate how-to).
- Target location: `docs/architecture/` (new directory if needed). Not gitignored.

**2. How-to — `docs/operations/deploy-and-bootstrap.md`**
- Type: how-to guide.
- Audience: operator (you, six months from now, doing a fresh deploy).
- Must cover: prerequisite checklist ($10 OpenRouter credit; secrets to provision; first-sign-in uid capture procedure; rules deployment; `gcloud run deploy summarizer`; verifying both processes healthy; how to refresh the daemon subtree; how to roll back).
- Must NOT cover: code architecture (separate reference).
- Target location: `docs/operations/`.

**3. README update — root `README.md`**
- Type: readme-update.
- Audience: anyone discovering the repo.
- Must cover: the three components (Android / backend / summarizer), single-tenant nature, dependencies between them, where to find the architecture doc, top-level `pnpm verify` command.
- Must NOT cover: deep operational detail or full architecture (link out).
- Target location: existing `README.md`.

**4. Existing planning doc remains.** `docs/internal/2026-05-17-wire-android-backend-summarizer.md` is gitignored and stays as historical record. Do not duplicate its content into the new docs; cross-link if helpful.

No tutorial needed (single-operator project — no onboarding flow for new users). No new user-facing app documentation needed beyond a short in-app legal/about screen (out of v1).

## Freshness Research

- **Source:** Firebase Functions GitHub issues #2772 (firebase-admin-node), #1640 (firebase-functions).
  **Why it matters:** Resolves a blocking peer-dep conflict (functions ^6 + admin ^13 cannot install together).
  **Takeaway:** Bump `firebase-functions` to `^7` in Phase 1.

- **Source:** Firebase Security Rules docs (rules-and-auth, rules-conditions, 2025–2026 updates).
  **Why it matters:** Validates that hardcoded uid in rules is idiomatic for single-tenant; alternative is custom claims.
  **Takeaway:** Keep hardcoded uid for v1 (per planning doc). Note custom-claim alternative in architecture doc.

- **Source:** Cloud Run sidecar GA announcement (Aug 2024); Cloud Run multi-process patterns (ahmet.im, Google codelab).
  **Why it matters:** Sidecars now offer cleaner separation than `tini`/`supervisord`/`entrypoint.js`. Future migration path.
  **Takeaway:** v1 ships single-container + `entrypoint.js` (per planning doc, accepted by PO); document sidecar refactor as a future option.

- **Source:** OpenRouter docs `/api/reference/limits`, Zendesk article on free-model caps, 2026 changelog notes.
  **Why it matters:** Original plan's `200/day` is wrong; actual free cap is `50/day` un-topped or `1000/day` post-$10-credit.
  **Takeaway:** PO chose $10 credit; quota constant in code = 1000/day.

- **Source:** OpenRouter free-model collection (May 2026 snapshot); marketingscoop changelog on `gemini-2.0-flash-exp:free` deprecation.
  **Why it matters:** Don't reference deprecated model IDs.
  **Takeaway:** Use `model="free"` exclusively; daemon's `refresh-free` maintains the active rotation.

- **Source:** webhooks.fyi/security/replay-prevention; Stripe webhook signing docs; Convoy blog on Stripe-style signatures.
  **Why it matters:** Plain body-only HMAC is replayable; community consensus has moved to timestamp-bound + structured headers.
  **Takeaway:** Adopt Stripe-style `t=<unix>,v1=<hmac>` with 300s replay window. Per-summary secret per the planning doc.

- **Source:** `gh` query on github.com/steipete/summarize: HEAD `0ec12ac` (2026-05-17), PRs #225–#227 (security hardening).
  **Why it matters:** Vendored subtree is at `e34ce25c`; PRs #226 and #227 add timing-safe bearer comparison and rate-limit on failed auth.
  **Takeaway:** Subtree pull to `0ec12ac` before Phase 3 ships. Pin SHA recorded in `SUBTREE_PIN.md`.

- **Source:** yt-dlp wiki on YouTube authentication; yt-dlp issue #15012 (PoToken); release notes for 2026.02.21+ (CVE-2026-26331 fix).
  **Why it matters:** Apt-installed yt-dlp on Debian is months stale, missing PoToken support and vulnerable to CVE-2026-26331.
  **Takeaway:** Dockerfile must use `pip install -U "yt-dlp>=2026.02.21"` or the static binary.

## Recommended Next Stage

- **Option A (default):** `/wf slice wire-android-backend-summarizer` — Recommended. The spec covers six sequential phases with parallelizable subsets (Phases 2 and 3 in parallel after 1) and 16 acceptance criteria spread across multiple distinct surfaces. This is a textbook case for slicing — each phase is its own delivery unit with its own AC subset and verification surface.
- **Option B:** `/wf plan wire-android-backend-summarizer` — Not recommended. The shape is multi-component and multi-phase; planning a single monolithic implementation would lose the value of incremental ACs and reviewer focus.
- **Option C:** `/wf intake wire-android-backend-summarizer` — Not recommended. No intake-level questions surfaced as fundamentally wrong; the planning doc was solid and the corrections (peer-dep, OpenRouter cap, yt-dlp install, webhook style, subtree pin) are technical refinements that belong in shape, not a return to intake.

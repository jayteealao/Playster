---
schema: sdlc/v1
type: slice
slug: wire-android-backend-summarizer
slice-slug: summary-orchestration
status: defined
stage-number: 3
created-at: "2026-05-17T21:45:53Z"
updated-at: "2026-05-17T21:45:53Z"
complexity: m
depends-on: [auth-and-android-firebase, summarizer-container]
tags: [backend, callable, webhook, hmac, quota, cron, openrouter, firestore]
refs:
  index: 00-index.md
  slice-index: 03-slice.md
  siblings:
    - 03-slice-auth-and-android-firebase.md
    - 03-slice-summarizer-container.md
    - 03-slice-summary-ui.md
  plan: 04-plan-summary-orchestration.md
  implement: 05-implement-summary-orchestration.md
implement-status: complete
---

# Slice: Summary orchestration (callable + webhook + quota + dispatcher)

## Goal

Wire slices 1 and 2 together with the backend orchestration that turns a `videoId` into a persisted, signed-and-verified `summaries/{videoId}` Firestore document — both via on-demand (`requestVideoSummary` callable from Android) and via background auto-summarization of newly-synced videos. Enforce the 1000/day OpenRouter quota transactionally. Dispatch from the auto-summarize queue at a steady pace (every 5 minutes, respecting per-minute and per-day caps).

## Why This Slice Exists

This is the integration layer that proves slices 1 and 2 actually work together. It owns the per-summary state machine (queued → pending → running → completed | failed-transient | failed-permanent), the Stripe-style webhook verification half of the contract (slice 2 produces signatures, this slice verifies them), the quota math, and the auto-summarize behavior the PO chose in Round 2.

The cron-based dispatcher is non-trivial: it must drain a queue without exceeding 20 RPM or the daily 1000 cap, must coordinate with concurrent manual `requestVideoSummary` calls (which also burn quota), and must not double-dispatch when two cron firings overlap.

## Scope

**In scope:**

- New directory `backend/functions/src/summarizer/`:
  - `dispatch.ts` — exports `requestVideoSummary` as an `allowlistedCall` callable.
    - Input: `{ videoId: string; model?: string }`. Model defaults to `"free"`.
    - Validates the video exists in `videos/{videoId}`.
    - Idempotency: if `summaries/{videoId}` exists and status ∈ `{queued, pending, running, completed}` → return `{ summaryId: videoId }` without dispatch.
    - Quota pre-flight via `assertOpenRouterQuotaAvailable` (transactional).
    - Reserve doc: write `summaries/{videoId}` with `{ status: "pending", model, requestedAt, webhookSecret: <32-byte hex>, ... }`.
    - POST to `${SUMMARIZER_URL}/v1/jobs` with `X-API-Key: $SUMMARIZER_API_KEY` and body `{ url: "https://www.youtube.com/watch?v=${videoId}", options: { model, format: "markdown" }, webhook_url: <region URL of summaryWebhook>, webhook_secret, client_job_id: videoId }`.
    - On non-2xx: update doc to `status=failed`, `errorCode="dispatch_failed"`, classify as transient if 5xx/network and permanent if 4xx. Throw `HttpsError("internal")` only for true unexpected errors; callable-level errors return as failed-doc + success response to callable to avoid Android retry storms.
    - On 2xx: parse `{ id: summarizerJobId }`, update doc to `status=running`, `summarizerJobId`, `dispatchedAt`. Call `incrementOpenRouterQuota` (in the same transaction as pre-flight ideally — see plan stage).
    - Return `{ summaryId: videoId }`.
  - `webhook.ts` — exports `summaryWebhook` as an `onRequest` HTTPS function.
    - Reads `X-Summarizer-Signature` header; parses `t=<unix>,v1=<hex>`.
    - Reads raw body (before JSON parse).
    - Reject if `|now - t| > 300` (replay window).
    - Looks up `summaries/{client_job_id}.webhookSecret`; if doc missing → return 404 (per PO Round 4).
    - Compute expected `hmac = crypto.createHmac("sha256", webhookSecret).update(`${t}.${raw_body}`).digest("hex")`.
    - Constant-time compare via `crypto.timingSafeEqual(Buffer.from(v1, "hex"), Buffer.from(expected, "hex"))`. If lengths differ or compare fails → return 401.
    - Parse body. Idempotency: if doc.status already in terminal state (`completed` | `failed-*`) and the incoming status matches → return 200, no-op.
    - Update doc: `status=completed` (or `failed-transient`/`failed-permanent` depending on `error.code` mapping), `content`, `completedAt`. Failed-permanent triggers when `error.code ∈ {quota_exhausted, unrecoverable, transcript_impossible}` — exact codes from daemon's error vocabulary; plan stage enumerates.
    - Return 204.
  - `quota.ts` — exports `assertOpenRouterQuotaAvailable` + `incrementOpenRouterQuota`.
    - Transactional read+write on `quota/openrouter`.
    - Doc shape: `{ date: string (YYYY-MM-DD UTC), requestCount: number, dailyLimit: 1000, perMinuteLimit: 20, recentTimestamps: number[] (max 20, sliding 60s window) }`.
    - Day-rollover: if `data.date !== todayUTC()`, reset counters in the same transaction.
    - Daily cap exceeded → throw `HttpsError("resource-exhausted", "Daily summary limit reached. Resets at midnight UTC.")`.
    - Per-minute cap exceeded → throw `HttpsError("resource-exhausted", "Rate limit; try again in a moment.")`.
    - On success: append `Date.now()` to `recentTimestamps` (trimmed), increment `requestCount`, write back.
  - `autoEnqueue.ts` — exported helper called from sync handlers after writing `videos/{videoId}` docs. For each new videoId, idempotently writes `summaries/{videoId}` with `status=queued` if no doc exists yet. Does NOT call the dispatcher or check quota — just enqueues.
  - `dispatcher.ts` — exports `summaryDispatcher` as an `onSchedule` (every 5 minutes).
    - Acquires lock at `locks/summaryDispatcher` (transactional; doc with `{ acquiredAt, ttlSeconds: 240 }`). If lock held by another instance with `now - acquiredAt < ttl` → return early.
    - Reads `quota/openrouter`. Computes remaining budget for the per-minute window (min(20 - recentTimestamps.length, 1000 - requestCount)).
    - Queries `summaries` where `status == "queued"` ordered by `requestedAt asc`, limit = remaining budget.
    - For each queued doc: invokes the same dispatch path as `requestVideoSummary` (extract a shared helper from `dispatch.ts`).
    - Releases lock.
- `index.ts` — register `requestVideoSummary`, `summaryWebhook`, `summaryDispatcher` exports. Inject `autoEnqueue` call into existing `syncAllPlaylists`, `syncPlaylist`, `syncWatchLater`, and `scheduledSync` handlers after each batch of `videos/` writes completes.
- `models/index.ts` — add `SummaryDocument`, `QuotaDocument` TypeScript shapes.
- `firestore.rules` — extend to cover `summaries/` (allow read for allowlisted uid, deny write — Admin SDK only) and `quota/` (allow read for allowlisted uid, deny write).
- Firebase secrets: define `SUMMARIZER_URL`, `SUMMARIZER_API_KEY`. Inject into the functions that need them via `secrets: [...]`. `ALLOWED_UID` already defined in slice 1.
- Emulator-suite tests for AC-7, AC-8, AC-9, AC-11, AC-15-extension. The auto-enqueue + dispatcher cycle is testable via emulator cron triggering.

**Out of scope (handled by other slices or deferred):**

- `summarySweeper` cron (AC-12 stuck-job recovery) → **deferred to v1.1**.
- `summaryRetryCron` (AC-13 daily retry of failed-transient) → **deferred to v1.1**.
- VideoDetailScreen / SummaryScreen / QuotaBanner observation → slice 4.
- Multi-model round-robin → out of scope (PO chose $10 credit + single 1000/day counter).

## Acceptance Criteria

Mapped to shape's ACs:

- **AC-5** (partial — dispatch round-trip latency) — Verified by emulator + docker-compose harness: when `requestVideoSummary({videoId})` is invoked against an emulated callable that proxies to a real summarizer-container, the in-progress UI render happens within 500ms (slice 4 verifies the UI side; this slice verifies the callable returns within 500ms with `summaries/{videoId}` doc visible to listeners).
- **AC-7**: Valid signature + known `client_job_id` → doc transitions running → completed with non-empty content. Emulator test with hand-signed body.
- **AC-8**: Valid signature + unknown `client_job_id` → 404. Emulator test.
- **AC-9**: Valid signature + stale timestamp (>300s) → 401. Emulator test.
- **AC-11**: Dispatcher respects per-minute and daily caps simultaneously. Emulator test with stubbed clock, seeded `quota/openrouter` doc, large queue.
- **AC-15** (extension): rules-unit-test extended to `summaries/` and `quota/` collections.

**Slice-local ACs (not in shape):**

- Auto-enqueue is idempotent: running `syncAllPlaylists` twice does not create duplicate `summaries/{videoId}` docs.
- Dispatcher lock prevents double-dispatch under simulated overlap.
- `quota/openrouter` day-rollover at midnight UTC resets the counters in a single observable transition.
- Quota transaction is genuinely atomic under concurrent contention (emulator-level test driving N concurrent `requestVideoSummary` calls).

## Dependencies on Other Slices

- **`auth-and-android-firebase`** — Imports `allowlistedCall` from `auth/verify.ts`. Inherits the `ALLOWED_UID` config param. Extends `firestore.rules`.
- **`summarizer-container`** — Calls the deployed `${SUMMARIZER_URL}/v1/jobs`. Verifies signatures generated by the summarize-api's url-runner. Relies on the `client_job_id` + `webhook_url` + `webhook_secret` schema additions landed in slice 2.

Downstream consumer:
- `summary-ui` (slice 4) invokes `requestVideoSummary` and observes `summaries/`/`quota/` via Firestore listeners.

## Risks

- **Day-rollover race during a dispatcher run.** If the dispatcher starts at 23:59:55 UTC and individual dispatch calls cross midnight, some increment a stale-date doc. Mitigation: the quota transaction always re-reads `date` and resets if needed; worst case is one extra request slipping into the new day (acceptable).
- **Quota transaction contention under high concurrency.** Firestore transactions retry on conflict; a queue of 20 concurrent dispatches could see noticeable latency. Mitigation: dispatcher operates serially over its queue (one transaction per video); manual `requestVideoSummary` calls are rare-enough that contention is low in practice.
- **Dispatch + increment atomicity.** Ideally `assertOpenRouterQuotaAvailable` and `incrementOpenRouterQuota` happen in one transaction with the dispatch HTTP call inside. But Firestore transactions are pure-Firestore; the HTTP call must happen outside. Mitigation: pessimistic increment (count it before dispatch); if dispatch fails, decrement in a follow-up transaction (best-effort; tolerates rare over-counting).
- **Stale running summaries pile up** (AC-12 deferred). Without the sweeper cron, a summary that times out without a webhook stays in `running` forever. Mitigation: documented limitation in v1; manual cleanup via Firebase console. Plan stage decides if a feature flag should make the sweeper trivially addable in v1.1.
- **Failed-transient never retries automatically** (AC-13 deferred). User must tap Retry manually in slice 4's UI. Mitigation: documented; v1.1 candidate.
- **Race: webhook arrives before dispatch finishes updating doc to `running`.** Doc is at `pending`; webhook update is for `completed`. Mitigation: webhook handler accepts any pre-terminal state and transitions to terminal; idempotency holds.
- **`SUMMARIZER_URL` mismatch.** If the Cloud Run URL changes (e.g., region change), backend would dispatch to a stale URL. Mitigation: store as a secret, not a hardcoded constant.
- **Body-byte mismatch between signer and verifier.** Slice 2 produces a signature over `JSON.stringify(payload)`; slice 3 must read the raw request bytes (not Express/Fastify's re-parsed body) and use those exact bytes for HMAC. Mitigation: webhook handler uses `req.rawBody` (Firebase Functions exposes this); slice 3's emulator test asserts exact-byte equality.
- **`onSchedule` cron tolerance.** Firebase's scheduled functions don't guarantee precise 5-minute timing. Mitigation: dispatcher logic doesn't depend on exact timing; it respects whatever budget remains. Worst case: 6 or 4 minutes between firings.

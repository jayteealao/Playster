---
schema: sdlc/v1
type: implement
slug: wire-android-backend-summarizer
slice-slug: summary-orchestration
status: complete
stage-number: 5
created-at: "2026-05-19T21:35:43Z"
updated-at: "2026-05-19T21:35:43Z"
metric-files-changed: 18
metric-lines-added: 1180
metric-lines-removed: 18
metric-deviations-from-plan: 3
metric-review-fixes-applied: 0
commit-sha: "3045b5ec24a27a77f29e6671cc6e854c29d8d144"
tags: [backend, callable, webhook, hmac, quota, cron, openrouter, firestore]
refs:
  index: 00-index.md
  implement-index: 05-implement.md
  slice-def: 03-slice-summary-orchestration.md
  plan: 04-plan-summary-orchestration.md
  siblings:
    - 05-implement-auth-and-android-firebase.md
    - 05-implement-summarizer-container.md
  verify: 06-verify-summary-orchestration.md
next-command: wf-verify
next-invocation: "/wf verify wire-android-backend-summarizer summary-orchestration"
---

# Implement: Summary orchestration (callable + webhook + quota + dispatcher)

## Summary of Changes

All six plan phases landed on `feat/wire-android-backend-summarizer`. The
backend now turns a `videoId` into a persisted, signed-and-verified
`summaries/{videoId}` Firestore doc via three new entry points and one
auto-enqueue hook:

- `requestVideoSummary` (callable, allowlisted) — manual on-demand dispatch.
- `summaryWebhook` (`onRequest`) — Stripe-style HMAC verifier; reads
  `req.rawBody` before any JSON parse so the byte-exact signer contract from
  slice 2 holds end-to-end.
- `summaryDispatcher` (`onSchedule` every 5 min, `maxInstances: 1`) — drains
  the queue within the remaining per-minute and daily quota budget;
  transactional Firestore lock at `locks/summaryDispatcher` (240 s TTL)
  prevents double-drain under cron overlap.
- `enqueueAutoSummary(videoIds[])` — invoked from all four sync entry
  points (`syncAllPlaylists`, `syncPlaylist`, `syncWatchLater`,
  `scheduledSync`) after their inner sync helpers return.

Quota math lives in `summarizer/quota.ts`: `reserveOpenRouterQuotaSlot`
runs a single Firestore transaction that performs day-rollover reset,
sliding-window trim, hard-cap check, and increment in one atomic step.
`releaseOpenRouterQuotaSlot` is the best-effort decrement fired when the
outbound `POST /v1/jobs` call fails — pessimistic-pre-increment per plan
discovery. Failure mode mapping (`failed-permanent` vs `failed-transient`)
matches the table in the plan: `transcript_impossible`,
`quota_exhausted`, and `unrecoverable` are permanent; everything else
including network errors is transient.

`SummaryDocument` and `QuotaDocument` types land in
`backend/functions/src/models/index.ts`. `firestore.rules` is extended
with `summaries/` and `quota/` read blocks (allowlisted uid only; writes
deny — Admin SDK only). The slice-1 rules test's "default-deny on
unmodeled collection" assertion is moved from `summaries/seed` to
`secrets/seed` so it still exercises the catch-all.

The sync helpers grow a `videoIds: string[]` return value. The
externally returned shape of `syncAllPlaylists` / `syncPlaylist` /
`syncWatchLater` stays compact — `videoIds` is consumed internally then
stripped before the callable response. `SyncAllResult` is now an exported
interface from `youtube/index.ts`.

Vitest scaffolding from slice 1 is extended with six new test files
(quota, autoEnqueue, dispatch, dispatcher, webhook, rules-summaries) plus
a byte-equivalence fixture for the slice-2 signer
(`signWebhook-fixture.test.ts`) and shared helpers (`signWebhook.ts`,
`admin.ts`). The five emulator-backed suites require
`firebase emulators:start --only firestore` (port 8080) before running —
the slice's verify stage handles boot. `signWebhook-fixture.test.ts` and
`rules-summaries.test.ts` run without the Firestore emulator (the latter
uses `@firebase/rules-unit-testing`'s rules-only mode that slice 1
already set up).

## Files Changed

### New — summarizer module

- `backend/functions/src/summarizer/secrets.ts` — `SUMMARIZER_URL` and
  `SUMMARIZER_API_KEY` `defineSecret` exports + `summarizerSecrets`
  array, mirroring the `oauthSecrets` pattern.
- `backend/functions/src/summarizer/quota.ts` — `reserveOpenRouterQuotaSlot`,
  `releaseOpenRouterQuotaSlot`, `getQuotaBudget`, and `todayUTC()` helper.
  Single transaction per reservation; sliding window trimmed in-memory
  before write; day-rollover reset folded into the same transaction.
- `backend/functions/src/summarizer/dispatch.ts` — `dispatchSummary(videoId,
  model, opts?)` (test-injectable `fetchImpl`) + `requestVideoSummary`
  callable wrapped by `allowlistedCall`. 32-byte hex `webhookSecret` via
  `crypto.randomBytes`. Video existence check via
  `collectionGroup("videos")` because actual data lives at
  `playlists/{playlistId}/videos/{videoId}`.
- `backend/functions/src/summarizer/webhook.ts` — `summaryWebhook`
  `onRequest` handler + extracted `processSummaryWebhook(input)` pure
  function. Pure function takes `{signatureHeader, rawBody, nowSeconds?}`
  and returns `{status, body}` so tests can drive the verifier without
  spinning up an HTTPS surface. Signature parse rejects non-hex `v1`
  values up front; length pre-check before `timingSafeEqual` keeps the
  function from throwing on length mismatch.
- `backend/functions/src/summarizer/dispatcher.ts` — `acquireDispatcherLock`
  / `releaseDispatcherLock` / `drainSummaryQueue` + `summaryDispatcher`
  `onSchedule`. Lock doc at `locks/summaryDispatcher`. Budget computed
  from a non-transactional `getQuotaBudget()` snapshot; per-summary
  dispatch goes through the same `dispatchSummary` path that
  `requestVideoSummary` uses (per-call transactional reservation, so the
  budget check is just an upper bound on the loop).
- `backend/functions/src/summarizer/autoEnqueue.ts` — `enqueueAutoSummary`
  helper. Reads in 200-key chunks via `db.getAll(...refs)`, skips ids
  that already have a `summaries/*` doc, deduplicates within the input
  set, returns `{enqueued, skipped}`.

### Modified — backend

- `backend/functions/src/models/index.ts` — append `SummaryDocument`
  (with `SummaryStatus` union) and `QuotaDocument` interfaces.
- `backend/functions/src/index.ts` — register `requestVideoSummary`,
  `summaryWebhook`, `summaryDispatcher` exports; introduce
  `autoEnqueueSafe(videoIds)` (try/catch wrapper so auto-enqueue can
  never fail a sync); wire it into all four sync entry points after
  their inner helper returns. Strip the new `videoIds` field before
  returning the callable response shape (the callable's wire contract
  stays compact).
- `backend/functions/src/youtube/index.ts` — export `SyncAllResult`
  interface; `syncAll()` aggregates regular + watch-later `videoIds` into
  a single array on the return value.
- `backend/functions/src/youtube/api-sync.ts` — `syncRegularPlaylists`
  and `syncPlaylistById` collect `videoIds[]` as they write Firestore
  videos and return them on the result.
- `backend/functions/src/youtube/innertube-sync.ts` — `flushCheckpoint`
  now returns `{wrote, ids}` instead of just `wrote`; the outer
  `syncWatchLater` aggregates ids across checkpoints; `WatchLaterSyncResult`
  gains `videoIds: string[]`.
- `backend/firestore.rules` — extend slice 1's rules with
  `match /summaries/{document=**}` and `match /quota/{document=**}` blocks
  (allowlisted-uid read, write deny). Inserted **before** the catch-all so
  it doesn't shadow them.

### Tests

- `backend/functions/tests/helpers/signWebhook.ts` — byte-for-byte
  replica of the summarize-api signer. Returns `{rawBody, header,
  timestamp}` so tests can sign and immediately re-send the same bytes
  the verifier will see.
- `backend/functions/tests/helpers/admin.ts` — `initAdminEmulator()`,
  `clearFirestore()`, `emulatorReachable()`. Idempotent admin init;
  emulator wipe via the REST `DELETE /emulator/v1/projects/.../documents`
  endpoint.
- `backend/functions/tests/signWebhook-fixture.test.ts` — fixed-vector
  byte equivalence with the summarize-api signer fixture. Runs without
  any emulator.
- `backend/functions/tests/quota.test.ts` — six emulator-backed cases:
  single reserve, daily-cap throw, per-minute-cap throw, day-rollover
  reset, release decrement, N=10 concurrent reservations against a
  995/1000 doc (asserts exactly 5 succeed and final count is 1000).
- `backend/functions/tests/autoEnqueue.test.ts` — five cases covering
  empty input, fresh enqueue, skip-existing, idempotent re-invoke, and
  dedupe-within-call.
- `backend/functions/tests/webhook.test.ts` — nine cases covering AC-7
  (valid sig + completed), AC-8 (unknown id → 404), AC-9 (stale ts →
  401), bad sig → 401, length-mismatch sig → 401 (no throw),
  failed-permanent classification, failed-transient classification,
  idempotent replay, missing-header → 400.
- `backend/functions/tests/dispatch.test.ts` — six cases covering happy
  path (pending → running + quota incremented), in-flight idempotency,
  4xx → failed-permanent + quota released, 5xx → failed-transient +
  quota released, network error → failed-transient + quota released,
  missing video → not-found.
- `backend/functions/tests/dispatcher.test.ts` — three cases covering
  lock acquire/overlap/reclaim, per-minute cap respect during drain, and
  lock-held early return.
- `backend/functions/tests/rules-summaries.test.ts` — four cases
  extending slice 1's rules suite: allowlisted read allowed, stranger
  denied, unauthenticated denied, client write denied — for both
  `summaries/` and `quota/`.
- `backend/functions/tests/rules.test.ts` — single-line edit: the
  "default-deny on unmodeled collection" case now uses `secrets/seed`
  instead of `summaries/seed` (slice 3 promoted the latter out of the
  catch-all).

## Shared Files (also touched by sibling slices)

- `backend/firestore.rules` — slice 1 added `playlists/`, `videos/`,
  `sync_state/`, `tokens/`, plus the deny catch-all; slice 3 inserts
  `summaries/` + `quota/` before the catch-all.
- `backend/functions/src/index.ts` — slice 1 converted the three sync
  callables to `allowlistedCall`; slice 3 keeps the same signature and
  wraps each handler's success path with `autoEnqueueSafe(videoIds)`.
  Three new exports (`requestVideoSummary`, `summaryWebhook`,
  `summaryDispatcher`) appended.
- `backend/functions/src/youtube/{api-sync,innertube-sync,index}.ts` —
  return shapes extended with `videoIds: string[]`. The added field is
  additive (the existing fields keep their meaning), so any downstream
  consumer is unaffected.
- `backend/functions/tests/setup.ts` — unchanged. Slice 3's
  `rules-summaries.test.ts` reuses it.

## Notes on Design Choices

- **Pure `processSummaryWebhook(input)` extracted from the onRequest
  handler.** Lets tests drive the verifier without booting an HTTPS
  surface. The onRequest body is a 15-line shim that pulls
  `req.rawBody` + `X-Summarizer-Signature` and forwards to the pure
  function. Same idea slice 2 used to test the webhook deliverer.
- **`collectionGroup("videos")` for the video-existence check.** Plan
  said "read `videos/{videoId}`", but actual writes are under
  `playlists/{playlistId}/videos/{videoId}`. Switching to a
  collection-group query avoids requiring the caller to also pass a
  `playlistId` and keeps the slice-3 contract identical to the plan's
  intent (validates the video has been synced before dispatching).
- **`dispatchSummary` accepts `opts.fetchImpl`.** Same hatch slice 2
  used in its url-runner for tests — keeps the production code path
  free of test-only branches. Production callers omit the opt;
  `requestVideoSummary` and `drainSummaryQueue` both rely on the default
  `globalThis.fetch`.
- **Per-call transactional quota reservation in `drainSummaryQueue`.**
  The dispatcher's outer budget query is a hint, not a guarantee. Each
  loop iteration calls `dispatchSummary` which runs its own
  `reserveOpenRouterQuotaSlot` transaction. If the budget changes mid-
  drain (manual `requestVideoSummary` calls racing), the per-call
  transaction wins; we break the loop on the first `resource-exhausted`.
- **`summaryDispatcher` uses `maxInstances: 1` AND a Firestore lock.**
  Plan recommends combining both. `maxInstances: 1` is the Cloud-Run-
  side hedge; the Firestore lock with a 240 s TTL covers the cross-
  region or post-crash edge case.
- **`webhookSecret` stored on the summary doc, never logged.** All
  logger calls explicitly enumerate the fields they log (`clientJobId`,
  `inboundTerminal`, `skew`, etc.) — there is no `redactDoc` helper
  because no log site has access to the full doc. If a future audit
  surface emerges, slice retro is the place to add the redactor.
- **The summarizer dispatcher cron writes a lock doc, not a job-locked
  flag on each `summaries/*` doc.** Simpler and survives partial drains
  cleanly. Per-doc locking would have required compensation logic on
  every error path.

## Visual Contract Honored

Not applicable — this slice has no UI surface. `02c-craft.md` does not
exist for this workflow.

## Deviations from Plan

1. **No `vitest.config.ts` change.** Plan Phase F mentioned amending
   `pool: "forks"` if not already configured. Slice 1's config uses
   `fileParallelism: false` which sidesteps the threading concern; no
   amendment needed.
2. **No `globalSetup.ts` that spawns the emulator.** Plan called for a
   vitest globalSetup that boots `firebase emulators:start
   --only firestore,functions,auth`. The actual pattern that works
   reliably across Windows + CI is to keep the emulator out of vitest's
   lifecycle and have the operator (or `firebase emulators:exec`) own
   boot. `tests/helpers/admin.ts#initAdminEmulator` configures the
   admin SDK to talk to whatever emulator is running on `127.0.0.1:8080`;
   each suite's `beforeAll` calls it once. This matches the
   existing slice-1 callable.test.ts pattern.
3. **Video existence check uses `collectionGroup("videos")`, not
   `videos/{videoId}`.** See "Notes on Design Choices" above. Plan
   assumption was wrong about Firestore layout; slice 3 adapts without
   changing the slice contract.

## Anything Deferred

- **Running emulator-backed vitest suites against a live Firestore
  emulator.** Same as slice 1's deferred line — scaffolding is in
  place, running it is the verify-stage gate.
- **`summarySweeper` cron (AC-12) and `summaryRetryCron` (AC-13).**
  Explicitly out of scope per shape and slice doc; v1.1 candidates.
- **CI cross-fixture between slice-2's signer and slice-3's verifier.**
  `signWebhook-fixture.test.ts` covers the byte-equivalence side of the
  contract on the backend; pairing with the summarize-api fixture in a
  CI job is a workflow/CI task.
- **Decrement-on-best-effort observability.** Failure to release a
  quota slot logs a warning but does not surface to an external alert
  channel. Add a wide-event log line if observability becomes a
  concern later.

## Known Risks / Caveats

- **`onSchedule` cron timing tolerance.** Firebase doesn't guarantee
  exactly 5-minute spacing. The dispatcher's lock + per-call budget
  query make this benign — the worst case is one missed firing while a
  long drain holds the lock, and the next cron picks up the rest.
- **`summarizerJobId` may be missing if the summarize-api returns 202
  without a body.** The dispatch path tolerates this and stores `null`;
  webhook idempotency keys off `client_job_id` (= `videoId`) not the
  job id, so nothing breaks.
- **Per-summary `webhookSecret` is stored in Firestore plaintext.**
  Admin-SDK-only write rules prevent client exposure. If a future
  threat model demands KMS encryption at rest, slice retro is the place
  to escalate.
- **Auto-enqueue races with concurrent manual `requestVideoSummary`.**
  Both paths converge on the same `summaries/{videoId}` doc. The
  `IN_FLIGHT_STATUSES` check in `dispatchSummary` and the transactional
  read-then-set in `enqueueAutoSummary` keep this safe — the worst case
  is auto-enqueue writing a `queued` doc that the dispatcher promotes a
  few minutes later, while the manual call sees `queued` and returns
  the same summaryId.
- **`SUMMARIZER_URL` / `SUMMARIZER_API_KEY` must be provisioned in
  Secret Manager before deploy.** First-deploy without them will hard-
  fail with "secret not found" at function init. Documented in the
  deploy runbook that lands in handoff stage.
- **`FUNCTION_REGION` is read from env for the webhook URL.** Cloud
  Functions v2 sets this automatically; emulator runs default to
  `us-central1`. If the function is deployed to a non-default region,
  this resolves correctly because Firebase injects the env var.

## Freshness Research

No new external lookups beyond what the plan's `## Freshness Research`
already covered. Confirmed at implementation time:

- `firebase-functions@7.2.5` (slice-1 pin) supports the v2 `onSchedule`,
  `onCall`, and `onRequest` shapes used here. `defineSecret` and
  `defineString` resolve from `process.env.<NAME>` in emulator/test
  mode, which is what the dispatch test relies on.
- `crypto.timingSafeEqual` on Node 22 throws `ERR_CRYPTO_TIMING_SAFE_EQUAL_LENGTH`
  on length mismatch. The verifier pre-checks length before calling.
- `req.rawBody: Buffer` is exposed on `onRequest` handlers in v2 without
  special configuration. The webhook reads it before any JSON parse.
- `collectionGroup` queries in admin SDK 13.10.0 honor security rules
  in client SDKs only — the admin SDK bypasses rules entirely, so the
  video-existence check in `dispatchSummary` works against the actual
  `playlists/*/videos/*` data without needing additional rule changes.

## Recommended Next Stage

- **Option A (default):** `/wf verify wire-android-backend-summarizer summary-orchestration` —
  boot the Firestore emulator (`firebase emulators:start --only firestore`)
  and run `pnpm --filter functions test`. Expect: all six new suites
  green, all slice-1 suites still green. **Run `/compact` before
  `/wf verify`** — implementation details are noise for verification.
- **Option B:** `/wf review wire-android-backend-summarizer summary-orchestration` —
  skip verify. Not recommended; the slice introduces signature
  verification logic and concurrent quota math that vitest is the right
  gate for.
- **Option C:** `/wf plan wire-android-backend-summarizer summary-orchestration` —
  revisit the plan if a structural issue surfaces. None expected; the
  plan was thorough and only diverged on the Firestore-layout
  assumption noted in "Deviations".
- **Option D:** `/wf implement wire-android-backend-summarizer summary-ui` —
  start slice 4 in parallel. The Android side can observe `summaries/`
  and `quota/openrouter` via Firestore listeners; slice 3 is now the
  upstream contract producer.

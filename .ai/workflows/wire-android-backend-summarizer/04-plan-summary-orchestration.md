---
schema: sdlc/v1
type: plan
slug: wire-android-backend-summarizer
slice-slug: summary-orchestration
status: complete
stage-number: 4
created-at: "2026-05-18T07:49:39Z"
updated-at: "2026-05-18T07:49:39Z"
metric-files-to-touch: 13
metric-step-count: 42
has-blockers: false
revision-count: 0
tags: [backend, callable, webhook, hmac, quota, cron, openrouter, firestore]
stack-source: confirmed
refs:
  index: 00-index.md
  plan-index: 04-plan.md
  slice-def: 03-slice-summary-orchestration.md
  siblings:
    - 04-plan-auth-and-android-firebase.md
    - 04-plan-summarizer-container.md
    - 04-plan-summary-ui.md
  implement: 05-implement-summary-orchestration.md
implement-status: complete
next-command: wf-verify
next-invocation: "/wf verify wire-android-backend-summarizer summary-orchestration"
---

# Plan: Summary orchestration (callable + webhook + quota + dispatcher)

## Plan Overview

This slice wires Firebase Functions orchestration that turns a videoId into a persisted, signed-and-verified `summaries/{videoId}` Firestore document. It is the integration seam between slices 1 (auth) and 2 (summarizer container), and the upstream producer for slice 4 (UI).

Six implementation phases produce, in order: shared types + rules → quota module → manual dispatch path + auto-enqueue → webhook verifier → scheduled dispatcher → emulator harness + tests. Each phase ends at a git-commit boundary that builds and lints clean.

Strict dependency: this slice cannot begin until slice 1's `auth/verify.ts#allowlistedCall` is committed on the integration branch and slice 2's `urlJobSchema` extensions (`webhook_url`, `webhook_secret`, `client_job_id`) and signer are merged. The webhook byte-contract with slice 2 is the highest-risk integration point — Phase D includes a paired harness test that uses slice 2's signer (or a byte-for-byte replica) to validate `crypto.timingSafeEqual` succeeds.

## Affected Code (Playbook A)

### Files this slice creates

- `backend/functions/src/summarizer/dispatch.ts` — `requestVideoSummary` callable + reusable `dispatchSummary(videoId)` helper.
- `backend/functions/src/summarizer/webhook.ts` — `summaryWebhook` `onRequest` HTTPS handler.
- `backend/functions/src/summarizer/quota.ts` — `assertOpenRouterQuotaAvailable` + `incrementOpenRouterQuota` + `decrementOpenRouterQuota` (best-effort rollback).
- `backend/functions/src/summarizer/autoEnqueue.ts` — `enqueueAutoSummary(videoIds: string[])` helper.
- `backend/functions/src/summarizer/dispatcher.ts` — `summaryDispatcher` `onSchedule` cron + transactional lock helper.
- `backend/functions/src/summarizer/secrets.ts` — `defineSecret("SUMMARIZER_URL")`, `defineSecret("SUMMARIZER_API_KEY")` exports, `summarizerSecrets` array.
- `backend/functions/src/summarizer/index.ts` — barrel exports for the four functions.
- `backend/functions/test/summarizer/*.test.ts` — emulator-based vitest suites (six files; see Phase F).
- `backend/functions/test/helpers/signWebhook.ts` — byte-for-byte replica of slice 2's signer (kept in sync via a fixture test).
- `backend/functions/test/helpers/emulator.ts` — boot/teardown helpers wrapping `@firebase/rules-unit-testing` and the Functions emulator.
- `backend/functions/vitest.config.ts` — vitest configuration.

### Files this slice modifies

- `backend/functions/src/index.ts` — register three new exports (`requestVideoSummary`, `summaryWebhook`, `summaryDispatcher`); call `enqueueAutoSummary` after `syncAll()` / `syncPlaylistById()` / `syncWatchLater()` returns. Note that slice 1 will have already converted the three sync wrappers from `onRequest` to `onCall`; this slice adds the auto-enqueue call inside each callable's success path (currently at lines 23, 47, 67 of the pre-slice-1 `index.ts` — exact post-slice-1 lines TBD).
- `backend/functions/src/models/index.ts` — append `SummaryDocument` and `QuotaDocument` interfaces. Existing shape uses `FieldValue | Date` for server timestamps; new types follow the same convention.
- `backend/firestore.rules` — extend slice 1's rules with `/summaries/{summaryId}` (read iff `request.auth.uid == ALLOWED_UID`, write deny) and `/quota/{quotaId}` (same). Admin SDK writes bypass rules.
- `backend/functions/package.json` — add devDeps `vitest`, `@firebase/rules-unit-testing`, `firebase-functions-test` is already present.
- `backend/functions/.eslintrc` (or ESLint flat config) — add `test/` to the lint target if not already covered.

### Injection sites for `autoEnqueue`

Located in `backend/functions/src/index.ts` and in the inner sync helpers. Post-slice-1, the three callables are:
- `syncAllPlaylists` (currently `onRequest` lines 19–32; slice 1 converts to `onCall`) — invokes `syncAll()` from `youtube/index.ts:13`. Slice 3 wraps this call and feeds the returned video lists into `enqueueAutoSummary`.
- `syncPlaylist` (currently `onRequest` lines 37–56) — invokes `syncPlaylistById()` from `youtube/api-sync.ts:162`; that function returns `{ videoCount }` only. **Surprise:** `syncPlaylistById` writes videos but does not currently return their IDs. This slice modifies the helper signature to additionally return `videoIds: string[]` so the auto-enqueue hook can run without re-reading Firestore. Same change applies to `syncRegularPlaylists` in `youtube/api-sync.ts:80`.
- `syncWatchLater` (currently `onRequest` lines 61–75) — invokes `syncWatchLater()` from `youtube/innertube-sync.ts:227`. **Surprise:** this function flushes in batched checkpoints (`flushCheckpoint` at line 159). Each checkpoint already iterates over its `items` array; the cleanest hook is to collect IDs there and return them aggregated in `WatchLaterSyncResult`.
- `scheduledSync` (lines 80–96, `onSchedule`) — calls `syncAll`. Same hook as `syncAllPlaylists`.

### Reuse opportunities found

- **Existing `onSchedule` pattern:** `scheduledSync` at `backend/functions/src/index.ts:80` already uses the v2 `onSchedule` shape `{ schedule, memory, timeoutSeconds, secrets }`. The new `summaryDispatcher` cron mirrors this exactly with `schedule: "every 5 minutes"`.
- **Existing `defineSecret` pattern:** `oauth.ts:5-6` uses `defineSecret("OAUTH_CLIENT_ID")` and exports an `oauthSecrets` array. The new `summarizer/secrets.ts` follows the same pattern (parallel naming: `summarizerSecrets`).
- **Existing batched-write pattern:** `api-sync.ts` and `innertube-sync.ts` both use `MAX_BATCH_SIZE = 500`. The auto-enqueue helper reuses this constant (or its own; trivial).
- **No existing HMAC helpers** — slice 2 introduces one in `summarize-api`; this slice writes a verifier that is *not* shared code (it lives in the backend functions tree). The byte-contract is enforced via the harness test in Phase F.
- **No existing transaction helpers** — Firestore `runTransaction` is called directly. Slice 3 introduces `runTransactionWithRetry` as a thin helper in `quota.ts` if surface justifies it.

### Slice-1 prerequisites this slice consumes

- `auth/verify.ts#allowlistedCall<TIn, TOut>(handler)` — wraps `onCall`, runs `requireAllowlistedUid(ctx)`, returns the same `onCall`-shaped function.
- `auth/verify.ts#requireAllowlistedUid(ctx)` — throws `HttpsError("unauthenticated")` or `HttpsError("permission-denied")` as appropriate.
- `defineString("ALLOWED_UID")` (or `defineSecret`) — owned by slice 1.
- `firestore.rules` — slice 1 covers `playlists/`, `videos/`, `tokens/`, `sync_state/`; slice 3 extends with `summaries/` and `quota/`. Both slices' rules live in `backend/firestore.rules` so slice 3's diff is purely additive.

## Domain Crossing: Backend ↔ Summarizer (Playbook B)

### Wire shapes

**Outbound (this slice → slice 2 at `${SUMMARIZER_URL}/v1/jobs`):**

```json
{
  "url": "https://www.youtube.com/watch?v=<videoId>",
  "options": { "model": "free", "format": "markdown" },
  "webhook_url": "https://<region>-<project>.cloudfunctions.net/summaryWebhook",
  "webhook_secret": "<64-char hex; per-summary>",
  "client_job_id": "<videoId>"
}
```

Headers: `X-API-Key: ${SUMMARIZER_API_KEY}`, `Content-Type: application/json`.

Response (slice 2's contract): `200 { "id": "<summarizerJobId>" }` on success; `4xx` on validation failure; `5xx` on infra issues. Slice 3 maps `4xx → failed-permanent`, `5xx | network → failed-transient`.

**Inbound (slice 2 → this slice at `summaryWebhook`):**

```json
{
  "client_job_id": "<videoId>",
  "status": "completed" | "failed",
  "result": { "summary": "<markdown>", "title": "<string>", "model": "<string>" },
  "error": { "code": "<string>", "message": "<string>" }
}
```

Headers: `X-Summarizer-Signature: t=<unix>,v1=<hex>`, `Content-Type: application/json`.

### Byte-exact HMAC contract

- **Signer (slice 2's `url-runner.ts`):** `const body = JSON.stringify(payload); const t = Math.floor(Date.now()/1000); const canonical = `${t}.${body}`; const sig = crypto.createHmac("sha256", webhook_secret).update(canonical).digest("hex"); POST body=body, header=`t=${t},v1=${sig}`.`
- **Verifier (this slice's `webhook.ts`):** reads `req.rawBody` (Buffer); parses header; recomputes `canonical = `${t}.${req.rawBody.toString("utf8")}` `; `crypto.timingSafeEqual(Buffer.from(receivedV1, "hex"), Buffer.from(expectedV1, "hex"))`.

The verifier MUST use `req.rawBody.toString("utf8")` rather than `JSON.stringify(req.body)` — Express/Firebase re-stringification will not byte-match upstream output (whitespace, key ordering, NaN handling).

### Failure-mapping table (used by webhook.ts)

| Inbound `status` | Inbound `error.code`            | Doc transition                        |
|------------------|---------------------------------|---------------------------------------|
| `completed`      | n/a                             | → `completed` + `content`             |
| `failed`         | `quota_exhausted`               | → `failed-permanent`                  |
| `failed`         | `unrecoverable`                 | → `failed-permanent`                  |
| `failed`         | `transcript_impossible`         | → `failed-permanent`                  |
| `failed`         | (any other)                     | → `failed-transient`                  |
| `failed`         | (missing)                       | → `failed-transient` (cautious default) |

Daemon error vocabulary is enumerated by slice 2; the table above is the planning-stage best estimate. Plan-stage open decision #2 below tracks the exact list.

## Test & Verification (Playbook C)

### Stack

From `00-index.md` `stack:` block:
- Languages: typescript.
- Testing: junit, espresso (Android), **vitest** (backend — confirmed for this slice), maestro (Android E2E).
- Build: tsc.
- Package managers: pnpm.
- Observability: lazylogcat (Android only; n/a for this slice).
- CLIs: firebase (emulators), gcloud.

### Vitest configuration

- New `backend/functions/vitest.config.ts` with `test: { environment: "node", testTimeout: 30_000, hookTimeout: 60_000, globalSetup: "./test/helpers/globalSetup.ts" }`.
- `globalSetup` spawns `firebase emulators:start --only firestore,functions,auth --project demo-playster` in the background, waits for the emulator hub, and shuts down on teardown.
- Tests connect via `@firebase/rules-unit-testing#initializeTestEnvironment` for rules tests, and via `firebase-admin` pointed at the emulator (`FIRESTORE_EMULATOR_HOST=localhost:8080`) for handler tests.

### Test plan per AC

- **AC-7 (valid signature + known job → completed):** `webhook.test.ts` seeds `summaries/{videoId}` with `webhookSecret` + `status=running`, signs a `{client_job_id, status:"completed", result:{summary:"hello"}}` body using the helper, POSTs to the emulated `summaryWebhook` URL, asserts doc transitions to `status=completed` with `content="hello"`.
- **AC-8 (unknown client_job_id → 404):** same test file, no seed; assert `res.status === 404` and no doc created.
- **AC-9 (stale timestamp → 401):** signs with `t = now - 301`, assert `res.status === 401` and doc unchanged.
- **AC-11 (dispatcher caps):** `dispatcher.test.ts` seeds 50 queued `summaries/*` docs, seeds `quota/openrouter` near caps, stubs `fetch` (via `vi.mock`) to return `{id:"jobN"}` for every dispatch, invokes the dispatcher handler directly with `firebase-functions-test`, asserts ≤ 20 docs transition to `pending`/`running` and `requestCount` increments correctly.
- **AC-15 extension (rules):** `rules.test.ts` uses `@firebase/rules-unit-testing` with the deployed `firestore.rules`. Cases: allowlisted uid reads `summaries/abc` → allowed; stranger uid → denied; any uid write to `summaries/abc` → denied; same for `quota/openrouter`.

### Slice-local AC tests

- **Auto-enqueue idempotency:** `autoEnqueue.test.ts` calls `enqueueAutoSummary(["v1","v2"])` twice, asserts exactly two `summaries/*` docs with `status=queued` exist (no duplicates).
- **Dispatcher lock:** `dispatcher.test.ts` includes a "two concurrent invocations" case where both transactions race to acquire `locks/summaryDispatcher`; assert only one drains the queue.
- **Day-rollover atomicity:** `quota.test.ts` seeds doc with `date = "2026-05-17"`, stubs `Date.now()` to a `2026-05-18` moment, calls `assertOpenRouterQuotaAvailable` and asserts doc becomes `{date:"2026-05-18", requestCount:0, recentTimestamps:[]}` in a single transaction.
- **Quota transaction atomicity under concurrency:** `quota.test.ts` fires `N=10` parallel `assertOpenRouterQuotaAvailable()+incrementOpenRouterQuota()` against a doc with `requestCount: 995, dailyLimit: 1000`; assert exactly 5 succeed, 5 throw `resource-exhausted`, and final `requestCount === 1000`.
- **Body-byte HMAC fixture:** `signWebhook.test.ts` computes a signature using the local helper and a hard-coded fixture cribbed from slice 2's url-runner test fixtures (cross-checked at PR review); asserts the bytes match. Detects silent drift between signer and verifier.

### Test commands

- Run all: `pnpm --filter functions test`.
- Single file: `pnpm --filter functions test -- webhook.test.ts`.
- Lint: `pnpm --filter functions lint` (existing).
- Build: `pnpm --filter functions build`.

### Stubbing the summarizer container

The vitest suite does NOT spin up the real summarizer container. Instead:
- For dispatch tests: `vi.mock("node-fetch" | "undici" | global.fetch)` is used to stub HTTP calls to `${SUMMARIZER_URL}/v1/jobs`. Returns a configurable response.
- For webhook tests: tests directly POST a hand-signed body to the emulated `summaryWebhook` HTTPS function. The hand-signer is the same `test/helpers/signWebhook.ts` used in production-equivalent flows.
- Cross-slice integration (real container talking to real backend) is the domain of the docker-compose harness owned by slice 2 (AC-6 / AC-14). This slice's tests stop at the wire boundary.

## Web Research (Playbook D)

Findings filtered to what changes the plan; each is one short paragraph.

### Firestore transactions for sliding-window counters

Pattern: store last-N timestamps as an array field, trim on every write inside a transaction. Read-modify-write is atomic; concurrent writers retry on conflict (Firestore exponentially backs off). For N=20 entries plus daily counter, contention is minimal at our scale (≤ 20 dispatches/min). The "decrement on dispatch failure" follow-up transaction does not need to be atomic with the original since over-counting by 1–2 is acceptable; it just needs eventual consistency.

### `onSchedule` v2 cron syntax

Confirmed: `schedule: "every 5 minutes"` is valid App Engine cron syntax accepted by Firebase Functions v2. Alternative `"*/5 * * * *"` (unix cron) is also supported. Firebase Functions v2 `onSchedule` runs on Cloud Run under the hood and benefits from regional placement (`region: "us-central1"` default). No retry by default — failed schedules just log; the next firing picks up. Slice 3's dispatcher is idempotent (lock + per-video state check), so no retry is needed.

### Cron overlap guard

Options surveyed: (a) `maxInstances: 1` on the function; (b) Firestore-doc-based distributed lock with TTL; (c) external queue (Cloud Tasks). **Recommendation:** combine (a) and (b). `maxInstances: 1` prevents Cloud Run from running two pods concurrently for the dispatcher; the Firestore lock with 240s TTL covers the edge case where one invocation crashes mid-drain and the next firing arrives 60s later (the TTL ensures the dead lock can be reclaimed before the cron's natural 5-minute cadence). Slice declines option (c) — overkill for v1.

### `req.rawBody` availability in Firebase Functions v2

Confirmed: `onRequest` handlers in Functions v2 expose `req.rawBody: Buffer` containing the unparsed request bytes. No special configuration required (in v1 it required `rawBody: true`; v2 always provides it). This is essential — `JSON.parse(req.body)` then `JSON.stringify(parsed)` will NOT byte-match upstream because key ordering and whitespace differ.

### `crypto.timingSafeEqual` length mismatch

If buffer lengths differ, `crypto.timingSafeEqual` throws synchronously (`ERR_CRYPTO_TIMING_SAFE_EQUAL_LENGTH`). Slice 3 wraps it: pre-check lengths, return 401 if mismatch, otherwise call `timingSafeEqual`. Never branch on length-equal-versus-not inside the compare path itself.

### Stripe reference

Cross-checked the verifier against Stripe's JS SDK `Webhooks.constructEvent`: same `t=<unix>,v1=<hex>` header schema, same `${t}.${body}` canonical bytes, same 300s default tolerance, same `timingSafeEqual`. Our implementation matches.

### Dispatch + increment atomicity

Two patterns considered:
1. **Pessimistic-pre-increment:** transaction increments first, then dispatch HTTP. If HTTP fails, fire-and-forget decrement transaction. Over-counts by 1–2 in rare crash-between-tx-and-tx scenarios.
2. **Post-success-increment:** dispatch HTTP first, then transaction. If two callers race, both pass pre-flight and both burn quota — we exceed cap by N. Under-counts never happen.

**Recommendation:** **(1) pessimistic-pre-increment.** Over-counting is bounded by retries; under-counting (burning OpenRouter credit past the cap) is hard ceiling we must not cross. The decrement follow-up is best-effort and logged.

### Day-rollover detection inside transactions

Compute `todayUTC()` from `Date.now()` (caller's clock). Inside the transaction, compare `doc.date` to `todayUTC()`; if different, reset `requestCount=0`, `recentTimestamps=[]`, `date=todayUTC()` in the same `tx.set(...)`. The race window (00:00 UTC ± microseconds across two transactions on either side) is benign — both observe the new date and both reset to 0+1 = 1 (atomic).

### Logging without leaking secrets

`firebase-functions/logger` accepts structured fields. NEVER pass `webhookSecret` as a logged field. Helper: a `redact(doc)` function in `summarizer/util.ts` that omits `webhookSecret` from any doc-shape before logging.

### Webhook retry semantics

Slice 2's url-runner retries 3× with exponential backoff. So slice 3's webhook handler will see ≤ 3 attempts per terminal event. Slice 3's idempotency rule: if doc is already in a terminal state matching the incoming status → 200 no-op. Different terminal status (rare; would mean slice 2 sent contradictory events) → log warning, keep first state, return 200 (we trust the first event).

### Firestore document size limit

1 MiB per doc. `quota/openrouter.recentTimestamps` capped at 20 entries × 8 bytes ≈ 160 bytes. Other fields trivial. No risk. `summaries/{videoId}.content` could approach the limit for very long summaries — daemon currently caps at ~50 KB markdown. No mitigation needed; document the ceiling in architecture doc.

## Open Decisions for Discovery Phase

1. **Pessimistic-pre-increment vs post-success-increment for quota.** Plan recommends pessimistic-pre-increment with best-effort decrement on dispatch failure. Confirm with PO that occasional under-counting (1–2 fewer summaries on a heavy failure day) is preferred over potentially over-burning OpenRouter credit.
2. **Daemon error-code vocabulary.** Exact strings for `failed-permanent` classification (`quota_exhausted`, `unrecoverable`, `transcript_impossible`) are slice-2-owned. Plan stage assumes these names; discovery confirms with slice 2 author before Phase D's webhook test fixtures are frozen.
3. **`summaries/{videoId}` write semantics — `set` vs `create`.** Plan uses transactional read-then-`set` to keep auto-enqueue, manual `requestVideoSummary`, and the dispatcher all collision-safe. Alternative `tx.create(ref, ...)` throws on existing doc — simpler, but requires try/catch on every path. Discovery picks one consistently.
4. **Auto-enqueue timing — inline per video vs batched at end of sync.** Plan defaults to **batched at end of sync** (single `enqueueAutoSummary(videoIds[])` call after each sync helper returns), to minimize Firestore RPCs and let the dispatcher cron handle pacing. Inline-per-video would let backpressure surface earlier; PO chose auto-summarize-all in Round 2 so batched is fine. Discovery confirms.

## Implementation Steps

Phases are sequential. Each phase ends at a single git commit. The branch is `feat/wire-android-backend-summarizer`; commits are scoped (e.g., `feat(backend): add SummaryDocument + QuotaDocument types`).

### Phase A — Models + rules

**Goal:** Land typed shapes and security rules so subsequent phases have a fixed Firestore schema to build against.

1. **Step A1.** Append `SummaryDocument` interface to `backend/functions/src/models/index.ts`. Fields: `videoId: string`, `status: "queued" | "pending" | "running" | "completed" | "failed-transient" | "failed-permanent"`, `model: string`, `webhookSecret: string` (note: stored in plaintext; Admin-SDK-only-write rules prevent client exposure), `summarizerJobId?: string`, `content?: string`, `errorCode?: string`, `errorMessage?: string`, `requestedAt: FieldValue | Date`, `dispatchedAt?: FieldValue | Date`, `completedAt?: FieldValue | Date`.
2. **Step A2.** Append `QuotaDocument` interface. Fields: `date: string` (YYYY-MM-DD UTC), `requestCount: number`, `dailyLimit: number`, `perMinuteLimit: number`, `recentTimestamps: number[]`, `updatedAt: FieldValue | Date`.
3. **Step A3.** Edit `backend/firestore.rules` to extend slice 1's rules. Add `match /summaries/{summaryId}` block (read iff `request.auth.uid == "<ALLOWED_UID>"`, write deny) and identical `match /quota/{quotaId}` block. Verify slice 1's deny-by-default catchall doesn't shadow.
4. **Step A4.** Run `pnpm --filter functions build`; expect green. Run `pnpm --filter functions lint`; expect green.
5. **Step A5.** Commit: `feat(backend): add SummaryDocument + QuotaDocument types and rules`.

### Phase B — Quota module

**Goal:** Standalone, tested quota math. No HTTP or Firestore-cross-cutting yet.

1. **Step B1.** Create `backend/functions/src/summarizer/quota.ts`. Export `assertOpenRouterQuotaAvailable(tx?: Transaction): Promise<{requestCount, perMinuteUsed}>` and `incrementOpenRouterQuota(tx?: Transaction): Promise<void>`. Both accept an optional transaction; if none passed, they run their own. Day-rollover check happens inside the transaction; sliding-window trim happens inside increment.
2. **Step B2.** Add `decrementOpenRouterQuota(): Promise<void>` for best-effort rollback on dispatch failure. Runs its own transaction; never throws.
3. **Step B3.** Add `todayUTC()` helper as `new Date().toISOString().slice(0, 10)`. Unit test inline (vitest test in same file via `import.meta.vitest` if configured, else in `quota.test.ts`).
4. **Step B4.** Daily cap throws `new HttpsError("resource-exhausted", "Daily summary limit reached. Resets at midnight UTC.")`. Per-minute cap throws `new HttpsError("resource-exhausted", "Rate limit; try again in a moment.")`.
5. **Step B5.** Run `pnpm --filter functions build`. Commit: `feat(backend): summarizer quota module with transactional sliding window`.

### Phase C — Dispatch + auto-enqueue

**Goal:** Manual callable `requestVideoSummary` works end-to-end against a stubbed summarizer, and sync handlers feed `autoEnqueue`.

1. **Step C1.** Create `backend/functions/src/summarizer/secrets.ts` exporting `SUMMARIZER_URL = defineSecret("SUMMARIZER_URL")`, `SUMMARIZER_API_KEY = defineSecret("SUMMARIZER_API_KEY")`, and `summarizerSecrets = [SUMMARIZER_URL, SUMMARIZER_API_KEY]`.
2. **Step C2.** Create `backend/functions/src/summarizer/dispatch.ts`. Export private helper `dispatchSummary(videoId: string): Promise<{summaryId: string}>` that runs the full state machine: read `videos/{videoId}` → idempotency check on `summaries/{videoId}` → quota pre-flight transaction (pessimistic-pre-increment) → reserve doc with `status=pending`, 32-byte hex `webhookSecret` via `crypto.randomBytes(32).toString("hex")` → `fetch(${SUMMARIZER_URL}/v1/jobs, {method:"POST", headers:{"X-API-Key":...}, body:JSON.stringify({...})})` → on 2xx: doc → `running` + `summarizerJobId`, return `{summaryId: videoId}`; on 4xx: doc → `failed-permanent`, fire decrement; on 5xx/network: doc → `failed-transient`, fire decrement.
3. **Step C3.** Export `requestVideoSummary = allowlistedCall<{videoId, model?}, {summaryId}>(async (data, ctx) => dispatchSummary(data.videoId, data.model ?? "free"))`. Bind `secrets: summarizerSecrets`.
4. **Step C4.** Create `backend/functions/src/summarizer/autoEnqueue.ts`. Export `enqueueAutoSummary(videoIds: string[]): Promise<{enqueued: number, skipped: number}>`. For each id, transactional read of `summaries/{videoId}`; if absent, write `{status: "queued", videoId, requestedAt: serverTimestamp(), model: "free", webhookSecret: ""}` (webhookSecret filled later when dispatcher promotes to pending). Batch into 500-op chunks.
5. **Step C5.** Modify `backend/functions/src/youtube/api-sync.ts`: change `syncRegularPlaylists` return type to `{playlistCount, videoCount, videoIds: string[]}` and `syncPlaylistById` return type to `{videoCount, videoIds: string[]}`. Collect IDs into a local array as videos are written.
6. **Step C6.** Modify `backend/functions/src/youtube/innertube-sync.ts`: extend `WatchLaterSyncResult` with `videoIds: string[]`. `flushCheckpoint` already iterates items; collect their `id` fields into an array passed back through the return chain.
7. **Step C7.** Modify `backend/functions/src/youtube/index.ts`: update `syncAll` return type to include the union of regular + WL `videoIds`. Optional: drop `videoIds` from the externally returned object to keep response payload small; pass a side-channel callback instead. Plan picks the simpler "return-as-part-of-result" approach; UI doesn't display it.
8. **Step C8.** Modify `backend/functions/src/index.ts`: after each successful `syncAll()` / `syncPlaylistById()` / `syncWatchLater()` call in the four entry points (`syncAllPlaylists`, `syncPlaylist`, `syncWatchLater`, `scheduledSync`), call `await enqueueAutoSummary(result.videoIds)`. Wrap in try/catch — auto-enqueue failures must not fail the sync.
9. **Step C9.** Register `requestVideoSummary` export at the bottom of `index.ts`: `export { requestVideoSummary } from "./summarizer/dispatch.js";`.
10. **Step C10.** Build + lint. Commit: `feat(backend): dispatch path + auto-enqueue from sync handlers`.

### Phase D — Webhook verifier

**Goal:** Inbound HMAC verification, idempotent doc update.

1. **Step D1.** Create `backend/functions/src/summarizer/webhook.ts`. Export `summaryWebhook = onRequest({secrets: [], memory: "256MiB", timeoutSeconds: 30}, async (req, res) => { ... })`. The handler:
   - Reject non-POST with 405.
   - Parse `X-Summarizer-Signature` via `header.split(",").reduce(...)` into `{t: number, v1: string}`. Reject missing/malformed → 400.
   - Compute `now = Math.floor(Date.now()/1000)`. If `Math.abs(now - t) > 300` → 401.
   - Read `req.rawBody.toString("utf8")` as `raw`. Parse `JSON.parse(raw)` into `{client_job_id, status, result, error}`. Reject malformed JSON → 400.
   - Read `summaries/{client_job_id}` doc. If missing → 404, no write.
   - Compute `expected = crypto.createHmac("sha256", doc.webhookSecret).update(`${t}.${raw}`).digest("hex")`. Length-check `v1` vs `expected`; if mismatch → 401. `crypto.timingSafeEqual(Buffer.from(v1,"hex"), Buffer.from(expected,"hex"))`; if false → 401.
   - Map `status` + `error.code` per the failure-mapping table above. If doc already in matching terminal state → return 204 no-op (idempotency).
   - Transactionally update doc with new terminal state, `content` (if completed), `completedAt: serverTimestamp()`. Return 204.
2. **Step D2.** Add structured logging via `firebase-functions/logger`. Log `{client_job_id, status, signatureValid, replayWindowOk}` on every request. NEVER log `webhookSecret` or the raw signature.
3. **Step D3.** Register `summaryWebhook` export in `index.ts`.
4. **Step D4.** Build + lint. Commit: `feat(backend): summary webhook with Stripe-style HMAC verification`.

### Phase E — Dispatcher cron

**Goal:** Drain queued summaries within quota every 5 minutes; lock prevents overlap.

1. **Step E1.** Create `backend/functions/src/summarizer/dispatcher.ts`. Export `acquireDispatcherLock(): Promise<boolean>` — transactional check on `locks/summaryDispatcher`: if doc absent or `now - acquiredAt > 240_000` → write `{acquiredAt: Date.now(), ttlSeconds: 240}` and return true; else return false.
2. **Step E2.** Export `releaseDispatcherLock(): Promise<void>` — delete (or set `acquiredAt: 0`) the lock doc. Best-effort.
3. **Step E3.** Export `summaryDispatcher = onSchedule({schedule: "every 5 minutes", maxInstances: 1, memory: "256MiB", timeoutSeconds: 540, secrets: summarizerSecrets}, async () => { ... })`. Handler:
   - If `!acquireDispatcherLock()` → return.
   - Try/finally so `releaseDispatcherLock()` always runs.
   - Read `quota/openrouter` (non-transactional read for budget calculation). Compute `remaining = min(perMinuteLimit - recentTimestamps.length, dailyLimit - requestCount)`. If `remaining <= 0` → return.
   - Query `summaries` `where status == "queued" order by requestedAt asc limit remaining`.
   - For each doc, invoke `dispatchSummary(doc.videoId)`. Each call has its own quota transaction (so the dispatcher loop respects mid-run cap exhaustion). On `resource-exhausted` from quota → break the loop. On other errors → log and continue.
4. **Step E4.** Register `summaryDispatcher` export in `index.ts`.
5. **Step E5.** Build + lint. Commit: `feat(backend): scheduled summary dispatcher with lock`.

### Phase F — Emulator tests + harness

**Goal:** Slice-local + AC-mapped tests run green under `pnpm --filter functions test`. This phase is the longest and the highest-value verification surface.

1. **Step F1.** Verify slice 1 has already added `vitest`, `@firebase/rules-unit-testing` to `backend/functions/package.json` devDependencies. If absent (slice 1 deferred), add here. **Cohesion note:** slice 1 owns the initial `vitest.config.ts` + `test/helpers/setup.ts` scaffolding for its `rules.test.ts` + `callable.test.ts`; this slice consumes that scaffolding rather than overwriting it.
2. **Step F2.** Verify `backend/functions/vitest.config.ts` exists from slice 1. If `pool: "forks"` is not already configured, amend (Firebase admin SDK doesn't play nice with vitest's default thread pool). Do NOT re-create the file from scratch.
3. **Step F3.** Create or extend `backend/functions/test/helpers/globalSetup.ts`. Spawn Firebase emulator (firestore + functions + auth) on a known port via child_process; wait for `/emulator/v1/projects/demo-playster/databases/(default)/documents` to respond. Tear down on `globalTeardown`. **Cohesion note:** slice 1's `setup.ts` uses a simpler `initializeTestEnvironment` model for rules-unit-testing; this slice introduces the more elaborate emulator-suite globalSetup pattern needed for HTTPS/onSchedule trigger testing. If slice 1's `setup.ts` exists, factor shared concerns into `helpers/`.
4. **Step F4.** Create `backend/functions/test/helpers/signWebhook.ts`. Exports `signWebhook(payload: object, secret: string, t?: number): {body: string, signature: string}` mirroring slice 2's signer exactly (compare to `summarizer/summarize-api/src/runners/url-runner.ts` after slice 2 lands).
5. **Step F5.** Create `backend/functions/test/helpers/emulator.ts`. Helpers: `seedSummary(videoId, fields)`, `seedQuota(fields)`, `seedVideo(videoId)`, `getSummary(videoId)`, `getQuota()`, `clearAll()`.
6. **Step F6.** Write `test/summarizer/quota.test.ts`. Covers slice-local ACs: day-rollover atomicity, concurrent-transaction cap enforcement, decrement-on-failure.
7. **Step F7.** Write `test/summarizer/dispatch.test.ts`. Mocks `global.fetch` via `vi.stubGlobal`. Cases: happy path → doc transitions pending → running, idempotency (existing completed doc → no dispatch), 4xx response → failed-permanent + decrement called, 5xx response → failed-transient + decrement called.
8. **Step F8.** Write `test/summarizer/webhook.test.ts`. Covers AC-7, AC-8, AC-9. Uses `signWebhook` helper. Cases: valid → completed, unknown id → 404, stale → 401, bad signature → 401, length-mismatch signature → 401, idempotent replay → 204 no-op.
9. **Step F9.** Write `test/summarizer/autoEnqueue.test.ts`. Cases: empty input → no-op, fresh ids → all enqueued, mixed (some existing summaries) → only new enqueued, idempotent on second call.
10. **Step F10.** Write `test/summarizer/dispatcher.test.ts`. Covers AC-11 + slice-local "lock prevents overlap". Cases: queue drains to per-minute cap, daily cap blocks dispatch, two concurrent invocations → only one drains, lock TTL expiry allows reclaim.
11. **Step F11.** Write `test/summarizer/rules.test.ts`. Covers AC-15 extension. Allowlisted uid reads summaries / quota → allowed. Stranger uid → denied. Any uid write → denied. Uses `@firebase/rules-unit-testing#initializeTestEnvironment` loading `backend/firestore.rules`.
12. **Step F12.** Add `"test": "vitest run"` to `backend/functions/package.json` scripts. Run `pnpm --filter functions test`; iterate until green.
13. **Step F13.** Commit: `test(backend): emulator suite for summary orchestration ACs`.

## Risk Register (slice-specific, beyond the slice doc)

- **Slice 1 not yet merged when this slice's PR opens.** Mitigation: rebase on slice 1's branch; CI runs against the integration branch. If slice 1 misses a feature this slice needs (`allowlistedCall` shape), surface as a discovery blocker.
- **Slice 2's webhook payload schema drifts.** Mitigation: paired fixture in `signWebhook.test.ts`. CI cross-runs slice 2's signer fixtures against this slice's verifier.
- **`firebase-functions ^7` upgrade (owned by slice 1) breaks `onRequest`'s `rawBody`.** Mitigation: web research confirmed `rawBody: Buffer` is stable in v2 API regardless of `firebase-functions` package version. Slice 3 adds a smoke test that asserts `typeof req.rawBody !== "undefined"` in `webhook.test.ts`.
- **Emulator port conflicts in CI.** Mitigation: `globalSetup` reads `FIREBASE_EMULATOR_PORT_FIRESTORE` env var with a default; CI configures explicit ports.

## Definition of Done

- Phases A–F all committed on `feat/wire-android-backend-summarizer`.
- `pnpm --filter functions build` green.
- `pnpm --filter functions lint` green.
- `pnpm --filter functions test` green; coverage includes all five slice-mapped ACs (AC-7, AC-8, AC-9, AC-11, AC-15-extension) and all four slice-local ACs.
- `backend/firestore.rules` extended; rules-unit-test demonstrates `summaries/` and `quota/` deny strangers and allow the allowlisted uid.
- `SUMMARIZER_URL` and `SUMMARIZER_API_KEY` secrets defined (operator must provision them in Secret Manager pre-deploy — out of scope for this slice; documented as a prerequisite in `docs/operations/deploy-and-bootstrap.md`).
- All four sync entry points (`syncAllPlaylists`, `syncPlaylist`, `syncWatchLater`, `scheduledSync`) call `enqueueAutoSummary` on success; auto-enqueue failures do not fail the sync.
- The deferred ACs (AC-12 sweeper, AC-13 retry cron) are explicitly tracked as v1.1 candidates; this plan does not implement them.

## Revision History

**2026-05-18T07:49:39Z — Discovery phase + cohesion reconciliation (initial plan write):**
- Discovery phase locked: **pessimistic pre-increment** for quota (PO confirmed default) — `dispatchSummary` increments transactionally before HTTP, fires best-effort decrement on dispatch failure. Aligns with Phase B + Phase C as written.
- Discovery phase locked: **transactional read-then-`set`** for `summaries/{videoId}` writes (PO confirmed default). All paths (manual `requestVideoSummary`, auto-enqueue, dispatcher promotion) use the same idempotent read-then-set pattern.
- Discovery phase locked: **batched auto-enqueue at end of sync** (PO confirmed default). Phase C steps C5–C8 modify sync helpers to return `videoIds: string[]`; `enqueueAutoSummary(videoIds)` is called once per sync invocation, not per video.
- Cohesion fix: Phase F step F2 amended — `vitest.config.ts` is owned by slice 1 (which lands first). This slice extends configuration rather than re-creating the file.
- Daemon error-code vocabulary (Playbook B failure-mapping table) confirmed at slice 2 implement time per PO direction; this plan's table is the planning-stage best estimate. If slice 2's error codes differ, the table is updated before Phase D landings (`signWebhook.ts` test fixtures use the confirmed names).

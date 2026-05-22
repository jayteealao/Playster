---
schema: sdlc/v1
type: plan-index
slug: wire-android-backend-summarizer
status: complete
stage-number: 4
created-at: "2026-05-18T07:49:39Z"
updated-at: "2026-05-22T21:01:56Z"
planning-mode: all
slices-planned: 5
slices-total: 5
implementation-order:
  - auth-and-android-firebase
  - summarizer-container
  - summary-orchestration
  - summary-ui
  - failure-recovery-cron
extension-rounds: 1
conflicts-found: 2
tags: [android, firebase, cloud-run, summarizer, openrouter, multi-component]
refs:
  index: 00-index.md
  slice-index: 03-slice.md
next-command: wf-implement
next-invocation: "/wf implement wire-android-backend-summarizer auth-and-android-firebase"
---

# Plan Index — wire-android-backend-summarizer

All four slices are planned. Discovery phase is closed; per-slice plans are execution-ready. Cohesion check surfaced two minor file-ownership conflicts (both resolved by editing the dependent plan, not the upstream owner). Implementation order falls out naturally from the dependency graph: slices 1 and 2 are independent roots; slice 3 needs both; slice 4 needs slice 3.

## Slice Plan Summaries

### `auth-and-android-firebase` ([plan](04-plan-auth-and-android-firebase.md))

- **Complexity / deps:** L / no upstream deps. Root slice.
- **Files to touch:** 18 (10 backend, 13 Android — some overlap counted once). Step count: 24.
- **Strategy:** Atomic flip of the backend `onRequest` sync endpoints to `allowlistedCall` callables, simultaneous Android rewrite from device-side YouTube Data API to Firestore live view bridged via Firebase Auth. `firebase-functions` bumps `^6.3 → ^7.2.5`. Two-pass deploy resolves the bootstrap chicken-and-egg.
- **Phases:** A (backend prep) → B (backend lockdown + rules) → C (Android Firebase wiring) → D (Android YouTube-API cleanup) → E (bootstrap + verification).
- **Maps to ACs:** AC-1, AC-2, AC-3, AC-4, AC-15 (partial). Slice-local: `^7` does not regress `scheduledSync`.
- **Key risk:** Atomic-flip PR size (both subprojects in one PR). Mitigation: commit boundaries map to Phase A–E.
- **Surprises during research:** `firebase-functions ^6.6` + `firebase-admin ^13` actually co-install today (the "peer conflict" framing was overstated); Credential Manager scaffolding already exists in `AuthScreen.kt` as dead code; the three `onRequest` sync functions have zero external callers anywhere in the repo (safe to swap without a compat shim).

### `summarizer-container` ([plan](04-plan-summarizer-container.md))

- **Complexity / deps:** L / no upstream deps. Parallelizable with slice 1.
- **Files to touch:** 16 (4 new, 3 deleted, 9 modified). Step count: 38.
- **Strategy:** Single Docker image runs both the Fastify `summarize-api` gateway and the vendored `summarize-daemon` (steipete/summarize subtree at `0ec12ac`). Multi-stage `node:24-slim` build, daemon on 127.0.0.1:8787, gateway on 0.0.0.0:$PORT. Stripe-style HMAC-signed webhooks on terminal SSE events. yt-dlp static-binary install (PEP-668 sidestep). Cloud Build for image push.
- **Phases:** A (subtree pin) → B (Dockerfile + entrypoint) → C (schema + migration) → D (webhook signing in url-runner) → E (docker-compose harness + fixtures) → F (Cloud Run runbook).
- **Maps to ACs:** AC-6, AC-14, AC-16. Slice-local: Node 24 build, yt-dlp version floor, subtree pin matches HEAD, replay attack rejected.
- **Key risk:** Subtree contract drift. Mitigation: Phase E harness gates every subtree pull.
- **Surprises during research:** Q1/Q2 daemon CLI open questions resolved directly from source (`daemon run --token <T> --port 8787`). Legacy `summarizer/deploy/daemon/Dockerfile` includes a `sed` patch flipping `127.0.0.1 → 0.0.0.0` that is **wrong** for our single-container architecture (deleted in this slice). pnpm version mismatch (api 10.25.0 vs daemon 10.33.2) unified on 10.33.2 across both build stages; api `pnpm-lock.yaml` regenerated.

### `summary-orchestration` ([plan](04-plan-summary-orchestration.md))

- **Complexity / deps:** M / depends on slices 1 (`allowlistedCall`, rules base) and 2 (signer byte-contract).
- **Files to touch:** 13 (5 new src + 1 barrel + 3 existing src modified + rules + models + package.json + vitest scaffolding extension). Step count: 42.
- **Strategy:** New `backend/functions/src/summarizer/` directory: `dispatch.ts` (requestVideoSummary callable + reusable helper), `webhook.ts` (Stripe-style HMAC verifier reading `req.rawBody`), `quota.ts` (transactional sliding-window quota with pessimistic pre-increment), `autoEnqueue.ts` (batched at end of sync), `dispatcher.ts` (5-minute onSchedule cron with `locks/summaryDispatcher` TTL). Inject `enqueueAutoSummary` into the four sync entry points after modifying `syncPlaylistById`/`syncRegularPlaylists`/`flushCheckpoint` to return `videoIds: string[]`.
- **Phases:** A (models + rules) → B (quota) → C (dispatch + auto-enqueue) → D (webhook verifier) → E (dispatcher cron) → F (emulator tests).
- **Maps to ACs:** AC-5 (partial — callable latency), AC-7, AC-8, AC-9, AC-11, AC-15 (extension). Slice-local: auto-enqueue idempotency, dispatcher lock prevents overlap, day-rollover atomic, quota transaction atomic under contention.
- **Deferred to v1.1:** AC-12 (stuck-job sweeper), AC-13 (failed-transient retry cron).
- **Key risk:** Byte-equivalence of HMAC across signer (slice 2) and verifier (this slice). Mitigation: shared fixture vector in `test/helpers/signWebhook.ts` cross-checks against slice 2's signer.
- **Surprises during research:** `syncPlaylistById` (`youtube/api-sync.ts:162`) and `syncRegularPlaylists` (`youtube/api-sync.ts:80`) currently return only `{videoCount}` — they write videos but discard IDs. Plan modifies both signatures (and `WatchLaterSyncResult` in `innertube-sync.ts:138`) to also return `videoIds: string[]` so auto-enqueue runs without a Firestore re-read. Existing `firestore.rules` is a placeholder (`allow read, write: if false`) — slice 1 replaces; this slice extends additively. No vitest config or backend tests in the repo today; slice 1 introduces the harness, this slice extends it.

### `summary-ui` ([plan](04-plan-summary-ui.md))

- **Complexity / deps:** M / depends on slice 3 (callable + Firestore docs) + slice 1 (Firebase BOM, listener pattern).
- **Files to touch:** 22 (12 new Kotlin, 4 new Maestro/scripts, 1 new instrumented test, 5 modifications). Step count: 38 across 7 phases.
- **Strategy:** New `VideoDetailScreen` (tabbed) → `SummaryScreen` with sealed `SummaryUiState` (5 variants: `InProgress`, `Completed`, `FailedTransient`, `FailedPermanent`, `NoSummary`); `queued`/`pending`/`running` all map to `InProgress`. Markdown rendering via `dev.jeziellago:compose-markdown:0.5.7`. Nav-arg `?autoDispatch=true|false` lets tile-Summarize auto-dispatch while plain tile-tap shows an explicit "Summarize this video" button. `QuotaBanner` observes `quota/openrouter` and disables Summarize CTAs when capped.
- **Phases:** A (DTOs + markdown lib) → B (QuotaBanner + repositories) → C (VideoDetailScreen scaffold + nav route) → D (SummaryScreen state machine + ViewModel) → E (PlaylistScreen integration) → F (Maestro flows + Compose UI tests) → G (evidence + handoff).
- **Maps to ACs:** AC-5 (visible-side 500ms timing), AC-10 (banner + disabled CTA). Slice-local: 4 states render distinctly (Compose UI tests, no Paparazzi), Retry transitions within 500ms, listener doesn't leak, cached-summary navigation skips redispatch.
- **Key risk:** Phase E depends on slice 1's `PlaylistScreen.kt` rewrite landing. Escalate if not.
- **Surprises during research:** Codebase has no existing sealed UI-state classes, no markdown rendering, no Maestro directory, no Hilt provider modules, no instrumented tests — slice 4 establishes several "firsts" in conjunction with slice 1. Compose BOM `2024.01.00` + navigation-compose `2.7.6` compatible with `compose-markdown 0.5.7` without bumps. Firestore listener for `SummaryViewModel` needs a `localFailure: StateFlow<SummaryUiState?>` override pattern for the case where the callable fails *before* a doc gets reserved (listener won't emit because no doc exists).

### `failure-recovery-cron` ([plan](04-plan-failure-recovery-cron.md)) — Extension Round 1 (from-review)

- **Complexity / deps:** S / depends on `summary-orchestration` only (server-side; no Android surface). **Parallel branch** — does NOT gate v1.0 handoff/ship.
- **Source:** `07-review.md` finding R-12 (sourced from `07-review-testing.md` TST-3); shape doc's *Failure & recovery* rules 14–16. Re-introduces the deferred slices listed under `## Deferred / Optional Slices` in `03-slice.md` as `v1.1-failure-recovery-cron`.
- **Files to touch:** 6 (4 new src/test, 2 modified). Step count: 22 across 5 phases.
- **Strategy:** Two `onSchedule` functions in `backend/functions/src/summarizer/`. `summarySweeper` (hourly) flips `status: "running"` docs older than 1h to `failed-transient` via per-doc status-gated transactions; pure Firestore, no outbound HTTP. `summaryRetryCron` (daily, 04:00 UTC, explicit `timeZone: "UTC"`) queries `failed-transient` docs and calls existing `dispatchSummary(videoId, "free")` via `Promise.allSettled` — full reuse of the dispatcher's quota + webhook-secret-rotation path. Distributed locks at `locks/summarySweeper` and `locks/summaryRetry` mirror `dispatcher-cron.ts` (240s TTL = `DISPATCHER_LOCK_TTL_MS`). Retry uses `retryConfig: { retryCount: 3, minBackoffDuration: "60s" }`; sweeper uses `{ retryCount: 1 }`. Per-invocation cap = `DISPATCHER_BATCH_SIZE` on retry; sweeper is uncapped (bounded by 540s function timeout).
- **Phases:** A (constants + sweeper module) → B (sweeper tests) → C (retry module) → D (retry tests) → E (wire exports).
- **Maps to ACs:** AC-12 (carried from shape), AC-13 (carried from shape). Slice-local extensions: AC-14 (sweeper idempotency / zero-write second pass), AC-15 (retry quota awareness mid-batch), AC-16 (lock TTL reclaim for both crons).
- **Key risk:** Parallel branch base must be the post-review HEAD of `feat/wire-android-backend-summarizer` (post-`f94a7691`) to inherit the `webhook_secrets/{videoId}` migration — not `main` until v1.0 ships. Mitigation documented in the per-slice plan.
- **Surprises during research:** Cloud Scheduler `timeZone` defaults to `America/Los_Angeles`, not UTC — daily cron would drift by 7h + DST without explicit `timeZone: "UTC"`. Default `retryCount=0` means a thrown invocation skips to the next 24h firing. `maxInstances: 1` alone does NOT prevent overlap (needs `concurrency: 1` too) — the distributed lock remains the canonical mechanism. `dispatchSummary`'s `NON_REDISPATCHABLE_STATUSES` set deliberately excludes `failed-transient`, so retry can call it directly without any branching logic.

## Cross-Cutting Concerns

### 1. Firebase Auth ID-token contract

Slice 1 establishes the bridge: Google Sign-In via Credential Manager → `GoogleIdTokenCredential.idToken` → `GoogleAuthProvider.getCredential(idToken, null)` → `FirebaseAuth.getInstance().signInWithCredential(...)`. Slice 4 inherits `FirebaseAuth.getInstance().currentUser` for all `getHttpsCallable` calls. The hardcoded server client ID `510333739373-ust5kheckkg2oiuoghp08l5ghm1fsmat...` in `AuthScreen.kt` is tied to the existing Firebase project `playster-406121` and stays valid for Credential Manager bridging.

### 2. Firestore document shapes

`PlaylistDocument` + `VideoDocument` already exist in `backend/functions/src/models/index.ts`. Slice 3 appends `SummaryDocument` + `QuotaDocument`. Slice 4 hand-mirrors them as Kotlin DTOs (`PlaylistDoc` / `VideoDoc` / `SummaryDoc` / `QuotaDoc`). The status enum (`queued | pending | running | completed | failed-transient | failed-permanent`) is the canonical state machine surface — slice 3 writes the values; slice 4's `SummaryUiState` maps them deterministically.

### 3. Stripe-style HMAC contract (slice 2 ↔ slice 3)

**This is the highest-risk integration point.** Signer (slice 2) constructs `canonicalBytes = ${t}.${rawBody}` where `rawBody = JSON.stringify(payload)` (computed **once**, used both for HMAC and POST body). Verifier (slice 3) reads `req.rawBody.toString("utf8")` — NOT `JSON.stringify(req.body)`, which whitespace-differs from upstream output. Both sides use `crypto.timingSafeEqual` on equal-length buffers; 300s replay window. A shared fixture vector (`backend/functions/test/helpers/signWebhook.ts` mirroring `summarizer/summarize-api/src/webhooks/signer.ts`) cross-checks byte-equivalence; CI-equivalent test in slice 3 reuses this fixture.

### 4. Subtree pin discipline

`summarizer/summarize-daemon/SUBTREE_PIN.md` records the pinned SHA + date + verification checklist. Refresh cadence: **pin-on-incident** (CVE in tree, upstream feature wanted, or 6-month hygiene). Each pull runs through slice 2's docker-compose harness before merging. Cross-references the daemon contract surfaces (Bearer auth, `/v1/summarize` body schema with the eight forward keys, `/v1/refresh-free` route).

### 5. Quota state semantics

`quota/openrouter` is a singleton Firestore doc updated transactionally on every dispatch. Day-rollover (UTC midnight) resets `requestCount=0`, `recentTimestamps=[]` in the same transaction. **Pessimistic pre-increment** strategy: the transaction increments before the HTTP dispatch; failed dispatch fires a best-effort decrement (worst case over-counts by 1–2 on rare crash). Daily limit: 1000 (post one-time $10 OpenRouter credit purchase). Per-minute limit: 20 (60s sliding window of last 20 timestamps). Slice 3 owns the doc; slice 4 observes for the banner/disabled-CTA UX.

### 6. Operator bootstrap procedure

**Placeholder rules + 2-pass deploy.** Initial deploy: `firestore.rules` contains sentinel `__BOOTSTRAP_UID__`, `ALLOWED_UID` config = `"__BOOTSTRAP_UID__"`. All callables deny — intentional. Operator installs the new Android APK and signs in once via Credential Manager → Firebase Auth uid created and visible in Firebase console → operator captures it → replaces sentinel in `firestore.rules` + sets real `ALLOWED_UID` via `firebase functions:secrets:set` or env-file param → redeploys rules + functions. Single runbook at `docs/operations/bootstrap-allowlisted-uid.md` (slice 1 writes). Same runbook will accumulate slice 2's Cloud Run deploy steps and slice 3's `SUMMARIZER_URL` / `SUMMARIZER_API_KEY` secret provisioning.

### 7. OpenRouter $10 credit prerequisite

Operator-side MVP prerequisite. Without it, free-tier quota is the much-tighter ~50/day. The 1000/day code constant (`quota/openrouter.dailyLimit`) assumes the credit is in place. Documented in slice 2's deploy runbook + slice 3's plan as a hard precondition.

### 8. Verification tooling (from `stack:`, PO-confirmed)

- **Backend:** vitest + `@firebase/rules-unit-testing` + Firebase emulator suite. Slice 1 introduces the scaffolding (`vitest.config.ts`, `tests/setup.ts`); slice 3 extends it.
- **Android:** Maestro + `lazylogcat` + Compose UI tests (Paparazzi NOT adopted — accepted default).
- **Summarizer container:** vitest unit tests + `summarizer/deploy/docker-compose.yml` harness with `mock-backend` signature-verifier service.
- **No new tooling introduced** anywhere — every verification path uses items already in `stack:`.

## Integration Points Between Slices

| Producer | Consumer | Contract / surface | Risk |
|----------|----------|--------------------|------|
| Slice 1 (`auth/verify.ts#allowlistedCall`) | Slice 3 (`dispatch.ts`, all callables in `summarizer/`) | Function signature: `allowlistedCall<TIn, TOut>(opts, handler)` | Low — slice 1 lands first; signature stable |
| Slice 1 (`firestore.rules`) | Slice 3 (extends `summaries/`, `quota/`) | Append-only — slice 3's rules block inserts before slice 1's deny-all catchall | Medium — slice 3 must verify catchall doesn't shadow |
| Slice 1 (`backend/firebase.json` emulators block) | Slice 3 (vitest emulator-suite tests) | Standard ports 9099 (auth), 8080 (firestore), 5001 (functions) | Low |
| Slice 1 (`vitest.config.ts` + `test/helpers/setup.ts`) | Slice 3 (extends with `globalSetup.ts` + emulator helpers) | **Cohesion fix:** slice 3 amends, not creates | **Conflict resolved** |
| Slice 1 (`FirebaseAuthBridge` + `FirestoreRepository` patterns) | Slice 4 (`SummaryRepository`, `QuotaRepository` follow pattern) | Convention: `callbackFlow + awaitClose + viewModelScope` | Low |
| Slice 1 (Firebase BOM 34+, no-suffix modules) | Slice 4 (uses same artifacts) | **Cohesion fix:** slice 4 corrected `*-ktx` references to no-suffix | **Conflict resolved** |
| Slice 1 (`screens/common/QuotaBanner.kt` placeholder) | Slice 4 (replaces with real implementation observing `quota/openrouter`) | Same file path, no API surface (Composable signature changes) | Low |
| Slice 1 (Android Maestro fixture-auth) | Slice 4 (`helpers/signin-helper.yaml` extract) | Slice 4 creates the reusable extract; both flows share fixture mechanism | Low |
| Slice 2 (`summarize-api` schema additions) | Slice 3 (dispatch request body) | `webhook_url`, `webhook_secret`, `client_job_id` fields | Low — schema lands before dispatch |
| Slice 2 (`url-runner.ts` HMAC signer) | Slice 3 (`webhook.ts` HMAC verifier) | **Byte-exact canonical bytes** `${t}.${rawBody}` | **HIGH** — paired test vectors mandatory |
| Slice 2 (Cloud Run URL) | Slice 3 (`SUMMARIZER_URL` secret) | Secret Manager value | Low — operator-provisioned |
| Slice 2 (daemon error vocabulary) | Slice 3 (failure-mapping table) | `quota_exhausted`, `unrecoverable`, `transcript_impossible` → failed-permanent; else failed-transient | Medium — names confirmed at slice 2 implement time |
| Slice 3 (`requestVideoSummary` callable) | Slice 4 (`SummaryFunctions.kt`) | Input `{videoId, model?}`, output `{summaryId}`, error codes per `FirebaseFunctionsException` | Low — strongly typed |
| Slice 3 (`summaries/{videoId}` docs) | Slice 4 (`SummaryRepository`) | `SummaryDocument` shape mirrored as Kotlin `SummaryDoc` | Low |
| Slice 3 (`quota/openrouter` doc) | Slice 4 (`QuotaRepository`, `QuotaBanner`) | `QuotaDocument` shape, sliding-window semantics | Low |

## Recommended Implementation Order

1. **`auth-and-android-firebase` first.** Root slice with no upstream deps, establishes auth + Firestore listener patterns that every downstream slice consumes. **Compact recommended before starting** — see Recommended Next Stage.
2. **`summarizer-container` in parallel.** Independent slice. Owns the deploy artifact + HMAC signer that slice 3 binds to. Can be developed by a separate worktree / branch; both merge to `feat/wire-android-backend-summarizer` and CI cross-validates the signer/verifier byte vectors.
3. **`summary-orchestration` third.** Hard-blocked on slices 1 (allowlistedCall) and 2 (signer schema). Lands the entire `backend/functions/src/summarizer/` directory plus the rules + sync-handler injection.
4. **`summary-ui` last.** Hard-blocked on slice 3 (callable + Firestore docs) and slice 1 (Firebase BOM, PlaylistScreen rewrite, Maestro fixture-auth). Visible head of the v1 demo flow.

Slices 1 + 2 can ship as separate PRs to the integration branch; slice 3's PR rebases on both; slice 4's PR rebases on slice 3. Alternatively (and recommended for a single-operator workflow), commit them in series on `feat/wire-android-backend-summarizer` and open a single PR at handoff stage — matches the workflow's `branch-strategy: dedicated`, `review-scope: slug-wide` settings.

### Extension slice — parallel track

5. **`failure-recovery-cron` (Extension Round 1).** Server-side only; depends solely on `summary-orchestration` and the post-review `webhook_secrets/{videoId}` migration. Implements on its own parallel branch off `feat/wire-android-backend-summarizer` HEAD (or off `main` after v1.0 ships). Does NOT gate v1.0 handoff/ship; a separate PR cuts after v1.0 merges. Implementation can start immediately once the parent branch is review-clean.

## Conflicts Found

The cohesion check across the four per-slice plans surfaced **2 conflicts**, both file-ownership ambiguities. Both resolved by editing the dependent plan (slice 3 + slice 4) to defer to the upstream owner (slice 1), with revision-history notes recording the fix. No conflicts required revisiting `03-slice.md`.

1. **`backend/functions/vitest.config.ts`** — slice 1 and slice 3 both originally said "create from scratch". **Resolution:** slice 1 owns the initial file (its rules + callable tests need it first). Slice 3 amends (e.g., adds `pool: "forks"` if not already present) and contributes `test/helpers/globalSetup.ts`. Recorded in slice 3's revision history.
2. **Firebase Android `*-ktx` module naming** — slice 4 used legacy `firebase-firestore-ktx` / `firebase-functions-ktx` / `firebase-auth-ktx` artifact names; slice 1's research surfaced that Firebase Android BOM 34+ (Jul 2025) rolled KTX into the main modules, so the correct artifact names have no `-ktx` suffix. **Resolution:** slice 4's references corrected to no-suffix names. Recorded in slice 4's revision history.

Additionally, slice 4 references a `signin-helper.yaml` Maestro flow it expected slice 1 to produce — slice 1's plan actually produces `signin-and-see-playlists.yaml` (not a reusable extract). **Resolution:** slice 4 now creates `android/maestro/helpers/signin-helper.yaml` as an extract during Phase F; both flows reuse the same fixture-auth mechanism. Not a hard conflict — just an additional ~10 lines in slice 4's Phase F.

## Discovery Phase Summary (2026-05-18)

**Round 1 — infrastructure (4 PO answers):**
- Bootstrap procedure: **placeholder rules + 2-pass deploy** ✓
- `firebase-functions ^7` bump: **push through** even on friction ✓
- yt-dlp install: **static binary from GitHub Releases** ✓
- Cloud Run `min-instances`: **0** (pay-per-request) ✓

**Round 2 — orchestration + UI (3 PO answers + consolidated defaults):**
- Quota increment strategy: **pessimistic pre-increment + best-effort decrement** ✓
- Markdown lib: **`dev.jeziellago:compose-markdown:0.5.7`** ✓
- Auto-dispatch routing: **nav-arg `?autoDispatch=true|false`** ✓
- All other recommended defaults accepted (Credential Manager scoped to legacy-GoogleSignIn removal; `google-services.json` gitignored; Cloud Build for image push; subtree pin-on-incident; Compose UI tests over Paparazzi; tile "Summary ready" badge deferred to v1.1; transactional read-then-`set` for summaries/{id}; auto-enqueue batched at end of sync; daemon error codes confirmed at slice-2 implement time) ✓

Full answers appended to `po-answers.md` Stage 4.

## Freshness Research (Aggregated)

Each per-slice plan has its own `## Freshness Research` section with citations. Aggregated highlights:

- **`firebase-functions` v7.2.5** (Apr 2026) — chosen target. Only meaningful break is removal of `functions.config()` (unused). Pin `^7.2.5` because v7.0.0 had a `SecretParam` export bug.
- **Firebase Android BOM 34+** (Jul 2025) — KTX modules rolled into main artifacts; no `-ktx` suffix.
- **Android Credential Manager + Sign in with Google** — standard 2026 pattern. Existing `AuthScreen.kt` scaffolding completed.
- **`@firebase/rules-unit-testing` v5** — `initializeTestEnvironment` + `assertFails` / `assertSucceeds`. Stable.
- **steipete/summarize at `0ec12ac`** (or current main HEAD) — includes PRs #226 (timing-safe Bearer compare) + #227 (failed-auth rate-limit). `daemon run --token <T> --port 8787` is the verified foreground invocation form.
- **yt-dlp `2026.02.21`** — CVE-2026-26331 fixed. Static binary download from GitHub Releases.
- **Cloud Run gen2** required for our memory profile + child-process spawn semantics. Buildpacks rejected (no system-deps).
- **Stripe webhook signing v1** — canonical bytes = `${unix_timestamp}.${raw_body}`. `crypto.timingSafeEqual` on hex-decoded buffers. 300s replay window.
- **`req.rawBody` in Firebase Functions v2 `onRequest`** — always exposed as `Buffer`, no special config needed.
- **`dev.jeziellago:compose-markdown:0.5.7`** — Markwon-backed bridge, active 2026, mature CommonMark + tables + code + images. ~600KB APK impact.
- **Debian Trixie PEP-668** — system Python is externally-managed; static-binary yt-dlp is the clean path.

## Recommended Next Stage

- **Option A (default):** `/wf implement wire-android-backend-summarizer auth-and-android-firebase` — start with the root slice. Plan is execution-ready, discovery is closed, dependency graph is satisfied. **Compact recommended before proceeding** — planning research is noise for implementation. The PreCompact hook preserves workflow state, so `/compact` first then `/wf implement` is the cleanest sequence.

- **Option B (parallel-track):** start two simultaneous worktrees — one on `auth-and-android-firebase`, one on `summarizer-container`. Slices 1 and 2 share no source files. Merge both to `feat/wire-android-backend-summarizer` before invoking `/wf plan` review-mode or `/wf implement summary-orchestration`. Useful only if there's a dual-operator pair; single-operator should just go serial.

- **Option C:** `/wf slice wire-android-backend-summarizer` — only if the cohesion conflicts or discovery answers reshape the slice boundaries. Both conflicts here were minor (file-ownership) and resolved inline; no slice-boundary changes needed.

- **Option D:** `/wf shape wire-android-backend-summarizer` — not recommended. Plans confirmed the shape is implementable as written. Q1/Q2 resolved by source inspection; one minor framing correction (peer conflict overstated) doesn't change scope.

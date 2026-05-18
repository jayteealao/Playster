# Product Owner Answers — wire-android-backend-summarizer

Cumulative log of PO answers across stages. Append-only.

---

## Stage 1 — intake (2026-05-17T15:00:08Z)

### Batch A (structured)

- **Branch strategy:** Dedicated. Branch `feat/wire-android-backend-summarizer`, base `main`.
- **Appetite:** Large. Multi-day, multi-phase initiative; expects slicing in stage 3.
- **Review scope:** Slug-wide. Single `07-review.md` against the cumulative branch diff.

### Batch B (freeform)

- **Stack confirmation:** Detected stack accepted with additions. PO confirmed the following CLIs/tooling are available in this session and relevant to the task: `android`, `gcloud`, `firebase`, `lazylogcat`, `maestro`. No corrections to detected platforms/languages/UI/build/testing/integrations.

- **Outcome / beneficiary:** Confirmed. Operator (sole user) gets a unified personal YouTube content app: Android renders Firestore-synced playlists/videos and can request LLM summaries; backend (Firebase Functions) orchestrates the summarizer and protects API keys; OpenRouter free models hold LLM cost at zero.

- **Success criteria (proposed phase-by-phase acceptance):** PO declined to lock these into the intake brief. Treat them as draft acceptance to be refined in shape, not committed.

- **Non-goals:** Confirmed.
  - Multi-tenant / per-user Firestore keying (locked to single-tenant).
  - Slides extraction (Phase 7, stretch only — not MVP).
  - Cache persistence across Cloud Run cold starts (accept loss in MVP).
  - Removing the legacy `setCookies` HTTP endpoint until Phase 6 cleanup.
  - Holding SSE connections in the backend (webhook-only).

- **Already-decided constraints:** Accepted as-is (no changes requested).
  - Single-tenant; Firebase Auth `ALLOWED_UID` allowlist.
  - OpenRouter free models (model name `"free"` to use daemon's rotating list).
  - Single Docker container with daemon on `127.0.0.1:8787` and summarize-api facing public ingress.
  - Webhook with HMAC-SHA256 signature (per-summary secret) over SSE for backend integration.
  - `summarizer/summarize-daemon/` vendored from `github.com/steipete/summarize` as `git subtree --squash`; pinned commits, manual pulls.
  - Node 24+ in the container; backend functions stays on Node 22.
  - Singleton Firestore docs (no per-user paths).

- **Open questions carried into shape:**
  1. Exact daemon CLI invocation for foreground container process (verify against `summarizer/summarize-daemon/src/daemon/cli.ts`).
  2. Daemon config-file vs CLI token — is `--token` the right knob?
  3. Cloud Run cold-start tolerance — accept boot + refresh-free on first hit vs `min-instances=1`.
  4. Subtree pull cadence + pinning policy.

---

## Stage 2 — shape (2026-05-17T18:32:21Z)

### Round 1 — What does the feature do?

- **Summarize entry point:** Both — on the video tile AND on a (new) video detail screen.
- **Pending UX:** Navigate to a summary detail screen immediately; result fills in live via Firestore listener.
- **User options at dispatch:** App decides everything. One canonical summary per video, no user controls. Backend chooses `model="free"`, default length, markdown format.
- **Output shape:** "What does the daemon provide" → resolved as plain markdown body. No timestamp parsing, no slides, no TL;DR formatting layer in MVP. Whatever the daemon natively emits is what the user sees.

### Round 2 — How does the feature behave?

- **Idempotency:** Tapping Summarize on a video with an existing non-failed summary opens the existing one. No new dispatch. Regeneration is a separate explicit action (deferred — not in MVP unless the user explicitly adds a "Regenerate" affordance later).
- **Retry model:** Manual retry button on failed-transient + idle background retry (daily cron rescans failed-transient summaries and re-dispatches).
- **Auto-summarize policy:** Auto-summarize all newly-synced videos across every playlist. Background dispatch from sync flow; not user-initiated.
- **Sync triggers:** Pull-to-refresh from Android + existing 6h cron on backend. Both paths produce identical Firestore writes.

### Round 3 — What does the feature look like?

- **Nav placement:** Tab in a new VideoDetailScreen. v1 scope now includes building VideoDetailScreen as the container for the SummaryScreen tab.
- **UI states (all four required):** Pending/Running (combined in-progress), Completed, Failed-transient (with Retry CTA), Failed-permanent (no retry).
- **Auto-summary completion UX:** No notification. User discovers when they next open the video. Tile may show a summary chip when a summary exists; that's the only signal.
- **Quota-exhausted UX:** App-wide banner at top of app + disabled Summarize controls until quota resets (midnight UTC). User cannot enqueue new requests when exhausted.

### Round 4 — What can go wrong?

- **No-transcript fallback:** Daemon's built-in `videoDetails.shortDescription` fallback is acceptable. Resulting summary is lower-fidelity but still completes. Don't pre-block dispatch.
- **Unknown webhook job-id:** Return 404, log, discard. Strict semantics.
- **Stuck-job recovery:** Hourly cron sweep marks any summary stuck >1h in `running` state as failed-transient. Recovered via daily retry cron from Round 2.
- **Summarizer access control:** API-key only (current planning-doc design). No Cloud Run IAM, no HMAC on outbound, no IP allowlist. Accept the risk that a leaked URL+key allows public abuse.

### Round 5 — Where are the boundaries?

- **Quota strategy:** Operator purchases one-time $10 OpenRouter credit before launch → 1000 RPD cap. Single daily counter. Documented as MVP prerequisite.
- **First-sync policy:** Auto-summarize everything. Backend queues every newly-synced video; daily cap drains it over multiple days if the library is large.
- **Cleanup scope in v1:** Drop Android's direct YouTube Data API + GoogleAccountCredential. Drop backend's unauthenticated `onRequest` sync endpoints. Defer setCookies removal and summarize-api `mode` field removal — these stay in code as legacy/no-op.
- **Verification tooling (all four):** Maestro flows for Android end-to-end + lazylogcat captures during runs + Firebase emulator for callables/Firestore/rules + docker-compose harness for end-to-end (mock backend → real summarizer container).

### Synthesizer notes — corrections from freshness research

- `firebase-functions ^6.3.0` + `firebase-admin ^13.0.0` are peer-incompatible. Bump `firebase-functions` to `^7` in Phase 1 (or pin admin to `^12`). PO not asked — treated as a planning-doc bug fix.
- OpenRouter free cap is 50/day on un-topped accounts (not 200/day as in the planning doc). With the chosen $10 credit prerequisite, ceiling is 1000/day.
- `gemini-2.0-flash-exp:free` was deprecated Feb 2026. Spec uses `model="free"` (daemon's rotating list) — no fixed model ID baked in.
- yt-dlp Dockerfile install must use `pip install -U "yt-dlp>=2026.02.21"`, not `apt`. Apt version pre-dates CVE-2026-26331 and lacks PoToken support.
- Webhook signature: adopt Stripe-style header `X-Summarizer-Signature: t=<unix>,v1=<hmac(timestamp + "." + raw_body)>` with 300s replay window + `crypto.timingSafeEqual`. Plain `X-Webhook-Signature: <hex>` from planning doc is now legacy.
- Subtree pin: bump to upstream `0ec12ac` before Phase 3 to pull in `#226` (timing-safe bearer compare) and `#227` (rate-limit failed bearer auth). Currently pinned at `e34ce25c`.
- Cloud Run sidecars now GA (Aug 2024) — present as an architectural alternative in plan stage; planning doc's single-container + `entrypoint.js` orchestrator remains an acceptable choice, not overridden.

---

## Stage 3 — slice (2026-05-17T21:45:53Z)

### Slicing strategy answers

- **Delivery order:** Visible-first. PO note: "isnt playster backend already deployed as well" — taken as confirmation that the existing backend (`scheduledSync` cron + Firestore sync) is already deployed and populating data, so the visible Android Firestore view can ship first without waiting on summarizer infra.
- **Granularity:** Thinnest — one slice per phase from shape. (Reconciled below with atomic flip.)
- **Rollout coupling:** Atomic flip — Phase 1 (backend auth lockdown) and Phase 2 (Android Firebase Auth + Firestore view) ship as one slice to avoid a transitional period where unauth onRequest endpoints coexist with new onCall endpoints.
- **Deferred ACs (out of v1):**
  - AC-13 — daily retry cron for failed-transient summaries. v1 only supports manual Retry. v1.1 candidate.
  - AC-12 — hourly stuck-job sweeper. Stuck-running summaries persist until manual Firestore cleanup. v1.1 candidate.

### Reconciliation note

"Thinnest 6 slices, one per phase" + "Atomic flip Phase 1+2" + "Cleanup deferrals from shape Stage 5" combine to **4 slices**, not 6:
- Phases 1 + 2 merge into one atomic slice (`auth-and-android-firebase`).
- Phase 6 (cleanup) is empty in v1 because (a) the cleanup items the PO selected in shape — Android youtube-data-api removal and unauth onRequest removal — naturally fold into the atomic slice; (b) the cleanup items the PO deferred — setCookies + summarize-api `mode` field — stay in code as no-ops.
- Phases 3, 4, 5 remain as their own slices.


---

## Stage 4 — plan (2026-05-18T07:49:39Z)

### Discovery Round 1 (4 questions, structured via AskUserQuestion)

- **Bootstrap procedure for the allowlist UID:** Placeholder rules + 2-pass deploy (Recommended). Initial deploy uses `__BOOTSTRAP_UID__` sentinel in `firestore.rules` + `ALLOWED_UID="__BOOTSTRAP_UID__"`. Operator signs in once via Credential Manager, captures the uid from Firebase console, replaces the sentinel + sets real `ALLOWED_UID`, redeploys rules + functions. No new bootstrap-only HTTP function ships. Runbook lives at `docs/operations/bootstrap-allowlisted-uid.md` (slice 1 writes).
- **`firebase-functions ^6 → ^7` upgrade aggressiveness:** Push through ^7 (Recommended). Even on friction, budget ~1 hour fixing call-site issues. No fallback to ^6.6 planned. Aligns with shape doc.
- **yt-dlp installation strategy in Dockerfile:** Static binary from GitHub Releases (Recommended). Pinned to 2026.02.21. No python3/pip layer in runtime image. Sidesteps Debian Trixie PEP-668.
- **Cloud Run `min-instances` for summarizer:** 0 — pay-per-request (Recommended). Cold-start cost (~3s daemon boot + 5–10s `/v1/refresh-free`) accepted for single-tenant use; operator can flip to 1 post-launch.

### Discovery Round 2 (4 questions, structured via AskUserQuestion)

- **Quota increment strategy:** Pessimistic pre-increment + best-effort decrement (Recommended). Transaction increments before HTTP dispatch; failed dispatch fires best-effort decrement. Worst case: over-counts by 1–2 on rare crash. Hard ceiling on OpenRouter $10 credit preserved.
- **Compose markdown library:** `dev.jeziellago:compose-markdown:0.5.7` (Recommended). Markwon-backed bridge. ~600KB APK impact. Native CommonMark + tables + code blocks + images via Coil.
- **Auto-dispatch routing on `VideoDetailScreen`:** Nav-arg `?autoDispatch=true|false` (Recommended). Tile-Summarize tap → `autoDispatch=true`; plain tile tap → `autoDispatch=false`. Best of both UX paths; AC-5 trivially testable.
- **Other recommended defaults:** Accept all (Recommended). Locked:
  - Credential Manager scoped to legacy `GoogleSignIn` removal only; One-Tap dead code stays.
  - `android/app/google-services.json` gitignored.
  - Cloud Build (`gcloud builds submit --tag`) for image push; Buildpacks rejected.
  - Subtree refresh cadence: pin-on-incident (CVE / upstream feature / 6-month hygiene).
  - Compose UI tests for 4-state SummaryScreen render; no Paparazzi adoption.
  - Tile "Summary ready" badge deferred to v1.1 (avoids N listeners per playlist).
  - Transactional read-then-`set` for `summaries/{videoId}` writes (collision-safe).
  - Auto-enqueue batched at end of sync (one `enqueueAutoSummary(videoIds[])` per sync invocation).
  - Daemon error-code vocabulary (`quota_exhausted`, `unrecoverable`, `transcript_impossible`) confirmed at slice-2 implement time; planning-stage estimate documented as placeholder.

### Resolved during plan stage (no PO question needed)

- **Q1/Q2 from `00-index.md` open-questions** resolved by direct source inspection of `summarizer/summarize-daemon/src/daemon/cli.ts:608-645`. Foreground daemon invocation: `node /opt/daemon/dist/cli.js daemon run --token <T> --port 8787`. No config file needed at runtime; `daemon run --token` synthesizes an in-memory v2 config. Open-questions cleared from `00-index.md`.
- **Cross-slice cohesion fixes** applied to per-slice plans:
  - `backend/functions/vitest.config.ts` ownership consolidated to slice 1 (lands first); slice 3 amends.
  - Slice 4's Firebase Android module references corrected from `*-ktx` suffix to no-suffix (BOM 34+ rolled KTX into main modules in Jul 2025).
  - Slice 4 adds `android/maestro/helpers/signin-helper.yaml` extract (factor with slice 1 during implementation; both Maestro flows share fixture-auth mechanism).

### Surprises surfaced during planning

- The `firebase-functions ^6` + `firebase-admin ^13` "peer conflict" is overstated — the lockfile resolves cleanly today. The `^7` bump is forward-looking, not a hard install blocker.
- Credential Manager scaffolding (`startCredentialSignIn` + `processCredentialSignIn`) is already present in `AuthScreen.kt` as dead code — just unwired. Switching the primary button to Credential Manager is a 3-line edit plus a tiny `idToken.id` → `idToken.idToken` correction.
- The three `onRequest` sync functions (`syncAllPlaylists`, `syncPlaylist`, `syncWatchLater`) have zero external callers anywhere in the repo. Safe to swap to `onCall` without any compat shim.
- `syncPlaylistById` (`youtube/api-sync.ts:162`) currently writes videos but discards their IDs. Slice 3 modifies the helper signature to also return `videoIds: string[]` so auto-enqueue runs without a Firestore re-read.
- Existing `backend/firestore.rules` is a placeholder (`allow read, write: if false` on every collection). Slice 1 replaces it; slice 3 extends additively.
- The legacy `summarizer/deploy/daemon/Dockerfile` includes a `sed` patch flipping `127.0.0.1 → 0.0.0.0` in the compiled daemon constants. That patch is *wrong* for our single-container architecture (daemon should stay loopback-bound) — deleted in slice 2.
- pnpm version mismatch between summarize-api (10.25.0) and summarize-daemon (10.33.2). Slice 2 unifies on 10.33.2 across both build stages; api `pnpm-lock.yaml` regenerated.
- Codebase has no existing sealed UI-state classes, no markdown rendering, no Maestro directory, no Hilt provider modules, no instrumented tests. Slice 4 establishes several "firsts" in tandem with slice 1.

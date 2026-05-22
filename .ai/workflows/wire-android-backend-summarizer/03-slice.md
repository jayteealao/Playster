---
schema: sdlc/v1
type: slice-index
slug: wire-android-backend-summarizer
status: complete
stage-number: 3
created-at: "2026-05-17T21:45:53Z"
updated-at: "2026-05-21T16:31:22Z"
total-slices: 5
best-first-slice: auth-and-android-firebase
tags: [android, firebase, cloud-run, summarizer, openrouter, single-tenant, multi-component]
slices:
  - slug: auth-and-android-firebase
    status: defined
    complexity: l
    depends-on: []
  - slug: summarizer-container
    status: defined
    complexity: l
    depends-on: []
  - slug: summary-orchestration
    status: defined
    complexity: m
    depends-on: [auth-and-android-firebase, summarizer-container]
  - slug: summary-ui
    status: defined
    complexity: m
    depends-on: [summary-orchestration]
  - slug: failure-recovery-cron
    status: defined
    complexity: s
    depends-on: [summary-orchestration]
    source: from-review
    extension-round: 1
refs:
  index: 00-index.md
  shape: 02-shape.md
next-command: wf-plan
next-invocation: "/wf plan wire-android-backend-summarizer auth-and-android-firebase"
---

# Slice Index — wire-android-backend-summarizer

## Slice Strategy

Four vertical slices, each independently verifiable, each ending in a user-visible or operator-visible state change. The shape spec's six phases reduce to four slices by atomic-flipping phases 1+2 (per PO rollout-coupling decision) and folding phase 6's cleanup items into their parent slices (since the PO-confirmed cleanup scope — Android `youtube-data-api` and unauth `onRequest` endpoints — happens naturally inside the atomic flip; the PO-deferred cleanups — legacy `setCookies` and summarize-api `mode` — stay in code as no-ops).

The reconciliation between "thinnest 6-per-phase" and "atomic flip 1+2" defaults to the more specific signal: atomic flip wins, slicing reduces to 4. This avoids a transitional period where the backend has both unauthenticated `onRequest` and allowlisted `onCall` endpoints active at once.

Visible-first ordering (per PO): slice 1 produces the most immediately visible change — operator opens the Android app and sees Firestore-synced playlists without the device ever calling the YouTube Data API. The remaining slices add summary capability behind that base, building up from infrastructure (slice 2) to orchestration (slice 3) to UI (slice 4).

Two ACs from shape are deferred to v1.1:
- **AC-12** (hourly stuck-job sweeper) — slice 3 ships without the cron; stuck-running summaries persist until manual Firestore cleanup.
- **AC-13** (daily retry cron for failed-transient summaries) — slice 4 ships only a manual Retry button; failed summaries do not auto-recover.

## Recommended Order

1. **`auth-and-android-firebase`** — Highest combined risk + visibility. Validates the Firebase Auth allowlist gate, deploys Firestore security rules tied to the operator's actual uid, replaces the Android device's direct YouTube Data API path with a Firestore live view, removes the unauth backend HTTP endpoints. End state: operator signs in once, sees playlists/videos from Firestore on the device, no YouTube credential on the device, no anonymous backend access.
2. **`summarizer-container`** — Highest infrastructure risk: bakes the daemon orchestration, multi-stage Docker build, yt-dlp install hygiene, Stripe-style webhook signing, and the subtree pin bump. Verified end-to-end via the docker-compose harness against fixture YouTube URLs (one captioned, one no-caption). Producing a real markdown summary that webhooks back to a mock backend is the AC. Can run in parallel with slice 1 development if desired (no code dependency); not a sequential prerequisite for shipping slice 1.
3. **`summary-orchestration`** — Wires slices 1 + 2 together at the backend. Adds `requestVideoSummary` callable, `summaryWebhook` HTTPS receiver, `quota/openrouter` transactional pre-flight, `summaryDispatcher` 5-minute cron for auto-summary queue draining, and the auto-enqueue hook in sync handlers. End state: a `requestVideoSummary` call (or a sync-triggered auto-summary) produces a completed `summaries/{videoId}` doc within the daily quota cap.
4. **`summary-ui`** — Surfaces the orchestration through Android. Adds VideoDetailScreen with Summary tab, SummaryScreen rendering all four states (in-progress, completed, failed-transient, failed-permanent), QuotaBanner observing `quota/openrouter`, and a Summarize affordance on the video tile. End state: operator opens a video, sees a summary either appear live or already present from auto-summarize.

## Cross-Cutting Concerns

These concerns span multiple slices; each slice's own AC covers its slice-local responsibility, but the slug-wide verification at handoff must check end-to-end:

- **Firebase Auth ID-token contract.** Slice 1 establishes it on Android and validates it in backend; slice 3 inherits the `allowlistedCall` wrapper for `requestVideoSummary`; slice 4 just uses the existing wrapper from slice 3.
- **Firestore document shapes** (`SummaryDocument`, `QuotaDocument`). Defined in slice 3's `models/index.ts` additions. Slice 4 consumes them as Kotlin DTOs.
- **Stripe-style HMAC contract.** Body signed = `${timestamp}.${raw_body}`. Header = `X-Summarizer-Signature: t=<unix>,v1=<hex-hmac-sha256>`. Replay window 300s. Slice 2 produces signatures; slice 3 verifies them. Both must agree on the canonical bytes (raw body, not parsed-then-restringified).
- **Subtree pin discipline.** Slice 2 bumps to `0ec12ac`. Subsequent slices do not touch the subtree. A `SUBTREE_PIN.md` artifact in `summarizer/summarize-daemon/` records the SHA and gets updated by subtree pulls only.
- **Quota state semantics.** `quota/openrouter` has fields `{ date, requestCount, dailyLimit: 1000, perMinuteLimit: 20, recentTimestamps: number[] }`. Slice 3 owns reads/writes; slice 4 only reads (for the banner).
- **Operator bootstrap** — the operator's actual uid must be captured after first sign-in, then injected into `ALLOWED_UID` config + hardcoded into `firestore.rules`, then re-deployed. This is a manual bootstrap step in slice 1's verification, not a code change. Documented as part of slice 1's risks.
- **`OPENROUTER_API_KEY` ($10 credit-backed) prerequisite.** Operational; not a code dependency. Slice 2 needs it provisioned in Secret Manager before the deployed container's `refresh-free` call works at cold start. Slice 3 enforces the 1000/day cap derived from the credit.
- **Verification tooling** — Maestro + lazylogcat + Firebase emulator + docker-compose harness. Slices 1 and 4 lean on Maestro + lazylogcat; slices 2 and 3 lean on the emulator + docker-compose. No slice owns the harness scaffolding exclusively — first slice that needs each tool stands it up; later slices reuse.

## Dependencies Between Slices

```
auth-and-android-firebase ─┐
                           ├─→ summary-orchestration ─→ summary-ui
summarizer-container ──────┘
```

- `auth-and-android-firebase` and `summarizer-container` have **no code dependency** between them and can be implemented in any order (or in parallel).
- `summary-orchestration` requires both to be merged: it imports the `allowlistedCall` wrapper from slice 1's `auth/verify.ts`, and it dispatches to the deployed Cloud Run URL from slice 2.
- `summary-ui` requires `summary-orchestration`: it consumes the Firestore `summaries/{videoId}` and `quota/openrouter` documents, and invokes the `requestVideoSummary` callable.

**Parallelism opportunity:** slices 1 and 2 can be developed simultaneously by different code paths (one Android+backend-auth focused, one summarizer-container focused). They merge cleanly because they touch disjoint files. PO has chosen visible-first delivery, so slice 1 ships first, but slice 2 development can start in parallel.

## Deferred / Optional Slices

These are explicitly **not in v1** and represent v1.1+ candidates. They are tracked here so they aren't forgotten.

- **`v1.1-failure-recovery-cron`** — combines AC-12 (hourly stuck-job sweeper) and AC-13 (daily failed-transient retry cron). One slice. Likely complexity: S.
- **`v1.1-slides-extraction`** — Phase 7 from planning doc. Daemon's slides feature + slide-strip Compose UI + GCS volume for persistence. Likely complexity: M-L (the GCS volume mount is the heavy part).
- **`v1.1-fcm-notifications`** — push notifications when auto-summary completes. Requires FCM topic setup, backend trigger, Android receiver. Likely complexity: S-M.
- **`v1.1-legacy-cleanup`** — remove `setCookies` HTTP endpoint, drop summarize-api `mode` field from schema, retire `youtubei.js`/`googleapis` dependencies if no longer needed by the kept code paths. Likely complexity: XS-S.
- **`v1.1-sidecar-refactor`** — migrate Cloud Run summarizer from single-container + `entrypoint.js` to two-container sidecar deployment now that sidecars are GA. Likely complexity: M.

## Freshness Research

No new freshness pass needed at this stage. Slice 2 specifically inherits research already captured in `02-shape.md`:
- yt-dlp must be `>=2026.02.21` (`pip` install, not `apt`).
- Subtree pin must move to `0ec12ac` for bearer-token timing-safe compare + rate-limit (PRs #226, #227).
- Stripe-style webhook signature with 300s replay window.
- `firebase-functions ^7` (slice 1) resolves the `firebase-admin ^13` peer conflict.
- OpenRouter free cap = 1000/day post one-time $10 credit (slice 3 constant).

If any of these change between now and slice 2/3 implementation, plan stage for those slices should re-run freshness.

## Recommended Next Stage

- **Option A (default):** `/wf plan wire-android-backend-summarizer auth-and-android-firebase` — Recommended. Start with the highest-visibility slice that establishes the auth contract every later slice depends on.
- **Option B:** `/wf plan wire-android-backend-summarizer all` — Plan all four slices upfront. Reasonable since slices 1 and 2 are independent and could be developed in parallel; slices 3 and 4 each have one dependency. Trades some upfront planning effort for a clearer end-to-end picture before any implementation lands.
- **Option C:** `/wf shape wire-android-backend-summarizer` — Revisit shape. Not recommended. The shape spec is detailed; slicing did not reveal contradictions or gaps that require returning to shape. (One minor reconciliation — "thinnest" vs "atomic flip" — was resolvable here without re-shaping.)

## Extension Round 1 — 2026-05-21
Source: from-review (07-review.md, finding R-12 sourced from 07-review-testing.md TST-3)

### New Slices Added
| Slice | Goal | Complexity | Depends On |
|-------|------|------------|------------|
| `failure-recovery-cron` | Hourly `summarySweeper` (stuck running → failed-transient) + daily `summaryRetryCron` (re-dispatch failed-transient within quota); covers AC-12 + AC-13. | S | `summary-orchestration` |

### Motivation

The slug-wide review at stage 7 surfaced AC-12 (`summarySweeper`) and AC-13 (`summaryRetryCron`) as missing-capability findings — present in `02-shape.md` but explicitly deferred by the original slice strategy (see *Deferred / Optional Slices* → `v1.1-failure-recovery-cron` above). The shape doc's *Failure & recovery* section requires that hung Cloud Run instances and transient OpenRouter failures auto-recover without manual operator intervention. Until this slice ships, stuck `running` docs persist indefinitely and `failed-transient` docs require a manual Retry tap from the Android UI.

The slice is bundled (sweeper + retry together) because they operate on the same Firestore documents, share the existing `quota/openrouter` transaction, and the sweeper's output is the retry's input. PO confirmed at extension time that this slice should be planned immediately on a parallel branch — it does NOT gate v1.0 handoff/ship of the four original slices.

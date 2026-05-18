---
schema: sdlc/v1
type: slice
slug: wire-android-backend-summarizer
slice-slug: summary-ui
status: defined
stage-number: 3
created-at: "2026-05-17T21:45:53Z"
updated-at: "2026-05-17T21:45:53Z"
complexity: m
depends-on: [summary-orchestration]
tags: [android, compose, navigation, firestore, markdown, quota]
refs:
  index: 00-index.md
  slice-index: 03-slice.md
  siblings:
    - 03-slice-auth-and-android-firebase.md
    - 03-slice-summarizer-container.md
    - 03-slice-summary-orchestration.md
  plan: 04-plan-summary-ui.md
  implement: 05-implement-summary-ui.md
---

# Slice: Summary UI (VideoDetailScreen + SummaryScreen + QuotaBanner)

## Goal

Surface the summary capability through Android Compose: a new VideoDetailScreen with a Summary tab, a SummaryScreen rendering all four states (in-progress, completed, failed-transient with Retry CTA, failed-permanent), a Summarize affordance on the video tile in the playlist view, and a top-of-app QuotaBanner that observes `quota/openrouter` and disables Summarize controls when exhausted.

## Why This Slice Exists

This is the user-visible outcome — the slice that turns the backend pipeline into something the operator actually interacts with. It's also the only slice that wires up the full state machine the operator observes: tap → in-progress → completed (or failed-transient → Retry → in-progress → completed).

## Scope

**In scope:**

- New: `screens/videoDetail/VideoDetailScreen.kt` — top-level Compose destination, accepts `videoId` nav arg, contains:
  - Header: video thumbnail, title, channel, basic metadata (read from `videos/{videoId}` Firestore listener).
  - Tab row with at least one tab: "Summary" (the SummaryScreen). Other tabs may be no-ops in v1.
- New: `screens/videoDetail/summary/SummaryScreen.kt` + `SummaryViewModel.kt`:
  - ViewModel observes `summaries/{videoId}` via Firestore listener; emits `SummaryUiState` sealed class with variants `InProgress`, `Completed(content)`, `FailedTransient(errorMessage)`, `FailedPermanent(errorMessage)`, `NoSummary` (initial — no doc exists yet).
  - On `NoSummary` initial state: auto-dispatch a `requestVideoSummary` call (if the user navigated here via tapping Summarize on the tile) OR show an explicit "Summarize this video" button (if the user navigated here without explicit intent).
  - On `InProgress`: spinner + label "Generating summary…".
  - On `Completed`: render `content` as Markdown via a Compose markdown library (existing dep `coil-compose` doesn't suffice; pick markdown library in plan stage — candidates: `compose-markdown`, `Markwon-compose`).
  - On `FailedTransient`: error icon + message + "Retry" button → re-invokes `requestVideoSummary` callable.
  - On `FailedPermanent`: error icon + message; no Retry button. Copy distinguishes "Daily limit reached" from "Couldn't summarize this video".
- Update `screens/playlist/PlaylistScreen.kt` (which slice 1 already rewrote to use Firestore): add navigation to VideoDetailScreen on video-tile tap (slice 1 left this as a no-op or placeholder). Add a "Summarize" affordance — either an icon on the tile or in the overflow — that:
  - If `summaries/{videoId}` does not exist → invokes `requestVideoSummary` and navigates to VideoDetailScreen (which will then show InProgress).
  - If exists and non-failed → just navigates to VideoDetailScreen (cached behavior per PO Round 2).
  - If exists and failed-transient → optionally pre-emptively dispatches retry, then navigates. Decide in plan stage.
- New / repurposed: `screens/common/QuotaBanner.kt` Composable — observes `quota/openrouter`, renders nothing when `requestCount < dailyLimit` and `recentTimestamps.length < perMinuteLimit`, otherwise renders a sticky top banner "Daily summary limit reached" (or "Rate limited — try again in a moment" for per-minute). All Summarize controls (tile button + Summary tab "Summarize this video" button) observe the same state and disable when banner is active.
- Update `navigation/`: add the VideoDetailScreen route to the NavGraph; deep link by `videoId`.
- Update `data/firestore/`: add Kotlin `SummaryDoc` and `QuotaDoc` data classes mirroring slice 3's `SummaryDocument` / `QuotaDocument`.
- New Maestro flows under `android/maestro/`:
  - `manual-summary-fresh.yaml` (AC-5)
  - `quota-exhausted-banner.yaml` (AC-10)
- Add `compose-markdown` (or chosen lib) to `android/app/build.gradle.kts`.

**Out of scope (handled by other slices or deferred):**

- Backend `requestVideoSummary` / `summaryWebhook` / `quota` logic → slice 3.
- Multi-tab VideoDetailScreen with non-Summary tabs → out of v1 (only Summary tab is required).
- Push notifications on auto-summary completion → out of v1 (PO chose "no notification").
- Tile badge ("✓ Summary ready") indicating cached summaries — soft out-of-scope from shape; plan stage may include if cheap, else defer.
- Stale-summary banner (if video changed since summary) → out of v1.
- Quota countdown timer in banner → soft out-of-scope; plan stage decides.
- Regenerate / re-summarize affordance → out of v1.
- Settings UI for daemon options (model, length, etc.) → out of v1 (PO chose "app decides everything").

## Acceptance Criteria

Mapped to shape's ACs:

- **AC-5**: Tap Summarize on a video with no prior summary → in-progress UI renders within 500ms of the tap, the SummaryScreen is visible. Verified via Maestro flow with timing assertion (`assertVisible` timeout = 500ms after tap).
- **AC-10**: Pre-seeded `quota/openrouter` at cap → banner visible, Summarize disabled. Verified via Maestro flow with backend (emulator) seeding `quota/openrouter` to `{ requestCount: 1000, dailyLimit: 1000, ... }`.

**Slice-local ACs (not in shape):**

- All four SummaryScreen states render distinctly (in-progress, completed, failed-transient with Retry, failed-permanent without Retry). Verified by snapshot tests (Paparazzi or Compose UI test) seeded with synthetic state.
- Tapping Summarize on a video that already has a completed summary navigates straight to the completed state — no new dispatch. Verified via Maestro flow + assertion that `requestVideoSummary` callable was not invoked (backend log).
- Retry button on failed-transient state re-invokes `requestVideoSummary` and the screen transitions to in-progress within 500ms. Verified via Maestro flow.
- QuotaBanner subscription doesn't leak across navigation (no Firestore listener stays attached when the user leaves the relevant screen). Verified via memory / instance check or by `lazylogcat` filter on listener detach.

## Dependencies on Other Slices

- **`summary-orchestration`** — Hard prerequisite. Imports the `requestVideoSummary` callable signature and consumes `summaries/{videoId}` + `quota/openrouter` Firestore docs.

Downstream: none. This is a leaf slice. Verification at handoff includes the cumulative golden flow (sign-in → playlist → video tile → SummaryScreen → completed) which is the v1 demo.

## Risks

- **Markdown library choice.** Compose ecosystem has several markdown libs of varying quality. Some don't render tables, some have stale Compose versions. Mitigation: plan stage picks based on the shape of summaries the daemon actually produces; tests the chosen lib against a known markdown sample.
- **Firestore listener lifecycle.** Forgetting to unsubscribe on screen disposal leaks listeners. Mitigation: use Compose `DisposableEffect` or wrap subscriptions in ViewModel `viewModelScope` with proper cancellation.
- **Quota banner can flicker.** If `quota/openrouter` updates frequently (every dispatch increments), the banner state could oscillate. Mitigation: derive banner state from `requestCount >= dailyLimit OR perMinuteWindow >= 20`, not from any rate-of-change signal. UI is intrinsically debounced by the listener.
- **Navigation back-stack from SummaryScreen.** Tapping Summarize from a tile pushes VideoDetailScreen; on completion the user backs out to playlist. Verify no orphan back-stack entries or stale state. Mitigation: snapshot the nav-graph behavior in a Maestro flow.
- **Auto-summary completion is invisible (PO chose no notification).** A summary may complete in the background. When the user later opens the video, the SummaryScreen will show `Completed` immediately. Mitigation: this is by design; the slice's UX makes this discovery natural. No additional work.
- **Confusion between "in-progress" and the rare `queued` (auto-summarize) state.** PO chose to merge pending/running into one in-progress UI state. But auto-summarized videos sit at `queued` (not pending) until the 5-min dispatcher promotes them. UI should treat `queued` the same as `pending` and `running` — all render as in-progress. Mitigation: explicit mapping in the ViewModel state machine.
- **Maestro flow stability against real Firebase.** Maestro flows that depend on real Firebase emulator state are fragile. Mitigation: scope Maestro flows to the local emulator with pre-seeded fixtures; document the seeding procedure as part of `pnpm verify:e2e`.
- **Compose markdown rendering with code blocks / links / images.** Daemon output may include code blocks and image references (rare for YouTube content but possible). Mitigation: pick a markdown lib that handles all CommonMark features; render images via Coil if present.
- **APK size impact of new dependencies.** Firebase BOM + markdown lib + any new Compose-foundation transitive deps add APK weight. Mitigation: measure pre/post and accept if reasonable.

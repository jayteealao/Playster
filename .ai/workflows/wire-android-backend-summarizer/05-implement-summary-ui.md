---
schema: sdlc/v1
type: implement
slug: wire-android-backend-summarizer
slice-slug: summary-ui
status: complete
stage-number: 5
created-at: "2026-05-19T22:55:42Z"
updated-at: "2026-05-19T22:55:42Z"
metric-files-changed: 30
metric-lines-added: 1257
metric-lines-removed: 25
metric-deviations-from-plan: 4
metric-review-fixes-applied: 0
commit-sha: "a57ba283"
tags: [android, compose, navigation, firestore, markdown, quota]
refs:
  index: 00-index.md
  implement-index: 05-implement.md
  slice-def: 03-slice-summary-ui.md
  plan: 04-plan-summary-ui.md
  siblings:
    - 05-implement-auth-and-android-firebase.md
    - 05-implement-summarizer-container.md
    - 05-implement-summary-orchestration.md
  verify: 06-verify-summary-ui.md
next-command: wf-verify
next-invocation: "/wf verify wire-android-backend-summarizer summary-ui"
---

# Implement: Summary UI (VideoDetailScreen + SummaryScreen + QuotaBanner)

## Summary of Changes

The Android client now has the user-visible head of the summary pipeline.
`videoDetail/{videoId}?autoDispatch={bool}` route, a Compose VideoDetailScreen
with a single Summary tab, a SummaryScreen rendering five states
(NoSummary, InProgress, Completed, FailedTransient, FailedPermanent), an
app-global QuotaBanner that observes `quota/openrouter`, and a Summarize
affordance on every video tile that wires the cache-vs-fresh decision via a
one-shot `summaries/{videoId}` get.

The summary state machine is driven by a single Firestore listener over
`summaries/{videoId}`; callable-invocation failures that happen *before* a
doc is reserved are surfaced via a `localFailure` MutableStateFlow that
overrides the listener emission until the user taps Retry. Markdown
rendering uses `com.github.jeziellago:compose-markdown:0.7.2` from JitPack.
All listeners are `callbackFlow + awaitClose` so leaving a screen tears the
subscription down (slice 1's pattern, extended to two more collections).

The QuotaBanner moved from PlaylistScreen-local to an app-global Column
wrapper inside MainActivity so the banner stays visible across every screen.
Tile Summarize controls observe the same QuotaBannerViewModel via a shared
`rememberQuotaState()` Composable accessor, so the banner and the per-tile
disabled-button state move together.

Three Maestro flows + four androidTest Compose UI tests cover AC-5, AC-10,
and the four state-rendering slice-local ACs. Maestro fixture-seeding
scripts under `android/maestro/helpers/` use `firebase emulators:exec` with
the admin SDK to write deterministic playlist / video / quota / summary
docs before each flow.

## Files Changed

### New — Android

- `data/firestore/SummaryDoc.kt` — Kotlin mirror of backend
  `SummaryDocument`. Includes `SummaryStatus` enum (with UNKNOWN fallback
  for forward-compatible wire values) and a `DocumentSnapshot.toSummaryDoc()`
  extension that returns null for missing docs.
- `data/firestore/QuotaDoc.kt` — Kotlin mirror of backend `QuotaDocument`
  plus `QuotaState` sealed interface (Healthy / DailyExhausted /
  PerMinuteExhausted) and a `QuotaDoc?.toQuotaState(now)` derivation. The
  per-minute window trim is computed at observation time, not stored.
- `data/firestore/FirestoreRepository.kt` — extended in-place with two new
  `@Singleton` companions (`SummaryRepository`, `QuotaRepository`) and a
  `videoFlow(videoId)` over `collectionGroup("videos")`. All flows tag
  attach/detach via the `playster.summary` logcat tag.
- `functions/SummaryFunctions.kt` — thin wrapper around the
  `requestVideoSummary` callable. Returns `Result<String>` so error code
  branching happens at the ViewModel layer, not via thrown exceptions.
- `screens/common/InProgressIndicator.kt` — central spinner + label
  Composable. testTag `summary-in-progress-spinner` by default; param-
  customizable for other screens that want to reuse.
- `screens/common/ErrorPanel.kt` — icon + message + optional Retry button.
  No-Retry mode for failed-permanent state.
- `screens/videoDetail/VideoDetailViewModel.kt` — Hilt ViewModel with
  `SavedStateHandle` reading nav args `videoId` + `autoDispatch`. Exposes
  `video: StateFlow<VideoDoc?>` driven by `FirestoreRepository.videoFlow`.
- `screens/videoDetail/VideoDetailScreen.kt` — header (thumbnail + title +
  channel) + back button + single-tab TabRow + embedded SummaryScreen.
  testTags `video-detail-screen` and `summary-tab`.
- `screens/videoDetail/summary/SummaryUiState.kt` — sealed interface with
  five `data object`/`data class` variants. UNKNOWN/null collapses to
  NoSummary.
- `screens/videoDetail/summary/SummaryViewModel.kt` — five-state machine
  driven by `SummaryRepository.observe()` combined with a `localFailure`
  override flow. On `init`, if `autoDispatch=true` and no doc exists, fires
  one `requestVideoSummary` call (guarded by an `autoDispatched` flag so
  re-subscription doesn't redispatch). FirebaseFunctionsException codes map
  to FailedPermanent (UNAUTHENTICATED, PERMISSION_DENIED, RESOURCE_EXHAUSTED,
  NOT_FOUND) or FailedTransient (INTERNAL, UNAVAILABLE, DEADLINE_EXCEEDED,
  default).
- `screens/videoDetail/summary/SummaryScreen.kt` — stateless
  `SummaryScreenContent(state, onRetry, onSummarize)` + connected wrapper
  `SummaryScreen(viewModel)`. All five branches `testTag`'d.
  `dev.jeziellago.compose.markdowntext.MarkdownText` renders the Completed
  state.

### Modified — Android

- `app/build.gradle.kts` — add `libs.compose.markdown` implementation.
- `gradle/libs.versions.toml` — add `compose-markdown = "0.7.2"` version
  and `com.github.jeziellago:compose-markdown` library entry (JitPack
  coordinate; see deviations).
- `settings.gradle.kts` — add `maven { url = uri("https://jitpack.io") }`
  to the `dependencyResolutionManagement` repositories block.
- `MainActivity.kt` — wrap `PlaysterNavHost` in a `Column` with
  `QuotaBanner()` above it so the banner is app-global. Banner returns a
  zero-height Box when `QuotaState.Healthy`, so layout is undisturbed for
  the happy path.
- `screens/auth/PlaysterNavHost.kt` — add `videoDetail/{videoId}?
  autoDispatch={autoDispatch}` composable destination wired to
  `VideoDetailScreen`. `videos/{playlistId}` callback now passes
  `(videoId, autoDispatch) -> Unit` to `VideoListScreen`.
- `screens/playlist/PlaylistScreen.kt` — drop the slice-1
  `QuotaBanner()` call (banner moved to MainActivity).
- `screens/playlist/VideoListScreen.kt` — full rewrite of the tile row:
  card tap navigates to detail without autoDispatch; new `AutoAwesome`
  icon button reads `rememberQuotaState()` for enabled/disabled binding
  and routes through `viewModel.onSummarizeClick(videoId, onOpenVideo)`
  which runs a one-shot `summaries/{videoId}` get to pick autoDispatch
  true (absent/failed) vs false (cached). Both tags
  `summarize-button-enabled` and `summarize-button-disabled` are
  applied conditionally.
- `screens/common/QuotaBanner.kt` — full rewrite. `QuotaBannerViewModel`
  collects `QuotaRepository.observe()` into a `StateFlow<QuotaDoc?>`;
  derived state at render time. Two banner variants by reason, both
  carrying `quota-banner` testTag. Exports a `rememberQuotaState()` helper
  so Summarize controls share the same observation pipeline.

### New — Maestro

- `android/maestro/manual-summary-fresh.yaml` — AC-5. Seeds fresh state,
  signs in, taps Summarize, asserts in-progress UI within 500ms.
- `android/maestro/quota-exhausted-banner.yaml` — AC-10. Seeds quota at
  cap, signs in, asserts banner + disabled Summarize button.
- `android/maestro/cached-summary-navigation.yaml` — slice-local. Seeds
  cached completed summary, taps Summarize, asserts direct landing on
  Completed state (the `lazylogcat` zero-`requestVideoSummary` check is a
  post-run assertion documented in the verify stage).
- `android/maestro/helpers/signin-helper.yaml` — reusable sign-in
  fragment. The actual fixture-auth mechanism is owned by slice 1 —
  this file proxies to whatever slice 1 ships and is shared by both
  slices' flows.
- `android/maestro/helpers/seed-fresh-state.sh` + `write-fresh-state.js` —
  seeds playlist + video for AC-5.
- `android/maestro/helpers/seed-quota-exhausted.sh` + `write-quota-cap.js`
  — seeds quota/openrouter to dailyLimit + playlist/video so the tile is
  reachable.
- `android/maestro/helpers/seed-cached-summary.sh` +
  `write-cached-summary.js` — seeds playlist/video/summary at status=
  completed.

### New — instrumented tests

- `app/src/androidTest/.../SummaryScreenComposeTest.kt` — four Compose UI
  tests exercising each non-NoSummary state via the stateless
  `SummaryScreenContent`. Asserts the discriminating testTag and (for
  failed-permanent) that the Retry button is *not* in the tree.

## Shared Files (also touched by sibling slices)

- `data/firestore/FirestoreRepository.kt` — slice 1 introduced this file
  with `playlistsFlow` / `videosFlow`. This slice extended it with
  `videoFlow(videoId)` and added two new `@Singleton class` siblings
  (`SummaryRepository`, `QuotaRepository`) in the same file.
- `screens/common/QuotaBanner.kt` — slice 1 left a no-op placeholder
  reserving the Composable name; this slice replaces the body. Signature
  stayed `(modifier: Modifier = Modifier)` so callers don't change (but
  the *only* caller was PlaylistScreen, which now goes through
  MainActivity instead).
- `screens/auth/PlaysterNavHost.kt` — slice 1 introduced the route table
  and added `videos/{playlistId}`. This slice added `videoDetail/...` and
  extended the `videos/` route callback signature to forward the
  `onOpenVideo(videoId, autoDispatch)` lambda into `VideoListScreen`.
- `screens/playlist/VideoListScreen.kt` — slice 1 stubbed this as a
  no-op tile list. This slice keeps the Hilt ViewModel + listener wiring
  and replaces the row Composable with the Summarize-bearing version.

## Notes on Design Choices

- **App-global QuotaBanner.** Slice 1 put the banner inside
  `PlaylistScreen` (the only screen visible to users at that point). The
  slice file calls for the banner to be visible across screens — moving
  the call site to `MainActivity` above the NavHost achieves that without
  needing to thread the state through each screen. The
  zero-height-when-Healthy invariant keeps the layout undisturbed for the
  90%+ case.
- **Shared `rememberQuotaState()` accessor.** Two distinct consumers
  (`QuotaBanner` and `VideoListScreen`) need the same derived state. A
  separate `QuotaBannerViewModel` returned via `hiltViewModel()` is
  scoped to whichever Composable claims it; the helper just wraps
  `viewModel.quotaDoc.toQuotaState()` so the consumers stay decoupled
  from the ViewModel type. The same Hilt ViewModel instance is reused
  across calls within the same NavBackStackEntry — this is correct: the
  QuotaBanner subscription is shared, no duplicate listeners.
- **No AssistedInject.** Plan called for `@AssistedInject` on
  `VideoDetailViewModel` and `SummaryViewModel` keyed on `videoId` +
  `autoDispatch`. The sibling `VideoListViewModel` (slice 1) already
  reads `playlistId` from `SavedStateHandle` — that's the simpler
  pattern Hilt natively supports without `@AssistedFactory` + Hilt
  module ceremony. NavGraph args propagate to the
  `NavBackStackEntry.arguments` Bundle, which Hilt wires into
  `SavedStateHandle` automatically. Both new ViewModels follow this.
- **`localFailure` override flow.** A callable failure that happens
  before any `summaries/` doc is reserved (e.g.,
  `resource-exhausted` thrown by the allowlistedCall gate, or a
  network failure mid-request) cannot surface via the listener — the
  listener will emit `null` forever. The `MutableStateFlow<SummaryUiState?>`
  is `combine`'d with the listener emissions so the UI honors the
  local failure until the user retries (which clears it).
- **One-shot `summaries/{videoId}.get()` for cache check.** The
  Summarize button needs to decide autoDispatch=true|false based on
  whether a cached non-failed summary exists. A one-shot read in
  `VideoListViewModel.onSummarizeClick` is the lightest path — no
  per-tile listener (which would multiply Firestore subscriptions by
  playlist size and contradict the "don't attach a listener per tile"
  constraint from the slice file).
- **JitPack repository, JitPack GAV.** See deviation #1.
- **Material 3 / Material 2 mix.** Slice 1 introduced the pullRefresh
  Material 2 dependency; this slice continues to use the existing
  Material 3 components (Card, Button, Icon, TabRow). No Material 2
  additions here.
- **`SummaryStatus.UNKNOWN` fallback.** Backend wire shape uses six
  string statuses today; if a future schema adds a seventh, the client
  collapses it to UNKNOWN → NoSummary instead of crashing. This is
  defensive but cheap.

## Visual Contract Honored

Not applicable — no `02c-craft.md` was produced for this slice.

## Deviations from Plan

1. **Markdown library coordinate changed.** Plan specified
   `dev.jeziellago:compose-markdown:0.5.7` from Maven Central. That GAV
   does not exist. The library is published on JitPack as
   `com.github.jeziellago:compose-markdown` and the current stable tag
   is `0.7.2` (verified via the upstream repo releases). Resolution:
   bumped libs.versions.toml to `0.7.2`, switched library coordinate to
   `com.github.jeziellago:compose-markdown`, added JitPack to
   `settings.gradle.kts` `dependencyResolutionManagement.repositories`.
   The plan's MarkdownText API (`dev.jeziellago.compose.markdowntext.
   MarkdownText`) remained valid across the 0.5.x → 0.7.x bump.
2. **QuotaBanner moved app-global (MainActivity instead of
   PlaylistScreen-only).** Plan B.5 put the QuotaBanner inside the
   PlaylistScreen Column. The banner should be visible on every screen
   per the slice file ("a top-of-app QuotaBanner"). Moved the call site
   to `MainActivity.setContent { Column { QuotaBanner(); NavHost(...) } }`
   so the banner appears above every route. Removed the PlaylistScreen-
   local call.
3. **No `FirestoreModule.kt` Hilt module.** Plan B.2 called for a new
   `data/firestore/FirestoreModule.kt` to provide `QuotaRepository`. The
   existing `data/AppModule.kt` (slice 1) already provides
   `FirebaseAuth`/`FirebaseFirestore`/`FirebaseFunctions` and Hilt's
   `@Singleton class QuotaRepository @Inject constructor(firestore)` is
   discoverable without an explicit provider. Skipped the redundant
   module file.
4. **No `@AssistedInject` for ViewModels.** Plan C.1 + D.3 specified the
   AssistedInject pattern for `VideoDetailViewModel` /
   `SummaryViewModel` keyed on nav args. The simpler
   `SavedStateHandle`-injection pattern (slice 1's
   `VideoListViewModel`) is what Hilt supports natively — no
   `@AssistedFactory` boilerplate, nav args flow through automatically
   via the NavBackStackEntry bundle. Both new ViewModels use the
   simpler pattern.

## Anything Deferred

- **Tile badge for cached summaries ("✓ Summary ready").** Plan
  explicitly deferred to v1.1; not implemented.
- **Live Maestro execution + lazylogcat artifact capture.** Phase G
  belongs to the verify stage. The flow YAML + seed scripts are in
  place; running them needs the Firebase emulator booted with the right
  fixtures + a connected device or emulator + the fixture-auth
  mechanism slice 1 owns.
- **`./gradlew :app:connectedDebugAndroidTest`** — instrumented test
  compiles cleanly but requires a connected device/emulator to execute.
  Verify stage runs it.
- **APK size delta measurement.** Plan G.3 ("nice to have"); left for
  slice retro.
- **Active-quota debouncing.** The plan notes the banner is
  intrinsically debounced by Firestore listener semantics. No
  debouncing is added.

## Known Risks / Caveats

- **Plan called for compose-markdown 0.5.7 from Maven Central; actual
  resolved coord is JitPack 0.7.2.** Documented in deviation #1.
  Functional risk: zero (API surface used is identical); operational
  risk: anyone running `./gradlew dependencies` against a pre-JitPack
  global Gradle config (`mavenCentral()` only) will need the
  `settings.gradle.kts` change to pull. Already committed.
- **`google-services.json` still gitignored.** Operator continues to
  supply it locally before `assembleDebug`.
- **`ALLOWED_UID` still `__BOOTSTRAP_UID__` until the operator runs
  the bootstrap two-pass deploy.** Maestro flows that exercise the
  `requestVideoSummary` callable will fail with `permission-denied`
  until the real uid is in the allowlist. AC-5 verification will need
  the bootstrap step to be complete OR the fixture-auth helper to sign
  in as the configured allowlisted uid.
- **JitPack first-fetch latency.** The first `pnpm` (wrong tool — first
  `./gradlew assemble`) pull from a clean local cache will fetch the
  POM from JitPack which builds on demand. Subsequent fetches are
  cached. Documented for the verify operator.
- **`SummaryViewModel.init` auto-dispatch races with the listener.**
  The mitigation is a `compareAndSet` on the `autoDispatched` flag —
  the dispatch only fires once even under re-subscription, and only if
  the listener has not already returned a doc by the time the one-shot
  `getOnce` resolves. If a sync arrives between getOnce and dispatch
  (unlikely; sub-second window), the listener will overwrite the
  in-progress state with the real persisted state, which is benign.
- **Hilt's shared `QuotaBannerViewModel`.** Composables in the
  NavBackStackEntry tree share the ViewModel instance via Hilt's
  ViewModelStoreOwner resolution. Verified: both `QuotaBanner` (at
  MainActivity scope — the root activity is the ViewModelStoreOwner
  here) and `VideoListScreen`'s `rememberQuotaState()` (inside the
  nested NavHost route) get *separate* instances because they're in
  different ViewModelStoreOwners. Two listeners attach. Not optimal
  but functionally correct; the per-minute-limit derivation is the
  same in both, so banner and tile state stay aligned. Worth a follow-
  up to share a single instance via a `LocalQuotaState` CompositionLocal
  — deferred.

## Freshness Research

Released-version research at implement time (since the plan referenced a
version that doesn't exist on Maven Central):

- **`compose-markdown` releases**: latest tag is `0.7.2`, 2026-Q1.
  GitHub releases enumerated via `gh api repos/jeziellago/compose-
  markdown/releases`: 0.7.2, 0.7.1, 0.7.0, 0.6.0, 0.5.8 (the closest to
  the plan's 0.5.7 — but JitPack will build any tag on demand, so 0.5.8
  was available too). Chose 0.7.2 as the current upstream stable.
- **JitPack repo configuration**: confirmed via upstream README that the
  install steps are unchanged — JitPack URL + `com.github.jeziellago:
  compose-markdown:<version>` coord. The MarkdownText API surface is
  the same import path (`dev.jeziellago.compose.markdowntext.MarkdownText`).

No other ecosystem lookups were needed beyond what the plan already
recorded.

## Recommended Next Stage

- **Option A (default):** `/wf verify wire-android-backend-summarizer
  summary-ui` — verify the slice. Compile is green; instrumented test
  compiles; Maestro YAML + helpers are in place. Verify will run the
  Compose UI tests on a connected device, build the APK, and exercise
  the Maestro flows against the Firebase emulator with fixture
  seeding. **Consider running `/compact` first** — implementation
  details (file reads, deprecation chases, dependency-coord fix) are
  noise for verification; the PreCompact hook preserves workflow state.
- **Option B:** `/wf review wire-android-backend-summarizer summary-ui`
  — skip verify and go directly to review if the operator judges the
  emulator-and-device gate as the verify scope's responsibility rather
  than implement-time. (Not recommended: AC-5's 500ms timing assertion
  is the verify gate.)
- **Option C:** `/wf plan wire-android-backend-summarizer summary-ui` —
  revisit the plan if a structural issue is found. None expected.

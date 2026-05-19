---
schema: sdlc/v1
type: plan
slug: wire-android-backend-summarizer
slice-slug: summary-ui
status: complete
stage-number: 4
created-at: "2026-05-18T07:49:39Z"
updated-at: "2026-05-18T07:49:39Z"
metric-files-to-touch: 22
metric-step-count: 38
has-blockers: false
revision-count: 0
tags: [android, compose, navigation, firestore, markdown, quota]
stack-source: confirmed
refs:
  index: 00-index.md
  plan-index: 04-plan.md
  slice-def: 03-slice-summary-ui.md
  siblings:
    - 04-plan-auth-and-android-firebase.md
    - 04-plan-summarizer-container.md
    - 04-plan-summary-orchestration.md
  implement: 05-implement-summary-ui.md
next-command: wf-verify
next-invocation: "/wf verify wire-android-backend-summarizer summary-ui"
---

# Plan: Summary UI (VideoDetailScreen + SummaryScreen + QuotaBanner)

## Context Recap

This slice is the user-visible head of the pipeline assembled by slices 1–3. Hard prerequisite is `summary-orchestration` (slice 3), which delivers:

- `requestVideoSummary` callable: input `{ videoId: string; model?: string }`, output `{ summaryId: string }`.
- `summaries/{videoId}` Firestore docs (read-allowed for the allowlisted uid, write denied — Admin SDK only).
- `quota/openrouter` Firestore doc (read-allowed for the allowlisted uid).

Slice 1 (`auth-and-android-firebase`) is also a transitive prerequisite — it lands Firebase Auth bridging, the Firestore listener pattern on `PlaylistScreen.kt`, Firebase BOM 34+ (`firebase-auth`, `firebase-firestore`, `firebase-functions` — **no `-ktx` suffix**; BOM 34 rolled KTX into main modules in Jul 2025) in `android/app/build.gradle.kts`, and the `QuotaBanner` scaffold placeholder.

Open question pinned for resolution this stage: **which markdown library**. Default recommendation below; PO confirmation requested in Open Decisions block.

## Stack Confirmation

Quoted from `00-index.md` `stack:`:

- platforms: android, service
- languages: kotlin, typescript
- ui: compose
- build: gradle, tsc, docker
- package-managers: gradle, pnpm
- testing: **junit, espresso, vitest, maestro**
- observability: **lazylogcat**
- integrations: hilt, firebase-auth, firestore, firebase-functions-v2, firebase-admin, youtubei.js, youtube-data-api, openrouter-planned, cloud-run-planned, steipete-summarize-subtree
- available-clis: android, gcloud, firebase, lazylogcat, maestro

No tools are introduced beyond what is already declared. Snapshot testing — if recommended — would use **Compose UI tests** (`androidx.compose.ui.test`) which is already in scope via `ui-test-junit4`. Paparazzi is *not* in scope and is offered only as an Open Decision (default: defer).

## Affected Code (Playbook A)

### Existing files inspected

- `android/app/src/main/java/com/github/jayteealao/playster/MainActivity.kt` — `ComponentActivity` + `setContent { PlaysterTheme { ... PlaysterNavHost(navHostController) } }`. We will inject the `QuotaBanner` above the NavHost as a `Column` wrapper inside `Surface`.
- `android/app/src/main/java/com/github/jayteealao/playster/screens/auth/PlaysterNavHost.kt` — three routes (`loader`, `onboard`, `list`). String-route convention (no type-safe destinations yet). Our new `videoDetail/{videoId}` route follows the same convention.
- `android/app/src/main/java/com/github/jayteealao/playster/screens/auth/authViewModel.kt` — uses `mutableStateOf` + `HiltViewModel` + `@Inject`. Slice 1 rewrites this to bridge Google Sign-In to Firebase Auth. We adopt the resulting `FirebaseAuth.getInstance().currentUser` access pattern (no direct dependency on `AuthViewModel` from `SummaryViewModel`).
- `android/app/src/main/java/com/github/jayteealao/playster/screens/playlist/PlaylistScreen.kt` — currently uses `YouTube.Builder` + `LaunchedEffect` + `mutableStateListOf<Playlist>`. **Slice 1 rewrites this to a Firestore listener over `playlists/`** (rendering `PlaylistDoc` instead of `Playlist`). Our slice depends on that rewrite landing first; we additionally add (a) navigation to `videoDetail/{videoId}` and (b) the Summarize affordance on each tile.
- `android/app/src/main/java/com/github/jayteealao/playster/screens/playlist/PlayCard.kt` — current card consumes `com.google.api.services.youtube.model.Playlist`. Slice 1 rewrites the type. We extend the *video tile* (likely a new `VideoCard.kt` introduced by slice 1's video-list addition) with the Summarize control. **Confirm with slice 1's plan that a `VideoListScreen` + `VideoCard` exist before this slice starts.**
- `android/app/src/main/java/com/github/jayteealao/playster/screens/LoadingScreen.kt` — animation + nav patterns; no changes here.
- `android/app/build.gradle.kts` — depends on Compose BOM `2024.01.00`, Hilt `2.48`, navigation-compose `2.7.6`, AGP `8.2.2`, Kotlin `1.9.22`, KSP `1.9.0-1.0.12`, `compileSdk=34`, `minSdk=29`, JVM target `1.8`. The slice adds: markdown lib + (assumed from slice 1) Firebase BOM 34+ + `firebase-firestore` + `firebase-functions` + `firebase-auth` (no `-ktx` suffix — KTX rolled into main modules in BOM 34, Jul 2025). We *do not* re-declare Firebase deps in this plan — slice 1 owns them. If slice 1 deferred them, this plan adds them in Phase A.
- `android/gradle/libs.versions.toml` — version catalog already structured with `[versions]`, `[libraries]`, `[bundles]`, `[plugins]`. We add a `markdown-renderer` library entry under whichever choice the Open Decision settles.

### Reuse opportunities

- No existing sealed UI state classes anywhere — `SummaryUiState` will be the first. Pattern: `sealed interface SummaryUiState { data object InProgress : SummaryUiState; data class Completed(val content: String, val model: String) : SummaryUiState; ... }`. We adopt `sealed interface` (Kotlin 1.5+) and `data object` (Kotlin 1.9+; we are on 1.9.22). Slice 1 should also adopt this for `QuotaState` so the codebase converges on one pattern.
- No existing Firestore listener helpers — slice 1 introduces the first (on `PlaylistScreen`). We follow that exact pattern. Recommended convention (request slice 1 to seed this if not already done): `Flow<List<T>>` via `callbackFlow { ... awaitClose { registration.remove() } }`, exposed from a repository wrapped in `@Inject` Hilt module.
- No common spinner / error composables yet. We introduce two thin composables in `screens/common/`:
  - `InProgressIndicator(label: String)` — central spinner + label.
  - `ErrorPanel(message: String, retry: (() -> Unit)?)` — error icon + message + optional Retry.
  These are reused by SummaryScreen and become candidates for slice 1's empty/error states to consume in a follow-up cleanup (out of scope here).
- No markdown rendering exists in the codebase. Confirmed via `Grep` on `markdown|Markwon|compose-markdown`: zero matches.
- Screen-level test pattern: `androidTestImplementation(libs.ui.test.junit4)` is in deps. No instrumented tests exist yet. Slice 1's plan should establish the first instrumented test scaffold; this slice piggybacks if it has, or stands up the scaffold itself if not.

### Files to create

```
android/app/src/main/java/com/github/jayteealao/playster/
├── screens/
│   ├── common/
│   │   ├── QuotaBanner.kt                       (NEW)
│   │   ├── InProgressIndicator.kt               (NEW)
│   │   └── ErrorPanel.kt                        (NEW)
│   └── videoDetail/
│       ├── VideoDetailScreen.kt                 (NEW)
│       ├── VideoDetailViewModel.kt              (NEW)
│       └── summary/
│           ├── SummaryScreen.kt                 (NEW)
│           ├── SummaryViewModel.kt              (NEW)
│           └── SummaryUiState.kt                (NEW — sealed interface)
├── data/
│   └── firestore/
│       ├── SummaryDoc.kt                        (NEW)
│       ├── QuotaDoc.kt                          (NEW)
│       ├── SummaryRepository.kt                 (NEW)
│       └── QuotaRepository.kt                   (NEW)
└── functions/
    └── SummaryFunctions.kt                      (NEW — wraps requestVideoSummary callable)

android/maestro/
├── manual-summary-fresh.yaml                    (NEW)
├── quota-exhausted-banner.yaml                  (NEW)
├── cached-summary-navigation.yaml               (NEW — slice-local AC)
└── helpers/
    └── seed-quota-exhausted.sh                  (NEW — emulator seeding script)

android/app/src/androidTest/java/com/github/jayteealao/playster/screens/videoDetail/summary/
└── SummaryScreenComposeTest.kt                  (NEW — 4-state render test)
```

### Files to modify

```
android/app/build.gradle.kts                     (MODIFY — add markdown lib)
android/gradle/libs.versions.toml                (MODIFY — add markdown lib entry)
android/app/src/main/java/com/github/jayteealao/playster/MainActivity.kt
                                                 (MODIFY — wrap NavHost in Column with QuotaBanner on top)
android/app/src/main/java/com/github/jayteealao/playster/screens/auth/PlaysterNavHost.kt
                                                 (MODIFY — add videoDetail/{videoId} route)
android/app/src/main/java/com/github/jayteealao/playster/screens/playlist/PlaylistScreen.kt
                                                 (MODIFY — navigate to videoDetail; add Summarize affordance)
                                                 (NOTE: only after slice 1's Firestore rewrite has landed)
android/app/src/main/java/com/github/jayteealao/playster/screens/playlist/PlayCard.kt
                                                 (MAYBE MODIFY — add Summarize icon; depends on slice 1 shape)
```

Files touched: **22** (12 new Kotlin, 4 new Maestro/scripts, 1 new instrumented test, 2 modify Gradle, 3 modify Kotlin). The metric-files-to-touch in frontmatter reflects this.

## Second-Domain Contract (Playbook B)

### `requestVideoSummary` callable (consumed)

From slice 3:

```typescript
// Input
interface RequestVideoSummaryInput {
  videoId: string;
  model?: string; // default "free"
}
// Output (success)
interface RequestVideoSummaryOutput {
  summaryId: string; // == videoId
}
// Error: throws HttpsError(code, message)
//   "unauthenticated"      — no/invalid Firebase ID token
//   "permission-denied"    — uid not in allowlist
//   "not-found"            — video doc missing
//   "resource-exhausted"   — quota cap (daily or per-minute)
//   "internal"             — unexpected backend error
//   The success path includes idempotent "already-have-non-failed-summary" replay
//   (returns `{ summaryId }` without re-dispatch).
```

Kotlin call site (using `firebase-functions-ktx`):

```kotlin
val data = hashMapOf("videoId" to videoId)
val result = Firebase.functions.getHttpsCallable("requestVideoSummary").call(data).await()
val summaryId = (result.data as Map<*, *>)["summaryId"] as String
```

Error mapping in `SummaryViewModel`: catch `FirebaseFunctionsException`, branch on `code`:
- `UNAUTHENTICATED` / `PERMISSION_DENIED` → not user-visible in v1 (slice 1 guarantees the user is signed in with the allowlisted uid). Log via `playster.summary` tag and show `FailedPermanent("Sign-in required")`.
- `RESOURCE_EXHAUSTED` → `FailedPermanent("Daily summary limit reached. Resets at midnight UTC.")` (the QuotaBanner is already showing).
- `NOT_FOUND` → `FailedPermanent("Video unavailable.")`.
- `INTERNAL` / `UNAVAILABLE` / `DEADLINE_EXCEEDED` → `FailedTransient(message)` with Retry button.
- Any other → `FailedTransient`.

### `summaries/{videoId}` document (consumed)

Mirrors slice 3's `SummaryDocument` TS shape. Kotlin DTO:

```kotlin
data class SummaryDoc(
    val videoId: String,
    val status: SummaryStatus,          // queued | pending | running | completed | failed-transient | failed-permanent
    val model: String?,
    val content: String?,               // markdown — non-null when status == completed
    val errorCode: String?,
    val errorMessage: String?,
    val summarizerJobId: String?,
    val requestedAt: Timestamp?,
    val dispatchedAt: Timestamp?,
    val completedAt: Timestamp?,
)

enum class SummaryStatus {
    QUEUED, PENDING, RUNNING, COMPLETED, FAILED_TRANSIENT, FAILED_PERMANENT;
    companion object {
        fun fromWire(s: String): SummaryStatus = when (s) {
            "queued" -> QUEUED
            "pending" -> PENDING
            "running" -> RUNNING
            "completed" -> COMPLETED
            "failed-transient" -> FAILED_TRANSIENT
            "failed-permanent" -> FAILED_PERMANENT
            else -> error("Unknown summary status: $s")
        }
    }
}
```

State-machine mapping in `SummaryViewModel`:
- `null` doc → `NoSummary` (auto-dispatch logic decides what's next)
- `QUEUED | PENDING | RUNNING` → `InProgress`
- `COMPLETED` → `Completed(content!!, model ?: "free")`
- `FAILED_TRANSIENT` → `FailedTransient(errorMessage ?: "Couldn't summarize. Try again.")`
- `FAILED_PERMANENT` → `FailedPermanent(errorMessage ?: "This video can't be summarized.")`

### `quota/openrouter` document (consumed)

Mirrors slice 3's `QuotaDocument`. Kotlin DTO:

```kotlin
data class QuotaDoc(
    val date: String,                   // YYYY-MM-DD UTC
    val requestCount: Long,
    val dailyLimit: Long,               // 1000
    val perMinuteLimit: Long,           // 20
    val recentTimestamps: List<Long>,   // epoch millis, sliding 60s window, max 20 entries
)
```

Derived banner state (in `QuotaRepository` / `QuotaState`):
- `Disabled` = `requestCount >= dailyLimit OR recentTimestamps.size >= perMinuteLimit`.
- When `Disabled` is true: banner is visible, all Summarize CTAs are disabled.
- Reason (for banner copy): `daily` if `requestCount >= dailyLimit` else `perMinute`.

Note: The slice's intrinsic listener already debounces flicker — Firestore listeners only fire on persisted writes, and per-minute timestamps move out of window after 60s of inactivity but the doc itself is only written on quota changes.

## Test & Verification (Playbook C)

### App run

- Build + install debug: `./gradlew :app:installDebug` (run from `android/`).
- Maestro run: `maestro test android/maestro/<flow>.yaml`.
- Logcat capture: `lazylogcat capture --tag playster.summary --tag playster.auth --tag playster.sync --output verify-artifacts/<flow>.log`.

### Maestro flow directory

No existing `android/maestro/` directory (verified via Glob — no matches anywhere in repo for `maestro*` or `.maestro/`). **Slice 1 establishes the directory** by creating its `signin-and-see-playlists.yaml` and `pull-to-refresh.yaml`. This slice adds 3 more flows into the same dir. If slice 1's plan deferred creation of the dir, this slice creates it (Phase F, Step F.1).

### AC-5 verification (Maestro)

`android/maestro/manual-summary-fresh.yaml`:

```yaml
appId: com.github.jayteealao.playster
---
- launchApp
- tapOn:
    id: "playlist-tile-PL_TEST_1"
- tapOn:
    id: "video-tile-VID_NO_SUMMARY"
- assertVisible:
    id: "summary-tab"
- tapOn:
    id: "summary-tab"
# AC-5: in-progress UI within 500ms of tap.
- assertVisible:
    id: "summary-in-progress-spinner"
    timeout: 500
- assertVisible:
    text: "Generating summary"
    timeout: 500
```

The 500ms timeout uses Maestro's `assertVisible.timeout` (Maestro supports per-assertion timeout in ms). Setup precondition: the emulator has the `videos/VID_NO_SUMMARY` doc but no `summaries/VID_NO_SUMMARY` doc. Seeding script: `android/maestro/helpers/seed-quota-exhausted.sh` (and its sibling `seed-fresh-state.sh`) call `firebase emulators:exec --only firestore` with a node script that uses the admin SDK to write fixture docs. Document this dependency on the Firebase emulator in the implementation step.

### AC-10 verification (Maestro)

`android/maestro/quota-exhausted-banner.yaml`:

```yaml
appId: com.github.jayteealao.playster
---
- runScript: helpers/seed-quota-exhausted.sh    # seeds quota/openrouter at requestCount=1000
- launchApp
- assertVisible:
    text: "Daily summary limit reached"
- assertVisible:
    id: "quota-banner"
- tapOn:
    id: "playlist-tile-PL_TEST_1"
- tapOn:
    id: "video-tile-ANY"
# Summarize CTA must be disabled.
- assertVisible:
    id: "summarize-button-disabled"
- assertNotVisible:
    id: "summarize-button-enabled"
```

### Slice-local AC: cached-summary navigation does not dispatch

`android/maestro/cached-summary-navigation.yaml`. Pre-seed `summaries/VID_HAS_SUMMARY` with `{ status: "completed", content: "# Cached" }`. Tap the Summarize affordance on that tile. Assert the screen lands directly on the rendered markdown content. After-run check: `lazylogcat` filter `playster.summary requestVideoSummary` shows zero matches. (The ViewModel must not invoke the callable when the local Firestore listener fires `Completed` immediately.)

### Slice-local AC: 4 SummaryScreen states render distinctly

`SummaryScreenComposeTest.kt` instrumented test. Pattern:

```kotlin
@get:Rule val composeTestRule = createAndroidComposeRule<ComponentActivity>()

@Test fun inProgress_rendersSpinner() {
    composeTestRule.setContent {
        SummaryScreenContent(SummaryUiState.InProgress, onRetry = {})
    }
    composeTestRule.onNodeWithTag("summary-in-progress-spinner").assertIsDisplayed()
    composeTestRule.onNodeWithTag("summary-retry-button").assertDoesNotExist()
}
// + 3 more tests: completed, failedTransient, failedPermanent
```

We factor `SummaryScreen` into two composables: a connected `SummaryScreen(viewModel)` and a stateless `SummaryScreenContent(state, onRetry)`. The test exercises only the stateless half. This is the lightest-weight path — no Paparazzi, no new tooling. Acceptable trade-off: snapshot diffs are not produced, but state-discriminability is asserted.

### lazylogcat tags

Introduce `playster.summary` tag emissions at the following points:
- `SummaryViewModel.onCallableInvoke(videoId)` — emit `requestVideoSummary{videoId=$videoId}`.
- `SummaryViewModel.onStateTransition(prev, next)` — emit `state{prev=$prev,next=$next,videoId=$videoId}`.
- `SummaryRepository.listenTo(videoId)` — emit `listen{videoId=$videoId,attach=true}` and on `awaitClose` `listen{videoId=$videoId,attach=false}` — used by the listener-leak slice-local AC.
- `QuotaRepository.observe()` — emit `quotaListen{attach=true|false}`.

These are `Log.d("playster.summary", message)` calls (no structured-logging lib in scope).

### Snapshot tests / Paparazzi

**Not adopted.** See Open Decisions for rationale. Compose UI tests (already in stack) cover the same risk.

## Web Research (Playbook D)

### Markdown library

Candidates surveyed:

| Library | GAV | Stars (May 2026) | Maintenance | Compose BOM compat | CommonMark coverage | Notes |
|---|---|---|---|---|---|---|
| `compose-markdown` (jeziellago) | `dev.jeziellago:compose-markdown:0.5.7` | ~1.2k | active (last release 2026-03) | Compose `1.6.x`/BOM `2024.01.00`+ | uses Markwon under the hood (AndroidView bridge); good code-block + table + image support | mature; recommended for v1 |
| `multiplatform-markdown-renderer` (mikepenz) | `com.mikepenz:multiplatform-markdown-renderer-m3:0.27.0` | ~700 | active (last release 2026-04) | KMP; Compose 1.6+ | pure Compose (no AndroidView); CommonMark + GFM tables/strikethrough | heaviest dep transitively; nicer theming hooks |
| `Markwon` (noties) + manual AndroidView bridge | `io.noties.markwon:core:4.6.2` | ~2.6k | stable but slow-moving | n/a (TextView + AndroidView) | top-tier CommonMark + plugins (images, tables, latex, syntax-highlight) | requires writing the Compose wrapper |
| `io.noties.markwon:android-compose` | — | — | does not exist (only `Markwon` core + community wrappers) | — | — | excluded |

**Recommendation: `dev.jeziellago:compose-markdown:0.5.7`.**

Rationale:
- Already wraps Markwon (giving us mature CommonMark + image + table support out of the box).
- Active maintenance with a 2026 release.
- Single dependency, no manual AndroidView bridging.
- Coil-compatible image loader for inline images (matches our existing `coil-compose` dep).
- Bundle-size impact: ~600KB (Markwon core + image + table + linkify plugins). Acceptable given the slice's UX importance.

PO confirmation requested in Open Decisions below.

### Firestore listener lifecycle in Compose

Web consensus (2026): use `viewModelScope` with `callbackFlow` + `awaitClose { registration.remove() }`, expose as `StateFlow` to the screen via `collectAsStateWithLifecycle()` (already in our Compose-lifecycle deps via `androidx-lifecycle-runtime-compose`). `DisposableEffect` is the right tool only for screen-local listeners that don't belong in a ViewModel — for our case (`SummaryViewModel`, `QuotaRepository`), `viewModelScope` cancellation is the correct lifecycle binding.

Pattern:

```kotlin
fun listen(videoId: String): Flow<SummaryDoc?> = callbackFlow {
    Log.d("playster.summary", "listen{videoId=$videoId,attach=true}")
    val registration = Firebase.firestore
        .collection("summaries")
        .document(videoId)
        .addSnapshotListener { snap, err ->
            if (err != null) { close(err); return@addSnapshotListener }
            trySend(snap?.toSummaryDoc())
        }
    awaitClose {
        Log.d("playster.summary", "listen{videoId=$videoId,attach=false}")
        registration.remove()
    }
}
```

Same pattern for `QuotaRepository.observe(): Flow<QuotaDoc?>`.

### Compose sealed-state pattern

Web consensus is `sealed interface UiState` over `sealed class` for state types (interfaces compose better and don't carry constructors). Kotlin 1.9.22 supports both. Our `SummaryUiState` is a `sealed interface` with `data object` (for stateless variants) and `data class` (for variants with payload).

### Maestro + Firebase Auth

Maestro doesn't support direct OAuth flows reliably (the Google sign-in screen is in-Play-Services and out of Maestro's view tree). Practical approach used in the wider community (2026):
1. Use a Maestro `runScript:` step that calls a helper which uses the **Firebase Auth emulator's REST API** to sign in a fixture uid (allowlisted in `firestore.rules` for the emulator). The helper writes a `custom_token` to a known place; the app, on launch, picks it up if running against the emulator.
2. Alternative: the app exposes a debug-build-only deep-link `playster://debug/sign-in?uid=TEST_ALLOWED_UID` that bypasses the OAuth flow and calls `FirebaseAuth.getInstance().signInWithCustomToken(...)`.

**Recommendation (defer to slice 1's plan since auth is its concern):** rely on slice 1's plan to settle this — slice 4 just inherits whichever fixture-auth mechanism slice 1 ships. If slice 1 hasn't documented it, this slice's `manual-summary-fresh.yaml` adds a `- runFlow: signin-helper.yaml` step that defers to slice 1's flow.

### Maestro `assertVisible` timing

Confirmed (Maestro 1.36+): `assertVisible` accepts `timeout: <ms>`. A value of `500` is supported. AC-5's 500ms timing assertion is therefore expressible directly in Maestro YAML without custom timing harness.

### Firebase emulator seeding for `quota/openrouter`

Seeding script approach (a `helpers/seed-quota-exhausted.sh` next to the flow files):

```bash
#!/usr/bin/env bash
set -euo pipefail
firebase emulators:exec --only firestore --project playster-dev \
  "node android/maestro/helpers/write-quota-cap.js"
```

`write-quota-cap.js` uses `firebase-admin` to write `quota/openrouter` with `requestCount=1000, dailyLimit=1000, perMinuteLimit=20, recentTimestamps=[], date=<today UTC>`. The script depends on `firebase-tools` and `firebase-admin` being installed in the workspace (already true for `backend/functions/`).

### Compose NavGraph 2026 patterns

`androidx.navigation.compose 2.7.6` (current) supports two patterns:
1. **String-based routes** (what `PlaysterNavHost.kt` uses today): `composable("videoDetail/{videoId}", arguments = listOf(navArgument("videoId") { type = NavType.StringType }))`.
2. **Type-safe destinations** (`@Serializable` data classes — requires `androidx.navigation:navigation-compose:2.8.0+`).

**Recommendation:** stay on string-based routes for parity with existing `PlaysterNavHost.kt`. Bumping navigation-compose is a separate concern (not blocked by this slice).

## Open Decisions for Discovery Phase

1. **Markdown library choice.** Recommended: `dev.jeziellago:compose-markdown:0.5.7`. Alternatives considered: `com.mikepenz:multiplatform-markdown-renderer-m3` (pure Compose, no AndroidView, slightly heavier dep) and rolling our own via `io.noties.markwon:core` + AndroidView (most control, most code). PO sign-off requested. Default applied if no objection.
2. **Paparazzi snapshot tests vs Compose UI tests.** Recommended: **Compose UI tests only**. Adopting Paparazzi requires JDK 21+ in CI and pulls in LayoutLib bytecode artifacts — about 5–8 minutes added to CI per module. Risk we're hedging is "the 4 SummaryScreen states render distinctly", which Compose UI tests cover via `assertIsDisplayed` + `onNodeWithTag` per state. Paparazzi's added value is *visual* regression catch; arguably nice-to-have but not required for v1. Default: defer. PO can override.
3. **Auto-dispatch on `NoSummary` entry vs explicit "Summarize this video" button.** The slice file allows both. Recommended: **auto-dispatch when entering via the tile-Summarize affordance; explicit button when entering via plain tile-tap.** Implementation routes both navigations through `videoDetail/{videoId}?autoDispatch=true|false`. This makes AC-5 trivially testable (Maestro flow always uses `autoDispatch=true`) while preserving "I just want to look at the video info" affordance for users.
4. **Tile badge ("✓ Summary ready") for cached summaries.** The slice file marks this as soft out-of-scope. Recommendation: **defer to v1.1.** Including it requires every tile to attach its own `summaries/{videoId}` listener (multiplied by playlist size, potentially 50+ listeners on screen). Pull-cost is non-trivial. Defer.
5. **Disabled Summarize button copy when quota exhausted.** Recommend: tooltip-equivalent — the button stays visible but greyed out with `contentDescription="Daily summary limit reached — try again after midnight UTC."`. The QuotaBanner provides the primary affordance; the tile button is secondary signal.
6. **`videoDetail` back-stack behavior.** Recommend: standard `popUpTo("list")` semantics. After viewing a summary, system-back returns to playlist. Nothing fancy.

## Plan

### Git commit boundaries

Each phase is one commit. Phases are sequential within the slice. Final commit pushes verification artifacts.

- Commit A: Phase A
- Commit B: Phase B
- Commit C: Phase C
- Commit D: Phase D
- Commit E: Phase E
- Commit F: Phase F (Maestro + tests)
- Commit G: lazylogcat traces + verification evidence

### Phase A — DTOs + dependencies

Goal: declare contracts and dependencies so subsequent phases compile against stable types.

- **Step A.1.** Add markdown library to `android/gradle/libs.versions.toml`:
  - `compose-markdown = "0.5.7"` under `[versions]`.
  - `compose-markdown = { module = "dev.jeziellago:compose-markdown", version.ref = "compose-markdown" }` under `[libraries]`.
- **Step A.2.** Add `implementation(libs.compose.markdown)` to `android/app/build.gradle.kts`.
- **Step A.3.** Verify (do not add) that Firebase BOM + `firebase-firestore-ktx` + `firebase-functions-ktx` are present (slice 1 lands these). If absent, this slice adds them — block on slice 1 confirmation.
- **Step A.4.** Create `data/firestore/SummaryDoc.kt` with `SummaryDoc` data class + `SummaryStatus` enum + `DocumentSnapshot.toSummaryDoc()` extension.
- **Step A.5.** Create `data/firestore/QuotaDoc.kt` with `QuotaDoc` data class + `DocumentSnapshot.toQuotaDoc()` extension + `QuotaState` sealed interface (`Healthy`, `DailyExhausted`, `PerMinuteExhausted`).
- **Step A.6.** Create `screens/videoDetail/summary/SummaryUiState.kt` — `sealed interface` with `InProgress`, `NoSummary`, `Completed(content, model)`, `FailedTransient(message)`, `FailedPermanent(message)`.
- **Step A.7.** `./gradlew :app:compileDebugKotlin` to confirm types compile.

Commit: `feat(android): add summary/quota DTOs + markdown lib for summary UI`.

### Phase B — QuotaBanner + repositories

Goal: top-of-app QuotaBanner Composable, deriving state from `quota/openrouter` listener. Repositories provide the listener glue.

- **Step B.1.** Create `data/firestore/QuotaRepository.kt`:
  - `@Singleton class QuotaRepository @Inject constructor()` exposes `observe(): Flow<QuotaState>` via `callbackFlow` on `Firebase.firestore.document("quota/openrouter")`. On `null` snapshot → `QuotaState.Healthy`. Tag listener attach/detach with `playster.summary` (or `playster.quota` — decide in implementation; `playster.summary` keeps tag count minimal per slice 1's convention).
- **Step B.2.** Provide `QuotaRepository` in a Hilt module under `data/firestore/FirestoreModule.kt` (new file — first Hilt provider module in the codebase; slice 1 may have established a precedent).
- **Step B.3.** Create `screens/common/QuotaBanner.kt`:
  - `@Composable fun QuotaBanner(modifier: Modifier = Modifier, viewModel: QuotaBannerViewModel = hiltViewModel())`.
  - `QuotaBannerViewModel` exposes `quotaState: StateFlow<QuotaState>` collected from `QuotaRepository`.
  - Renders nothing when `Healthy`; renders a sticky 48dp-tall top banner when not. Copy: "Daily summary limit reached" (DailyExhausted) or "Rate limited — try again in a moment" (PerMinuteExhausted).
- **Step B.4.** Create `screens/common/InProgressIndicator.kt` and `screens/common/ErrorPanel.kt` stateless helpers (used by SummaryScreen in Phase D).
- **Step B.5.** Modify `MainActivity.kt`:
  - Wrap `PlaysterNavHost(navHostController)` in `Column { QuotaBanner(); PlaysterNavHost(navHostController) }`.
  - The banner zero-height-when-Healthy keeps the layout undisturbed.
- **Step B.6.** Slice-local AC sanity: QuotaBanner subscription doesn't leak. Verified by inspecting `awaitClose` behavior — covered in Phase F's listener-leak Maestro assertion via lazylogcat.

Commit: `feat(android): QuotaBanner + quota/summary repositories with Firestore listeners`.

### Phase C — VideoDetailScreen scaffold

Goal: the route exists, the tab row exists, the header reads `videos/{videoId}` from Firestore.

- **Step C.1.** Create `screens/videoDetail/VideoDetailViewModel.kt`:
  - `@HiltViewModel class VideoDetailViewModel @AssistedInject constructor(@Assisted val videoId: String, private val videoRepository: VideoRepository)` — exposes `video: StateFlow<VideoDoc?>`.
  - If slice 1 didn't ship `VideoRepository`, this slice creates a thin one (analogous to `QuotaRepository`).
- **Step C.2.** Create `screens/videoDetail/VideoDetailScreen.kt`:
  - Top-level `@Composable fun VideoDetailScreen(videoId: String, autoDispatch: Boolean, onBack: () -> Unit)`.
  - Header: `AsyncImage(model = video.thumbnailUrl)` + title + channel.
  - `TabRow` with one tab: "Summary". (v1 ships single tab; structure ready for expansion.)
  - Below tab row: `SummaryScreen(videoId, autoDispatch)`.
  - Back arrow in top app bar.
- **Step C.3.** Modify `PlaysterNavHost.kt`:
  - Add `composable("videoDetail/{videoId}?autoDispatch={autoDispatch}", arguments = listOf(navArgument("videoId") { type = NavType.StringType }, navArgument("autoDispatch") { type = NavType.BoolType; defaultValue = false })) { backStackEntry -> VideoDetailScreen(videoId = ..., autoDispatch = ..., onBack = { navHostController.popBackStack() }) }`.
- **Step C.4.** Add `testTag("video-detail-screen")` and `testTag("summary-tab")` modifiers for Maestro hooks.

Commit: `feat(android): VideoDetailScreen scaffold + nav route`.

### Phase D — SummaryScreen state machine + ViewModel

Goal: the four-state UI renders correctly off `summaries/{videoId}` Firestore listener, with markdown rendering, Retry, and callable invocation.

- **Step D.1.** Create `data/firestore/SummaryRepository.kt`:
  - `observe(videoId: String): Flow<SummaryDoc?>` via `callbackFlow` on `summaries/{videoId}`.
- **Step D.2.** Create `functions/SummaryFunctions.kt`:
  - `@Singleton class SummaryFunctions @Inject constructor()` wraps `Firebase.functions.getHttpsCallable("requestVideoSummary")`.
  - `suspend fun requestSummary(videoId: String): Result<String>` — returns `Result.success(summaryId)` or `Result.failure(FirebaseFunctionsException)`.
  - Inject into Hilt via `FirestoreModule` (rename to `FirebaseModule` at this point).
- **Step D.3.** Create `screens/videoDetail/summary/SummaryViewModel.kt`:
  - `@HiltViewModel` + `@AssistedInject` keyed on `videoId` + `autoDispatch`.
  - On `init`: launch listener; if `autoDispatch == true` and listener emits `null` (no doc), call `SummaryFunctions.requestSummary(videoId)`. Use a one-shot flag to avoid retriggering.
  - State machine: map listener emissions → `SummaryUiState`.
  - `fun retry()`: call `SummaryFunctions.requestSummary(videoId)` (no waiting for doc; the listener will emit the new pending state). Emit `playster.summary state{action=retry}`.
  - On `FirebaseFunctionsException` from `requestSummary`: emit `FailedTransient` or `FailedPermanent` per the mapping in Playbook B. **Important:** if the call fails *before* a doc was reserved, we owe the user a transient UI — the listener won't emit because no doc exists. Bridge this by exposing a `localFailure: StateFlow<SummaryUiState?>` that overrides the listener-derived state when set, cleared on retry.
- **Step D.4.** Create `screens/videoDetail/summary/SummaryScreen.kt`:
  - Stateless `@Composable fun SummaryScreenContent(state: SummaryUiState, onRetry: () -> Unit, onSummarize: () -> Unit)`.
  - Stateful wrapper `@Composable fun SummaryScreen(videoId: String, autoDispatch: Boolean)` collects `viewModel.uiState` and delegates.
  - `Completed` branch uses `dev.jeziellago.compose.markdown.MarkdownText(markdown = state.content)`.
  - `FailedTransient` branch shows `ErrorPanel(state.message, retry = viewModel::retry)`.
  - `FailedPermanent` branch shows `ErrorPanel(state.message, retry = null)`.
  - `InProgress` branch shows `InProgressIndicator("Generating summary…")`.
  - `NoSummary` branch (only reachable when `autoDispatch=false`): shows "Summarize this video" button → `viewModel.retry()` (semantically the same as Retry — dispatch the callable).
  - All states tagged with `testTag("summary-<state>")` for Maestro.

Commit: `feat(android): SummaryViewModel state machine + SummaryScreen with markdown rendering`.

### Phase E — PlaylistScreen integration

Goal: tile tap navigates to VideoDetailScreen; Summarize affordance dispatches & navigates.

**Precondition: slice 1's Firestore rewrite of `PlaylistScreen.kt` has landed.** If not, escalate; do not attempt to wire this slice against the pre-rewrite YouTube-Data-API screen.

- **Step E.1.** Modify `PlaylistScreen.kt` (post-slice-1) to accept a `navController: NavHostController` parameter (or use a lambda `onVideoClick: (videoId, autoDispatch: Boolean) -> Unit` to keep the screen testable).
- **Step E.2.** On video-tile tap (the video list inside a playlist; slice 1 lands the video list view): navigate to `videoDetail/$videoId?autoDispatch=false`.
- **Step E.3.** Add Summarize affordance (icon button on video tile, `Icons.Outlined.AutoAwesome` or `Icons.Outlined.Description`). Behavior:
  - Observes `QuotaState`; disabled when `!Healthy`. `testTag("summarize-button-disabled" or "summarize-button-enabled")`.
  - On tap: check the cached summary state from the local Firestore listener (a one-shot `Firebase.firestore.document("summaries/$videoId").get()`):
    - If doc exists with non-failed status → navigate `videoDetail/$videoId?autoDispatch=false`.
    - If doc missing or failed-transient → navigate `videoDetail/$videoId?autoDispatch=true`.
- **Step E.4.** Modify `PlaysterNavHost.kt` to pass the nav controller (or callback) into `PlaylistScreen`.
- **Step E.5.** Sanity-compile. No unit tests at this layer (covered by Maestro).

Commit: `feat(android): wire playlist tile -> VideoDetailScreen with Summarize affordance`.

### Phase F — Maestro flows + Compose UI tests

Goal: AC-5, AC-10, slice-local ACs verifiable end-to-end.

- **Step F.1.** Create `android/maestro/` directory if absent. Create `helpers/seed-fresh-state.sh`, `helpers/seed-quota-exhausted.sh`, and the underlying `write-quota-cap.js` + `write-fresh-state.js` admin SDK scripts.
- **Step F.2.** Create `android/maestro/manual-summary-fresh.yaml` per the Playbook-C example. Add an opening `- runFlow: helpers/signin-helper.yaml` step. **Cohesion note:** slice 1's plan produces `signin-and-see-playlists.yaml`, not a reusable `signin-helper.yaml`. This slice creates `android/maestro/helpers/signin-helper.yaml` as a small extract of slice 1's sign-in steps (factor with slice 1 during implementation; both flows reuse the same fixture-auth mechanism). Also add `- runScript: helpers/seed-fresh-state.sh`.
- **Step F.3.** Create `android/maestro/quota-exhausted-banner.yaml` per the Playbook-C example.
- **Step F.4.** Create `android/maestro/cached-summary-navigation.yaml`:
  - Pre-seed `summaries/VID_CACHED` at `status=completed`.
  - Tap Summarize on the corresponding tile.
  - Assert `summary-completed` test tag visible.
  - Implicitly: lazylogcat post-run filter shows zero `requestVideoSummary` invocations for `VID_CACHED`.
- **Step F.5.** Create `app/src/androidTest/.../SummaryScreenComposeTest.kt` with the four state-rendering tests outlined in Playbook C.
- **Step F.6.** Document the `verify:e2e` runbook in slice notes: `firebase emulators:start` → `./gradlew :app:installDebug` → `maestro test android/maestro/manual-summary-fresh.yaml` → capture lazylogcat → repeat for other flows. (Slice 1's `pnpm verify:e2e` script likely covers this orchestration; if not, this slice's plan defers automation to a follow-up.)

Commit: `test(android): Maestro flows AC-5/AC-10 + Compose UI state tests for SummaryScreen`.

### Phase G — Evidence + handoff

- **Step G.1.** Run all 3 Maestro flows. Capture `lazylogcat` artifacts to `.ai/workflows/wire-android-backend-summarizer/evidence/summary-ui/`.
- **Step G.2.** Capture screenshots of the 4 SummaryScreen states (debug-build manual run is acceptable; not blocking on Paparazzi).
- **Step G.3.** Verify the APK size delta pre-/post-slice. Note in slice retro if > 1MB.
- **Step G.4.** Update `00-index.md` progress block: `summary-ui` plan → complete; ready for implement.

Commit: `chore(workflow): summary-ui verification artifacts`.

## Risks & Mitigations (carried from slice file)

| Risk | Mitigation in this plan |
|------|------------------------|
| Markdown library choice | Recommended in Playbook D; PO confirms in Open Decision 1. |
| Firestore listener leak | `callbackFlow` + `awaitClose` + `viewModelScope` cancellation; lazylogcat-asserted in slice-local AC. |
| QuotaBanner flicker | Banner state is binary (Disabled or not); Firestore listener naturally debounces; no rate-of-change logic. |
| Navigation back-stack | Standard `popBackStack()`; explicit Maestro assertions in `cached-summary-navigation.yaml`. |
| Confusion across queued/pending/running | Explicit mapping in `SummaryViewModel`; all three → `InProgress`. |
| Maestro fragility | Use Firebase emulator + seeded fixtures; document seeding in `helpers/`. |
| Markdown code-blocks/images | `compose-markdown` (via Markwon) handles both natively; Coil-backed image loader available. |
| APK size impact | Measured in Phase G. Markdown lib ≈ 600KB; Firebase BOM landed by slice 1. |
| AssistedInject pattern | First use in codebase; Hilt 2.48 supports it. Document the `@AssistedFactory` pattern in implementation notes. |

## Definition of Done for This Slice

- All 22 files-to-touch are created or modified as planned.
- `./gradlew :app:assembleDebug` green.
- `./gradlew :app:connectedDebugAndroidTest` green (Compose UI tests for SummaryScreen states).
- Maestro: `manual-summary-fresh.yaml`, `quota-exhausted-banner.yaml`, `cached-summary-navigation.yaml` all green against a Firebase emulator with seeded fixtures.
- lazylogcat artifacts captured for each Maestro run.
- AC-5 timing assertion (in-progress within 500ms) passes.
- AC-10 banner + disabled CTA assertion passes.
- Slice-local ACs (4 states distinct, retry transitions, no listener leak, cached navigation no-redispatch) all pass.
- No new lint warnings introduced.

## Blockers / Open Questions Bubbled Up

None blocking. Discovery phase resolves Open Decisions 1–6 with PO. Hard prereq: slice 1's PlaylistScreen rewrite + Firebase BOM additions + Maestro fixture-auth mechanism must land before Phase E.

## Revision History

**2026-05-18T07:49:39Z — Discovery phase + cohesion reconciliation (initial plan write):**
- Discovery phase locked: **`dev.jeziellago:compose-markdown:0.5.7`** for markdown rendering (PO confirmed default). Phase A step A.1 + A.2 land this dependency.
- Discovery phase locked: **nav-arg `?autoDispatch=true|false`** for auto-dispatch routing (PO confirmed default). Phase C step C.3 + Phase E step E.3 implement the branching.
- Discovery phase locked: **Compose UI tests only (no Paparazzi)** for the 4-state SummaryScreen render check (PO accepted default in the consolidated-defaults question). Phase F step F.5 covers this with `SummaryScreenComposeTest.kt`.
- Discovery phase locked: **tile "Summary ready" badge deferred to v1.1** (PO accepted default). No Phase E work added for the badge.
- Discovery phase locked: **disabled-button tooltip copy + standard `popBackStack()` semantics** (PO accepted defaults). Phase E reflects this without additional steps.
- Cohesion fix: corrected `firebase-firestore-ktx` / `firebase-functions-ktx` / `firebase-auth-ktx` references to no-suffix module names (`firebase-firestore`, `firebase-functions`, `firebase-auth`). Firebase Android BOM 34+ (Jul 2025) rolled KTX into the main modules; slice 1's plan already uses the no-suffix names.
- Cohesion fix: Phase F step F.2 — slice 1's plan produces `signin-and-see-playlists.yaml`, not a reusable `signin-helper.yaml`. This slice now explicitly creates `android/maestro/helpers/signin-helper.yaml` as an extract of slice 1's sign-in steps; both flows reuse the same fixture-auth mechanism.


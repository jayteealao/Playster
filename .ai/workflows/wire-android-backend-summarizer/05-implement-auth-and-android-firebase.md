---
schema: sdlc/v1
type: implement
slug: wire-android-backend-summarizer
slice-slug: auth-and-android-firebase
status: complete
stage-number: 5
created-at: "2026-05-18T10:50:00Z"
updated-at: "2026-05-18T10:50:00Z"
metric-files-changed: 30
metric-lines-added: 1717
metric-lines-removed: 427
metric-deviations-from-plan: 3
metric-review-fixes-applied: 0
commit-sha: "d696d2973f68294a2e805aa6bd8b715d50d96ccb"
tags: [android, firebase-auth, firestore, backend, onCall, cleanup]
refs:
  index: 00-index.md
  implement-index: 05-implement.md
  slice-def: 03-slice-auth-and-android-firebase.md
  plan: 04-plan-auth-and-android-firebase.md
  siblings: []
  verify: 06-verify-auth-and-android-firebase.md
next-command: wf-verify
next-invocation: "/wf verify wire-android-backend-summarizer auth-and-android-firebase"
---

# Implement: Auth + Android Firebase view (atomic flip)

## Summary of Changes

Atomic flip of the three sync HTTP triggers from open `onRequest` to allowlisted
`onCall`, plus the Android device's playlist data source from device-side
YouTube Data API to a Firestore live view bridged by Firebase Auth. Both halves
land in the same branch so the app cannot regress to calling the deleted HTTP
endpoints.

**Backend.** `firebase-functions` bumped `^6.3.0 → ^7.2.5` with no source-side
fixes needed beyond the planned changes. New `auth/verify.ts` exports
`requireAllowlistedUid`, `allowlistedCall<TIn,TOut>` (an `onCall` wrapper that
runs the gate then the handler), and the `ALLOWED_UID` `defineString` param
(default `"__BOOTSTRAP_UID__"`). `syncAllPlaylists`, `syncPlaylist`,
`syncWatchLater` are now callables; `scheduledSync` stays on `onSchedule` (no
auth context — it's a cron). `firestore.rules` swaps the deny-all stub for an
allowlisted-uid read on `playlists/`, `videos/`, `sync_state/`, hard-deny on
`tokens/`, and a default-deny catch-all that slice 3 will extend with
`summaries/` + `quota/`. Emulator block added to `firebase.json` on the
standard ports. Vitest + `@firebase/rules-unit-testing` scaffolding under
`backend/functions/tests/` with both rules tests (AC-1/AC-15) and callable
tests (AC-1/AC-2) — emulator-dependent tests are written and type-check; running
them is the verify stage's gate.

**Android.** Firebase BOM `34.0.0` added (no `-ktx` suffix per BOM 34+
migration); `firebase-auth`, `firebase-firestore`, `firebase-functions`, and
`kotlinx-coroutines-play-services` wired in. The `com.google.gms.google-services`
plugin is applied to `:app`. `google-services.json` is gitignored — operator
supplies it locally. Four YouTube Data API artifacts removed:
`google-api-client-android`, `google-api-services-youtube`, `google-api-client-gson`,
`google-http-client-android` (still excluding `httpclient` + `commons-logging`
transitively because those keep showing up). New `data/` package: `PlaylistDoc`,
`VideoDoc`, `FirestoreRepository` (snapshot-listener `Flow`s with
`awaitClose { listener.remove() }`), `FirebaseAuthBridge` (`signInWithGoogleIdToken`
+ `currentUid: StateFlow<String?>` from `AuthStateListener`), and an Hilt
`AppModule` providing the three Firebase singletons. `AuthViewModel` is now
roughly a third of its former size — `GoogleAccountCredential` and `YouTubeScopes`
are gone; `loggedIn` is a `StateFlow<Boolean>` mapped from the bridge.
`AuthScreen` drops the legacy `GoogleSignIn` and `One-Tap` paths, the primary
button now triggers Credential Manager and the resulting `GoogleIdTokenCredential.idToken`
is handed to `authViewModel.bridgeToFirebase(...)`. `PlaylistScreen` is rebuilt
around a `PlaylistViewModel` that consumes `FirestoreRepository.playlistsFlow()`
and invokes `Firebase.functions.getHttpsCallable("syncAllPlaylists").call()` on
pull-to-refresh. A stub `VideoListScreen` + `VideoListViewModel` consume
`videosFlow(playlistId)` so the nav graph can land tile-tap navigation before
slice 4 builds the detail/summary UX on top. `QuotaBanner` is a no-op
placeholder reserving slot in the layout.

**Operations.** `docs/operations/bootstrap-allowlisted-uid.md` documents the
two-pass deploy procedure (placeholder sentinel → operator captures uid → real
allowlist redeploy) including the env-file `defineString` mechanism, the
secret-param alternative, and a "recovering from a bad deploy" section.

## Files Changed

### Backend

- `backend/functions/package.json` — bump `firebase-functions` to `^7.2.5`; add
  devDeps `@firebase/rules-unit-testing@^5`, `@types/node@^22`, `firebase@^12`,
  `vitest@^2`; add `test` + `test:rules` scripts.
- `backend/functions/src/auth/verify.ts` — **new**. `requireAllowlistedUid`,
  `allowlistedCall<TIn,TOut>`, `ALLOWED_UID` (`defineString` default `"__BOOTSTRAP_UID__"`).
- `backend/functions/src/auth/index.ts` — re-export the three new symbols from
  the auth barrel.
- `backend/functions/src/index.ts` — flip `syncAllPlaylists`, `syncPlaylist`,
  `syncWatchLater` to `allowlistedCall`; remove `onRequest` import. Each
  callable carries explicit `TIn`/`TOut` types; `syncPlaylist` throws
  `HttpsError("invalid-argument")` on missing `playlistId`.
- `backend/firestore.rules` — replace deny-all stub with allowlisted-read
  matchers for `playlists/`, `videos/`, `sync_state/`; hard-deny on `tokens/`;
  catch-all `if false`. Uses sentinel `__BOOTSTRAP_UID__` for the
  comparison literal.
- `backend/firebase.json` — add `emulators` block (auth 9099, firestore 8080,
  functions 5001, UI 4000, `singleProjectMode: true`).
- `backend/functions/vitest.config.ts` — **new**. `globals: true`,
  `fileParallelism: false`, `testTimeout: 30_000`, `include: ["tests/**/*.test.ts"]`.
- `backend/functions/tsconfig.test.json` — **new**. Extends `tsconfig.json`,
  includes `tests/` with `noEmit`, `types: ["node", "vitest/globals"]`. Used
  for IDE + CI type-checking without polluting the deploy bundle.
- `backend/functions/tests/setup.ts` — **new**. Lazy `getTestEnv()` that reads
  `firestore.rules`, swaps the sentinel for `ALLOWLISTED_UID_FOR_TESTS`, and
  initializes a rules-unit-testing environment against the emulator at
  `127.0.0.1:8080`.
- `backend/functions/tests/rules.test.ts` — **new**. Six specs covering
  unauthenticated denial, stranger-uid denial, allowlisted-uid success on
  `playlists/`/`videos/`/`sync_state/`, hard-deny on `tokens/` for the
  allowlisted client, full client-write denial, and default-deny on the
  not-yet-defined `summaries/` collection.
- `backend/functions/tests/callable.test.ts` — **new**. Four specs covering
  `unauthenticated`, `permission-denied`, pass-through (with mocked
  `syncAll`), and `invalid-argument` on missing `playlistId`. Uses
  `firebase-functions-test` with `oauthSecrets`/`youtube/index` stubs and
  `process.env.ALLOWED_UID` seeding before module load.

### Android

- `android/gradle/libs.versions.toml` — drop the four YouTube Data API
  version refs; add `firebase-bom = "34.0.0"`, `google-services = "4.4.2"`,
  `kotlinx-coroutines-play-services = "1.7.3"`. Add `firebase-bom`,
  `firebase-auth`, `firebase-firestore`, `firebase-functions`,
  `kotlinx-coroutines-play-services` library aliases. Add
  `googleServices` plugin alias.
- `android/build.gradle.kts` — `alias(libs.plugins.googleServices) apply false`.
- `android/app/build.gradle.kts` — apply `googleServices` plugin; add
  `implementation(platform(libs.firebase.bom))` + auth/firestore/functions
  artifacts + `kotlinx-coroutines-play-services`. Remove the four YouTube
  Data API `implementation` blocks (the `exclude` clauses for
  `httpclient`/`commons-logging` stay in `configurations.all`).
- `android/app/src/main/java/com/github/jayteealao/playster/data/AppModule.kt` —
  **new** Hilt module providing `FirebaseAuth`, `FirebaseFirestore`,
  `FirebaseFunctions` singletons via `Firebase.auth` / `.firestore` / `.functions`.
- `data/auth/FirebaseAuthBridge.kt` — **new**. `signInWithGoogleIdToken(idToken)`
  uses `GoogleAuthProvider.getCredential` + `signInWithCredential().await()`.
  `currentUid: StateFlow<String?>` is wired to a long-lived
  `AuthStateListener`; `authStateFlow()` exposes a cold flow variant.
- `data/firestore/PlaylistDoc.kt`, `VideoDoc.kt` — **new** Kotlin mirrors of
  the backend `PlaylistDocument` / `VideoDocument` types. All fields default
  to empty so a partially-written document doesn't crash the POJO mapper.
- `data/firestore/FirestoreRepository.kt` — **new**. `playlistsFlow()` listens
  on `playlists/`; `videosFlow(playlistId)` listens on `videos/` filtered by
  `playlistId`. Both built on `callbackFlow + awaitClose { listener.remove() }`.
- `screens/auth/authViewModel.kt` — rewrite. `GoogleAccountCredential` /
  `YouTubeScopes` / `mutableStateOf<Pair<…,Exception?>>` gone. New shape:
  `loggedIn: StateFlow<Boolean>` from the bridge, `bridgeToFirebase(idToken,
  onResult)` for AuthScreen to call after Credential Manager returns,
  `saveLoginFailure(exception)` retained for error display.
- `screens/auth/AuthScreen.kt` — rewrite. Legacy `GoogleSignIn` + One-Tap +
  their launchers/clients deleted. Single `startCredentialSignIn()` path,
  the primary button wired to it, and a `processCredentialSignIn` that
  hands `googleIdTokenCredential.idToken` to
  `authViewModel.bridgeToFirebase(...)` which calls `onSignIn()` on success.
- `screens/auth/PlaysterNavHost.kt` — switch `loggedIn` reading from
  `MutableState<Boolean>` to `collectAsStateWithLifecycle()` on the new
  `StateFlow`. Add `composable("videos/{playlistId}")` route. `onboard →
  list` and `loader → list/onboard` navigation now uses `popUpTo(...)` so
  back-stack doesn't accumulate the loader/onboard frames.
- `screens/playlist/PlaylistScreen.kt` — rewrite. Drops the device-side
  `YouTube.Builder` block entirely. Uses `hiltViewModel<PlaylistViewModel>()`
  with `collectAsStateWithLifecycle()` against the Firestore flow. Wraps
  the content `Column` in `Box(modifier = ….pullRefresh(state))` with a
  `PullRefreshIndicator` overlay; on refresh, the ViewModel calls the
  `syncAllPlaylists` callable.
- `screens/playlist/PlaylistViewModel.kt` — **new**. Wraps
  `FirestoreRepository.playlistsFlow()` as `StateFlow<List<PlaylistDoc>>` and
  exposes `refresh()` that fires the callable, with
  `_isRefreshing: MutableStateFlow<Boolean>` for the spinner.
- `screens/playlist/PlayCard.kt` — accept `PlaylistDoc` instead of
  `com.google.api.services.youtube.model.Playlist`. Property accesses change
  from `playlist.snippet.title`/`playlist.snippet.channelTitle`/
  `playlist.contentDetails.itemCount` to `title`/`channelTitle`/`videoCount`.
  Adds `onClick` for tile navigation.
- `screens/playlist/VideoListScreen.kt` — **new**. Slice-1 stub with a
  `VideoListViewModel` (Hilt-injected, reads `playlistId` from
  `SavedStateHandle`) and a video-row Composable. Tap is currently a no-op —
  slice 4 wires VideoDetailScreen on top.
- `screens/common/QuotaBanner.kt` — **new**. No-op placeholder Composable
  reserving layout real estate; slice 4 swaps in the
  `quota/openrouter`-observing implementation.
- `.gitignore` — add `android/app/google-services.json`.

### Operations

- `docs/operations/bootstrap-allowlisted-uid.md` — **new** runbook covering
  the two-pass deploy procedure (placeholder → first sign-in → capture uid →
  real allowlist redeploy), env-file param mechanism, secret-param alternative,
  verification steps, recovery procedure, and the future-scaling note for
  moving beyond single-tenant.

## Shared Files (also touched by sibling slices)

None — this is the upstream root slice. The following are seeded here for
downstream slices to extend:

- `backend/firestore.rules` — slice 3 will append `summaries/{document=**}` and
  `quota/{document=**}` blocks *before* the catch-all (the catch-all currently
  shadows them, so the inserts must precede it).
- `backend/firebase.json` emulator block — slice 3 reuses the ports.
- `backend/functions/vitest.config.ts` + `tests/setup.ts` — slice 3 adds
  fixture helpers (HMAC vectors) alongside.
- `backend/functions/src/auth/verify.ts` (`allowlistedCall`) — slice 3's
  `summarizer/dispatch.ts` imports it.
- `data/firestore/FirestoreRepository.kt` listener pattern — slice 4's
  `SummaryRepository`/`QuotaRepository` follow the same `callbackFlow +
  awaitClose` shape.
- `screens/common/QuotaBanner.kt` — slice 4 replaces the body; Composable
  signature stays the same so PlaylistScreen needs no edit.

## Notes on Design Choices

- **`defineString` over `defineSecret` for `ALLOWED_UID`.** The uid is operator
  config, not a credential. `defineString` reads from `.env.<projectId>` at
  deploy time which keeps the value out of Secret Manager and out of the
  git-checked source. The runbook documents how to flip to `defineSecret` if
  the operator wants stronger isolation.
- **Sentinel literal in the rules file.** `firestore.rules` is the source of
  truth at deploy time — substituting `__BOOTSTRAP_UID__` programmatically at
  test time (in `tests/setup.ts`) avoids the temptation to commit the real
  uid. The rules-unit-testing harness reads the source, swaps the sentinel
  with `ALLOWLISTED_UID_FOR_TESTS`, then evaluates.
- **Material 2 `pullRefresh` instead of Material 3 `PullToRefreshBox`.**
  Compose BOM `2024.01.00` ships material3 `1.1.2`, which predates
  `PullToRefreshBox` (added in `1.3.0`). `androidx.compose.material.pullrefresh`
  is the stable Material 2 API and coexists cleanly inside a Material 3 app.
  Bumping the Compose BOM would have rippled into the Compose-tooling
  versions; deferring that to a separate, focused change.
- **`@firebase/rules-unit-testing` + `firebase` modular SDK.** The v5 API
  returns a modular `Firestore` instance, so the tests use `getDoc`/`setDoc`
  from `firebase/firestore` (added as devDep). Cleaner than the legacy
  compat API and matches what new Firebase docs show.
- **Listener teardown on every Flow.** Every snapshot-listener flow is built
  on `callbackFlow { … awaitClose { listener.remove() } }` so cancelled
  collectors don't leave dangling Firestore subscriptions. Slice 4's repo
  layer follows the same convention.
- **`scheduledSync` left alone.** It runs on `onSchedule`, has no auth
  context, and is the way Firestore data gets populated in the first place.
  Lockdown-by-allowlist would have broken the seed path.

## Deviations from Plan

1. **Added `firebase` (modular SDK) + `@types/node` as devDeps.** Plan listed
   `@types/node` but missed `firebase`. The `@firebase/rules-unit-testing` v5
   API returns a modular `Firestore`, so `firebase/firestore` is required to
   call `getDoc` / `setDoc` / `doc`. Added at `^12.0.0` to satisfy
   `@firebase/rules-unit-testing`'s peer constraint.
2. **Added `kotlinx-coroutines-play-services` to Android.** Required for the
   `.await()` extension on `Task` returns from `signInWithCredential` and
   `getHttpsCallable().call()`. Plan didn't enumerate this dep explicitly.
3. **Added `tsconfig.test.json`.** Plan didn't mention how to type-check the
   tests without emitting them into `lib/`. The extras-tsconfig approach
   keeps `pnpm run build` deploy-clean while letting IDE + CI run a separate
   type-check pass over `tests/`.

The plan's two-pass deploy procedure (placeholder sentinel → operator captures
uid → real redeploy) is implemented exactly as specified; no further
deviation there.

## Anything Deferred

- **Running the rules + callable tests against a live emulator.** The
  scaffolding is in place; running them is the `wf verify` stage's gate.
- **Removing the `One-Tap` dead code in `AuthScreen.kt`.** Discovery phase
  scoped the Credential Manager change to "replace legacy GoogleSignIn only";
  One-Tap was already dead and unreferenced after the rewrite. The remaining
  `BeginSignInRequest`/`Identity.getSignInClient` constants are gone now
  (they were deleted as part of the AuthScreen rewrite — the dead-code
  cleanup actually went one step further than the plan's "leave it" stance
  because the smaller diff was the natural shape of the rewrite).
- **Removing the `setCookies` legacy `onRequest` endpoint.** PO deferred. Still
  present in `auth/handlers.ts`. Not touched.
- **Bumping Compose BOM to enable Material 3 `PullToRefreshBox`.** Deferred —
  Material 2 pullRefresh covers the same UX without dependency churn.
- **Maestro flows + lazylogcat capture for AC-3 / AC-4.** Plan put these in
  Phase E ("interactive verification"); they're the verify stage's gate.
- **An APK size measurement.** Plan listed as nice-to-have; left for slice
  retro.

## Known Risks / Caveats

- **`google-services.json` is gitignored.** Operator must place it at
  `android/app/google-services.json` before `./gradlew :app:assembleDebug`
  will succeed. The bootstrap runbook documents this.
- **`ALLOWED_UID` is `__BOOTSTRAP_UID__` until the operator runs the second
  deploy pass.** Every callable will fail with `permission-denied` and every
  Firestore client read will fail until the real uid lands in both
  `firestore.rules` and the `.env.playster-406121` file. This is the
  intended bootstrap state.
- **`firestore.rules` summaries/quota inserts must precede the catch-all.**
  Slice 3 must `INSERT BEFORE` the `match /{document=**} { allow read, write: if false; }`
  block — otherwise the catch-all shadows the new rules. Documented in the
  cross-slice integration notes above.
- **`firebase-functions ^7` install is clean today.** Lockfile resolves
  cleanly to `firebase-functions@7.2.5` + `firebase-admin@13.10.0` with no
  peer warnings. No `SecretParam`-style import bugs surfaced. If a future
  pnpm install pulls an older 7.0.0 by mistake, the build will fail-fast
  with an import error.
- **Compose `PullToRefreshBox` (Material 3 ≥ 1.3) is the modern API.**
  Material 2 `pullRefresh` works today; future Compose-BOM bump should
  migrate to the Material 3 equivalent. Not urgent.

## Freshness Research

No new external lookups needed beyond what the plan's `## Freshness Research`
already covered. The plan was written today (2026-05-18); nothing in the
ecosystem moved between then and implementation. Verified at implementation
time:

- `firebase-functions@7.2.5` + `firebase-admin@13.10.0` co-install cleanly.
  `pnpm install` resolved without peer warnings.
- `@firebase/rules-unit-testing@5.0.1` requires `firebase@^12`; installed
  `firebase@12.13.0`.
- Material 2 `pullRefresh` API is stable and present in Compose BOM
  2024.01.00 (`androidx.compose.material:material:1.6.x`).
- Firebase Android BOM 34.0.0 ships `firebase-auth`, `firebase-firestore`,
  `firebase-functions` artifacts with the KTX-in-main shape — no `-ktx`
  suffix needed.

## Recommended Next Stage

- **Option A (default):** `/wf verify wire-android-backend-summarizer auth-and-android-firebase` — run the rules + callable tests against the Firebase emulator, build the Android APK against an operator-supplied `google-services.json`, and execute the AC-3 / AC-4 Maestro flows with lazylogcat capture. **Consider running `/compact` before `/wf verify`** — implementation details (debugging, file reads) are noise for verification; the PreCompact hook preserves workflow state.
- **Option B:** `/wf review wire-android-backend-summarizer auth-and-android-firebase` — skip verify and go straight to review if verification is judged trivial (it isn't, for this slice — emulator tests are the gate for AC-1/AC-2/AC-15).
- **Option C:** `/wf plan wire-android-backend-summarizer auth-and-android-firebase` — revisit the plan if review finds a structural issue (none currently expected).

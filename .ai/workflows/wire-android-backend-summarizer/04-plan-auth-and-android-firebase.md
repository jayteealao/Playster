---
schema: sdlc/v1
type: plan
slug: wire-android-backend-summarizer
slice-slug: auth-and-android-firebase
status: complete
stage-number: 4
created-at: "2026-05-18T07:49:39Z"
updated-at: "2026-05-18T07:49:39Z"
metric-files-to-touch: 18
metric-step-count: 24
has-blockers: false
revision-count: 0
tags: [android, firebase-auth, firestore, backend, onCall, cleanup]
stack-source: confirmed
refs:
  index: 00-index.md
  plan-index: 04-plan.md
  slice-def: 03-slice-auth-and-android-firebase.md
  siblings:
    - 04-plan-summarizer-container.md
    - 04-plan-summary-orchestration.md
    - 04-plan-summary-ui.md
  implement: 05-implement-auth-and-android-firebase.md
next-command: wf-implement
next-invocation: "/wf implement wire-android-backend-summarizer auth-and-android-firebase"
---

# Plan: Auth + Android Firebase view (atomic flip)

## Current State

**Backend (`backend/functions/src/`)** — Three HTTP triggers in [`index.ts`](../../backend/functions/src/index.ts) are wide open: `syncAllPlaylists` (line 19), `syncPlaylist` (line 37), `syncWatchLater` (line 61). All are `onRequest`, no auth gate. `scheduledSync` (line 80) runs every 6h via Cloud Scheduler and has no auth context. The four auth handlers in [`auth/handlers.ts`](../../backend/functions/src/auth/handlers.ts) — `authRedirect`, `authCallback`, `setCookies`, `setTvOauthCredentials` — are also `onRequest` and out of scope for this slice (PO deferred). [`auth/index.ts`](../../backend/functions/src/auth/index.ts) re-exports both OAuth utilities and HTTP handlers from the same barrel; `oauth.ts` already uses `defineSecret` (`OAUTH_CLIENT_ID`, `OAUTH_CLIENT_SECRET`), so the `params` pattern is in place. [`models/index.ts`](../../backend/functions/src/models/index.ts) defines `PlaylistDocument`, `VideoDocument`, `WatchLaterSyncState` — no `SummaryDocument`/`QuotaDocument` yet (slice 3). [`package.json`](../../backend/functions/package.json) pins `firebase-functions: ^6.3.0` and `firebase-admin: ^13.0.0`; the lockfile shows these resolve cleanly to `firebase-functions@6.6.0` + `firebase-admin@13.10.0` (peer accepts `^11.10.0 || ^12.0.0 || ^13.0.0`), so the "peer conflict" framing in the shape doc is somewhat overstated — the install works today. The drive to v7 is forward-looking (TypeScript v5, ES2022 target, `functions.config()` removal which we don't use, ESM support) rather than a blocker.

**Backend rules / emulator config** — [`backend/firestore.rules`](../../backend/firestore.rules) exists but is deny-all (`allow read, write: if false`). [`backend/firebase.json`](../../backend/firebase.json) defines the `functions` codebase + `firestore.rules`/`firestore.indexes.json` paths but **has no `emulators` block**. [`backend/.firebaserc`](../../backend/.firebaserc) → project `playster-406121`. No `@firebase/rules-unit-testing` in dependencies (grep confirms only docs references). No vitest config in `backend/functions/`. Tests today: zero — backend has no test runner installed beyond `firebase-functions-test` (devDep, unused).

**Android (`android/app/src/main/`)** — [`MainActivity.kt`](../../android/app/src/main/java/com/github/jayteealao/playster/MainActivity.kt) → `PlaysterNavHost`. [`screens/auth/PlaysterNavHost.kt`](../../android/app/src/main/java/com/github/jayteealao/playster/screens/auth/PlaysterNavHost.kt) defines three routes: `onboard` → `AuthScreen`, `list` → `PlaylistScreen`, `loader` → `LoadingScreen`. [`screens/auth/authViewModel.kt`](../../android/app/src/main/java/com/github/jayteealao/playster/screens/auth/authViewModel.kt) holds `GoogleAccountCredential` in `userLogin` (lines 26-37), bound to `YouTubeScopes.YOUTUBE_READONLY`. [`screens/auth/AuthScreen.kt`](../../android/app/src/main/java/com/github/jayteealao/playster/screens/auth/AuthScreen.kt) — surprise finding: **Credential Manager scaffolding already exists** (`startCredentialSignIn`, `processCredentialSignIn` with `GoogleIdTokenCredential.createFrom`, lines 134-217), alongside legacy `GoogleSignIn` (lines 86-92) and One-Tap (`Identity.getSignInClient`, lines 73-84). The visible button (line 273) currently invokes `startLegacySignIn()`; `startCredentialSignIn` is dead code waiting to be wired. Server client ID hardcoded `510333739373-ust5kheckkg2oiuoghp08l5ghm1fsmat.apps.googleusercontent.com`. [`screens/playlist/PlaylistScreen.kt`](../../android/app/src/main/java/com/github/jayteealao/playster/screens/playlist/PlaylistScreen.kt) lines 41-61 invoke `YouTube.Builder(...).playlists().list(...).execute()` directly from the device — this is the device→YouTube Data API path that AC-3 wants to eliminate. [`SettingsManager.kt`](../../android/app/src/main/java/com/github/jayteealao/playster/SettingsManager.kt) persists `Account(name, type)` via DataStore.

**Android deps** ([`android/app/build.gradle.kts`](../../android/app/build.gradle.kts) + [`android/gradle/libs.versions.toml`](../../android/gradle/libs.versions.toml)) — AGP 8.2.2, Kotlin 1.9.22, compileSdk 34, minSdk 29, compose-bom 2024.01.00. Direct YouTube deps to remove: `google-api-services-youtube` (v3-rev20231011-2.0.0), `google-api-client-android` (2.2.0), `google-http-client-android` (1.43.3), `google-api-client-gson` (2.2.0). Keep: `credentials` (1.2.0), `credentials-play-services-auth`, `identity` (googleid 1.1.0), `one-tap-sign-in` (play-services-auth 20.7.0). No Firebase plugin / dependencies / `google-services.json` present anywhere. No `firebase-bom` reference. No `com.google.gms.google-services` plugin in [`android/build.gradle.kts`](../../android/build.gradle.kts) or [`android/settings.gradle.kts`](../../android/settings.gradle.kts). No Maestro directory exists.

**Inbound callers of the three `onRequest` sync functions** — Grep across the repo finds them referenced only in `backend/functions/src/{index,youtube/index,youtube/innertube-sync}.ts` (own re-exports) plus this workflow's own slice docs. No CI runner, no script in `backend/functions/scripts/`, no Android code, no documentation. Safe to swap to `onCall` without breaking external callers.

**Workspace** — [`pnpm-workspace.yaml`](../../pnpm-workspace.yaml) currently lists `backend/functions` + `summarizer/summarize-api`. Root has a unified `pnpm-lock.yaml`. Node 22 on backend, Node 24 on summarizer.

## Reuse Opportunities

- **`defineSecret`/`defineString` from `firebase-functions/params`** — Already used in [`auth/oauth.ts`](../../backend/functions/src/auth/oauth.ts) for OAuth client id/secret. Use the same pattern for `ALLOWED_UID` via `defineString` (non-secret config).
- **`onCall` with `secrets:` option** — Existing `onRequest` calls already pass `{ secrets: oauthSecrets }`. Direct port: `onCall<TIn, TOut>({ secrets: oauthSecrets }, async (req) => {...})`. Confirmed available on `firebase-functions/v2/https` in both ^6.6 and ^7.x.
- **Credential Manager scaffolding in `AuthScreen.kt`** — `startCredentialSignIn()` already extracts `GoogleIdTokenCredential.idToken` (lines 144-148). Wiring `GoogleAuthProvider.getCredential(idToken, null)` + `FirebaseAuth.getInstance().signInWithCredential(...)` on top of this is a 3-line addition. **Caveat:** the current dead-code path passes `idToken.id` (the email) to `saveLoginSuccess` rather than `idToken` (the JWT); needs a small adjustment.
- **`SettingsManager` DataStore pattern** — Will be reused to persist the Firebase uid after first sign-in, alongside the existing `Account` storage.
- **`PlaysterNavHost` "list" route** — Already exists; we just swap the Composable's data source. No nav graph change needed for this slice (slice 4 adds VideoDetailScreen route).
- **No reusable Firestore-listener pattern exists yet** — This slice establishes it. Slice 4 will follow the pattern.

## Likely Files / Areas to Touch

### Backend (TypeScript)

- `backend/functions/package.json` — bump `firebase-functions: ^6.3.0` → `^7.2.5` (latest at write time). Add devDeps for emulator-based tests: `@firebase/rules-unit-testing@^5`, `vitest@^2`, `@types/node@^22`. Add scripts: `test`, `test:rules`.
- `backend/functions/src/auth/verify.ts` — **new**. `requireAllowlistedUid(authToken)`, `allowlistedCall<TIn, TOut>(opts, handler)` HOF. Exports `ALLOWED_UID` (defineString).
- `backend/functions/src/auth/index.ts` — extend barrel to re-export `requireAllowlistedUid`, `allowlistedCall`, `ALLOWED_UID`.
- `backend/functions/src/index.ts` — convert `syncAllPlaylists`, `syncPlaylist`, `syncWatchLater` to `allowlistedCall<TIn, TOut>(...)`. Drop `onRequest` imports if no longer referenced.
- `backend/firestore.rules` — replace deny-all with allowlisted-uid read, deny write. Cover `playlists/{document=**}`, `videos/{document=**}`, `tokens/{document=**}`, `sync_state/{document=**}` plus a future-proof catch-all. Use a placeholder uid string (`__BOOTSTRAP_UID__`) that the bootstrap procedure replaces.
- `backend/firebase.json` — add `emulators` block (`auth`, `firestore`, `functions`) on standard ports (9099, 8080, 5001, 4000 UI). Add `firestore.indexes.json` reference (already present, no-op).
- `backend/firestore.indexes.json` — unchanged.
- `backend/functions/vitest.config.ts` — **new**. Standard vitest config with `globals: true`, `testTimeout: 30_000`, `fileParallelism: false` (rules tests share emulator state).
- `backend/functions/tests/rules.test.ts` — **new**. Cover AC-1, AC-2, AC-15 (partial). Uses `@firebase/rules-unit-testing` `initializeTestEnvironment({ projectId: 'playster-rules-test', firestore: { rules: fs.readFileSync('firestore.rules', 'utf8') } })`. Tests: stranger uid denied on each collection; allowlisted uid allowed on each collection; unauthenticated context denied; writes denied for everyone (Admin SDK only).
- `backend/functions/tests/callable.test.ts` — **new**. Uses `firebase-functions-test` to invoke `syncAllPlaylists` with no token / stranger token / allowlisted token; asserts `unauthenticated` / `permission-denied` / pass-through.
- `backend/functions/tests/setup.ts` — **new**. Test setup that seeds `ALLOWED_UID` env and starts/stops the emulator handle.
- `backend/functions/eslint.config.js` (or existing) — no change expected; verify v7 typings don't trip the lint config.

### Android (Kotlin)

- `android/build.gradle.kts` — add `alias(libs.plugins.googleServices) apply false`.
- `android/app/build.gradle.kts` — apply `alias(libs.plugins.googleServices)`. Add Firebase BOM + dependencies. Remove `google-api-services-youtube`, `google-api-client-android`, `google-api-client-gson`, `google-http-client-android` (with their `configurations.all { exclude ... }` lines if those become orphaned).
- `android/gradle/libs.versions.toml` — add `firebase-bom = "34.0.0"` (no-suffix KTX baseline — see Freshness Research) and library aliases `firebase-bom`, `firebase-auth`, `firebase-firestore`, `firebase-functions`. Add `google-services` plugin alias (version `4.4.2`).
- `android/app/google-services.json` — **new**, operator-supplied. Add path to `.gitignore` (single-tenant means this file holds project config keys; not actually secret but cleaner to gitignore and document the bootstrap step).
- `android/app/src/main/java/com/github/jayteealao/playster/data/firestore/PlaylistDoc.kt` — **new**. Kotlin data class mirroring `PlaylistDocument` (nullable fields with `@PropertyName` if needed, plus `@DocumentId` on the id).
- `android/app/src/main/java/com/github/jayteealao/playster/data/firestore/VideoDoc.kt` — **new**. Kotlin data class mirroring `VideoDocument`.
- `android/app/src/main/java/com/github/jayteealao/playster/data/firestore/FirestoreRepository.kt` — **new**. Wraps `Firebase.firestore.collection("playlists").snapshots()` as `Flow<List<PlaylistDoc>>` and `playlists/{id}/videos` likewise. Single source of truth for collection paths.
- `android/app/src/main/java/com/github/jayteealao/playster/data/auth/FirebaseAuthBridge.kt` — **new**. `signInToFirebase(idToken: String): String` returns Firebase uid; uses `FirebaseAuth.getInstance().signInWithCredential(GoogleAuthProvider.getCredential(idToken, null)).await()`. Provides `currentUid: Flow<String?>` from `FirebaseAuth.AuthStateListener` adapter.
- `android/app/src/main/java/com/github/jayteealao/playster/screens/auth/authViewModel.kt` — drop `GoogleAccountCredential`. Add `firebaseUid: StateFlow<String?>`. Add `bridgeToFirebase(idToken)` that calls `FirebaseAuthBridge.signInToFirebase` and persists uid via `SettingsManager`. Keep `loggedIn` semantics tied to Firebase Auth state.
- `android/app/src/main/java/com/github/jayteealao/playster/screens/auth/AuthScreen.kt` — switch the primary button from `startLegacySignIn()` to `startCredentialSignIn()`. Fix the `processCredentialSignIn` path to call `authViewModel.bridgeToFirebase(googleIdTokenCredential.idToken)` instead of the current `saveLoginSuccess(context, Account(id, packageName))` shim. Remove legacy `GoogleSignIn` branch + its launcher + `legacySignIn` client construction. (Leave One-Tap dead path for now — out of scope to delete; or remove if cheap. Plan stage flags as optional cleanup.)
- `android/app/src/main/java/com/github/jayteealao/playster/screens/playlist/PlaylistScreen.kt` — remove `YouTube.Builder`/`NetHttpTransport`/`GsonFactory` block (lines 41-61). Replace `displayItems: List<Playlist>` with `displayItems: List<PlaylistDoc>` from `FirestoreRepository`. Add `SwipeRefresh` (Material 3 `pullRefresh`) wrapper that on refresh invokes `Firebase.functions.getHttpsCallable("syncAllPlaylists").call(emptyMap<String, Any>())`.
- `android/app/src/main/java/com/github/jayteealao/playster/screens/playlist/PlayCard.kt` — change parameter type from `com.google.api.services.youtube.model.Playlist` to local `PlaylistDoc`. Adjust property accesses (`snippet.title` → `title`, `snippet.thumbnails.high.url` → `thumbnailUrl`, etc.).
- `android/app/src/main/java/com/github/jayteealao/playster/screens/playlist/VideoListScreen.kt` — **new**. Stub screen: Firestore listener on `playlists/{id}/videos`. Tapping a tile is a no-op (slice 4 wires VideoDetailScreen).
- `android/app/src/main/java/com/github/jayteealao/playster/screens/auth/PlaysterNavHost.kt` — add `composable("videos/{playlistId}") { VideoListScreen(...) }`. Hook tile tap in `PlaylistScreen` to navigate there.
- `android/app/src/main/java/com/github/jayteealao/playster/screens/common/QuotaBanner.kt` — **new**. No-op placeholder Composable (slice 4 wires the listener).
- `android/app/src/main/java/com/github/jayteealao/playster/screens/LoadingScreen.kt` — switch its `loggedIn` source from `AuthViewModel.loggedIn` to derived-from-Firebase-Auth state (so the loader correctly waits for the bridge to complete).
- `.gitignore` — add `android/app/google-services.json` (consistent with `local.properties` already there).

### Cross-cutting

- `pnpm-workspace.yaml` — unchanged (already lists `backend/functions`).
- `docs/operations/bootstrap-allowlisted-uid.md` — **new** (small how-to: deploy → first sign-in → capture uid → `firebase functions:secrets:set ALLOWED_UID --data-file <(echo -n "<uid>")` and rules placeholder replacement → redeploy).

## Proposed Change Strategy

**Atomic flip approach.** PO has explicitly chosen to ship Phase 1 (backend lockdown) and Phase 2 (Android Firestore view) as a single slice to avoid a transitional window where the Android app calls `onRequest` endpoints that have been replaced with `onCall`. The PR will be larger than a single subproject change but smaller than two separate transitional PRs would be cumulatively. Commits within the PR are organized by subproject to ease review.

**Bootstrap chicken-and-egg.** The operator's Firebase Auth uid does not exist until first successful sign-in, but `ALLOWED_UID` and the uid in `firestore.rules` must exist before the slice is "live". The plan resolves this with a **two-pass deploy**:

1. *First pass (placeholder):* deploy `firestore.rules` with `__BOOTSTRAP_UID__` (a sentinel string that won't match any real uid) and `ALLOWED_UID=__BOOTSTRAP_UID__`. All callables and Firestore reads will deny — that's intentional. Android side: install the new APK with Firebase Auth wired. Operator opens the app and taps Sign in with Google via Credential Manager. The Firebase Auth uid is created on first `signInWithCredential` call and is visible in the Firebase console under Authentication → Users.
2. *Second pass (real uid):* operator copies the uid from the console. Runs `firebase functions:secrets:set ALLOWED_UID` (or sets via `defineString` param config — `defineString` in v7 reads from `.env.<projectId>` / param-config-only-mode). Edits `firestore.rules` to replace the placeholder. Runs `firebase deploy --only firestore:rules,functions`. Operator pulls-to-refresh in the app — AC-4 verifies.

Documented in `docs/operations/bootstrap-allowlisted-uid.md`. The placeholder approach is preferred over a "setAllowlistedUid bootstrap function with a one-shot token" (planning doc §1.4) because it requires fewer moving parts and one fewer secret.

**`firebase-functions ^7` bump strategy.** The lockfile shows ^6.6.0 + admin ^13.10.0 actually co-install today (peer accepts ^13). The real motivation for ^7 is: (a) shape doc commits to it as the path forward; (b) v7 drops `functions.config()` which Firebase deprecated end-of-2025 — even though we don't use it, future deploys will warn; (c) TypeScript v5 / ES2022 alignment with the summarizer subproject. **API delta** (from v7.0 release notes, Apr 2026): `onCall`, `onSchedule`, `onRequest`, `defineSecret`, `defineString`, `params` all unchanged at the call-site level. ESM support added (we stay on CJS for now). `LegacyEvent` rename only affects v1 triggers (we use v2 exclusively). One reported v7.0 issue: `SecretParam` and certain type exports were missing from `firebase-functions/params` — fixed in v7.0.1+; pin to ^7.2.5 or later. Risk of unforeseen breakage: low — confined to the one-line version bump and `pnpm install`; any TypeScript errors surface immediately via `pnpm --filter functions run build`.

**Why cleanup is non-transitional.** Dropping `google-api-services-youtube` etc. on Android and removing `onRequest` versions of the three sync functions are non-reversible by design. The atomic slice means the Android app cannot regress to calling YouTube directly because the YouTube data API client classes will no longer compile. PR review enforces both: backend changes remove the old endpoints in the same commit as the new callables; Android changes remove the YouTube types in the same commit as the Firestore listener. **The deferred `setCookies` endpoint stays untouched** (per PO) — verify with grep that it's still wired through `auth/handlers.ts` after this slice.

## Step-by-Step Plan

### Phase A — Backend prep (commits: `backend: add allowlistedCall + ALLOWED_UID param`)

1. Bump `backend/functions/package.json`: `"firebase-functions": "^7.2.5"`, leave `firebase-admin: ^13.0.0`. Add `devDependencies`: `"@firebase/rules-unit-testing": "^5.0.0"`, `"vitest": "^2.1.0"`. Add `"scripts"` entries: `"test": "vitest run"`, `"test:rules": "vitest run tests/rules.test.ts"`. Run `pnpm install` at repo root; verify lockfile updates only `firebase-functions` + added devDeps.
2. Run `pnpm --filter functions run build`. Fix any TS errors that surface from the v7 bump (expected: none, based on freshness research; budget 30 min).
3. Create `backend/functions/src/auth/verify.ts`. Exports:
   - `export const ALLOWED_UID = defineString("ALLOWED_UID", { default: "__BOOTSTRAP_UID__" });`
   - `export async function requireAllowlistedUid(auth?: { uid?: string }): Promise<string>` — throws `HttpsError("unauthenticated", ...)` if no auth; throws `HttpsError("permission-denied", ...)` if `auth.uid !== ALLOWED_UID.value()`.
   - `export function allowlistedCall<TIn, TOut>(opts: CallableOptions, handler: (data: TIn) => Promise<TOut>): CallableFunction<TIn, TOut>` — wraps `onCall<TIn, TOut>(opts, async (req) => { await requireAllowlistedUid(req.auth); return handler(req.data); })`.
4. Extend `backend/functions/src/auth/index.ts` to re-export `requireAllowlistedUid`, `allowlistedCall`, `ALLOWED_UID`.

### Phase B — Backend lockdown (commits: `backend: flip sync HTTP endpoints to callable`)

5. Edit `backend/functions/src/index.ts`. For each of `syncAllPlaylists`, `syncPlaylist`, `syncWatchLater`:
   - Remove `onRequest` import path; replace with `allowlistedCall`.
   - Define `TIn` shape: `syncAllPlaylists` → `Record<string, never>` (no input); `syncPlaylist` → `{ playlistId: string }`; `syncWatchLater` → `{ reset?: boolean }`.
   - Define `TOut` shape: mirror existing JSON return shapes.
   - Validate `data.playlistId` in `syncPlaylist` handler; throw `HttpsError("invalid-argument", "Missing playlistId")` if absent.
   - Keep `secrets: oauthSecrets`, `memory`, `timeoutSeconds` options.
   - `scheduledSync` unchanged (no auth context — it's a cron).
6. Run `pnpm --filter functions run lint && pnpm --filter functions run build`. Confirm clean.
7. Write `backend/firestore.rules`:
   ```
   rules_version = '2';
   service cloud.firestore {
     match /databases/{database}/documents {
       function isAllowlisted() {
         return request.auth != null && request.auth.uid == "__BOOTSTRAP_UID__";
       }
       match /playlists/{document=**}    { allow read: if isAllowlisted(); allow write: if false; }
       match /videos/{document=**}       { allow read: if isAllowlisted(); allow write: if false; }
       match /tokens/{document=**}       { allow read: if false;            allow write: if false; }
       match /sync_state/{document=**}   { allow read: if isAllowlisted(); allow write: if false; }
       match /{document=**}              { allow read, write: if false; }
     }
   }
   ```
   Note: `tokens/` denies read for the allowlisted client too — only the Admin SDK reads OAuth refresh tokens. Forward-compat: slice 3 adds `summaries/` and `quota/` blocks.
8. Add `emulators` block to `backend/firebase.json`:
   ```json
   "emulators": {
     "auth": { "port": 9099 },
     "firestore": { "port": 8080 },
     "functions": { "port": 5001 },
     "ui": { "enabled": true, "port": 4000 },
     "singleProjectMode": true
   }
   ```
9. Create `backend/functions/vitest.config.ts` (mirror `summarizer/summarize-api/vitest.config.ts` shape; `fileParallelism: false`).
10. Create `backend/functions/tests/setup.ts` — exports `getTestEnv()` that lazily calls `initializeTestEnvironment({ projectId: "playster-rules-test", firestore: { rules: fs.readFileSync("../firestore.rules", "utf8").replace("__BOOTSTRAP_UID__", "ALLOWLISTED_UID") } })`. Also exports `teardownTestEnv()`.
11. Create `backend/functions/tests/rules.test.ts` (covers AC-1, AC-15 partial):
    - `it('denies unauthenticated reads on playlists')` → `assertFails(getDoc(unauth.firestore().doc('playlists/x')))`.
    - `it('denies stranger uid reads on playlists/videos/sync_state')` for each collection.
    - `it('allows allowlisted uid reads on playlists/videos/sync_state')`.
    - `it('denies allowlisted uid writes on every collection')` — Admin SDK only.
    - `it('denies allowlisted uid reads on tokens/')` — strict.
12. Create `backend/functions/tests/callable.test.ts` (covers AC-1, AC-2):
    - Use `firebase-functions-test` in offline mode with `defineParameters`.
    - Wrap `syncAllPlaylists`; call with `{ auth: undefined }` → expect `unauthenticated`.
    - Wrap; call with `{ auth: { uid: "stranger" } }` → expect `permission-denied`.
    - Wrap; call with `{ auth: { uid: "ALLOWLISTED_UID" } }` → mock `syncAll` to return `{ regular: { playlistCount: 0, videoCount: 0 }, watchLater: { videoCount: 0, ... } }`; expect pass-through.
13. Run `pnpm --filter functions run test` against the Firebase emulator (`firebase emulators:exec --only auth,firestore --project playster-rules-test "pnpm --filter functions run test"`). All green is the gate for the backend half of the slice.

### Phase C — Android Firebase wiring (commits: `android: add Firebase BOM + Auth bridging`)

14. Edit `android/gradle/libs.versions.toml`:
    - `[versions]` add: `google-services = "4.4.2"`, `firebase-bom = "34.0.0"`.
    - `[libraries]` add: `firebase-bom = { module = "com.google.firebase:firebase-bom", version.ref = "firebase-bom" }`, `firebase-auth = { module = "com.google.firebase:firebase-auth" }`, `firebase-firestore = { module = "com.google.firebase:firebase-firestore" }`, `firebase-functions = { module = "com.google.firebase:firebase-functions" }`. **No `-ktx` suffix** — Firebase Android BoM 34+ removed them (KTX rolled into main modules).
    - `[plugins]` add: `googleServices = { id = "com.google.gms.google-services", version.ref = "google-services" }`.
15. Edit `android/build.gradle.kts` — add `alias(libs.plugins.googleServices) apply false`.
16. Edit `android/app/build.gradle.kts`:
    - At top of `plugins { ... }`, append `alias(libs.plugins.googleServices)`.
    - In `dependencies { ... }`, add `implementation(platform(libs.firebase.bom))`, `implementation(libs.firebase.auth)`, `implementation(libs.firebase.firestore)`, `implementation(libs.firebase.functions)`.
    - In `dependencies { ... }`, remove `libs.google.api.client.android`, `libs.google.api.services.youtube`, `libs.google.api.client.gson`, `libs.google.http.client.android` (with the `exclude(...)` clauses inside their blocks). Verify `configurations.all { exclude(module = "httpclient"); exclude(module = "commons-logging") }` can stay (still harmless).
    - Operator places `google-services.json` at `android/app/google-services.json`.
17. Add `android/app/google-services.json` to `.gitignore`.
18. Create `android/app/src/main/.../data/firestore/PlaylistDoc.kt` and `VideoDoc.kt` (data classes with `@DocumentId`, defaulted nullable fields matching `backend/functions/src/models/index.ts`). Create `FirestoreRepository.kt` with `playlistsFlow(): Flow<List<PlaylistDoc>>` using `callbackFlow` over `addSnapshotListener`, and `videosFlow(playlistId): Flow<List<VideoDoc>>`. Provide via Hilt (mirroring `SettingsManager` pattern).
19. Create `android/app/src/main/.../data/auth/FirebaseAuthBridge.kt` with `suspend fun signInToFirebase(idToken: String): String` and `val currentUid: StateFlow<String?>`. Inject `FirebaseAuth.getInstance()` via Hilt module (new `AppModule.kt` if absent, or extend existing).
20. Rewrite `screens/auth/authViewModel.kt`:
    - Drop `GoogleAccountCredential`, `YouTubeScopes` imports + `userLogin` field.
    - Inject `FirebaseAuthBridge` + keep `SettingsManager`.
    - `loggedIn` becomes a `StateFlow<Boolean>` driven by `firebaseAuthBridge.currentUid != null`.
    - Add `suspend fun bridgeToFirebase(idToken: String)` → calls `firebaseAuthBridge.signInToFirebase(idToken)`; on success, persists uid via `settingsManager`.
    - Add `saveLoginFailure(exception)` retained (call sites in `AuthScreen` unchanged).

### Phase D — Android YouTube-API cleanup (commits: `android: replace YouTube SDK with Firestore listener`)

21. Edit `screens/auth/AuthScreen.kt`:
    - Primary button `onClick = { startLegacySignIn() }` → `onClick = { startCredentialSignIn() }`.
    - In `processCredentialSignIn` → `is CustomCredential` → `GoogleIdTokenCredential` branch: replace `authViewModel.saveLoginSuccess(context, Account(id, packageName))` with `coroutineScope.launch { authViewModel.bridgeToFirebase(googleIdTokenCredential.idToken) }`.
    - Remove `processLegacySignIn`, `legacySignIn` client, `legacyLauncher`, `startLegacySignIn` (and unused imports: `GoogleSignIn`, `GoogleSignInAccount`, `GoogleSignInOptions`, `Scope`, `YouTubeScopes`).
    - Leave the One-Tap path alone for this slice (PO has not asked to clean it up; out of scope risk).
22. Rewrite `screens/playlist/PlaylistScreen.kt`:
    - Delete `YouTube.Builder` block (lines 41-61) and unused imports (`NetHttpTransport`, `GsonFactory`, `YouTube`, `Playlist`, `Dispatchers`, `withContext`).
    - Inject `FirestoreRepository` via `hiltViewModel<PlaylistViewModel>()` (new ViewModel — small wrapper around the repo's `playlistsFlow().collectAsStateWithLifecycle(initial = emptyList())`).
    - Wrap the `LazyColumn` in a `pullRefresh` (Material 3 `PullToRefreshBox` or accompanist fallback if AGP 8.2.2 + compose-bom 2024.01.00 don't have it natively — verify in implement stage). On refresh: `viewModelScope.launch { Firebase.functions.getHttpsCallable("syncAllPlaylists").call() }` with spinner during in-flight.
    - Update `PlayCard` call site to pass `PlaylistDoc`.
23. Edit `screens/playlist/PlayCard.kt` — switch parameter type to `PlaylistDoc`; adjust property accesses; remove `com.google.api.services.youtube.model.Playlist` import.
24. Create `screens/playlist/VideoListScreen.kt` stub (Firestore listener on `videos/`; no-op on tap). Add `composable("videos/{playlistId}") { ... }` to `PlaysterNavHost.kt`. Hook tile tap in `PlaylistScreen` to navigate. Create `screens/common/QuotaBanner.kt` no-op placeholder Composable.

### Phase E — Bootstrap + verification (commits: `chore: docs + bootstrap-uid procedure`)

(Bootstrap & verification is interactive — see Test / Verification Plan below. Final commit lands the docs/runbook.)

## Test / Verification Plan

### Automated checks

- **Backend lint+build:** `pnpm --filter functions run lint && pnpm --filter functions run build`. Gate: zero errors/warnings.
- **Backend rules + callable tests:** `firebase emulators:exec --only auth,firestore --project playster-rules-test "pnpm --filter functions run test"`. Covers AC-1, AC-2, AC-15 (partial). All vitest specs green.
- **Backend cron unaffected:** existing `scheduledSync` definition has no auth context and is not touched — verified by `pnpm --filter functions run build` producing the same export shape. Optional: emulator cron trigger via `firebase functions:shell` invoking `scheduledSync()` manually; assert it completes without throwing (slice-local AC).
- **Android assemble + unit tests:** `cd android && ./gradlew :app:assembleDebug :app:testDebugUnitTest`. Gate: build success, junit tests green (existing test surface is minimal — no new tests required by this slice, snapshot/unit tests of `FirestoreRepository` are nice-to-have but out of scope).
- **APK size delta:** `./gradlew :app:assembleRelease` pre/post slice. Expected reduction from dropping `google-api-services-youtube` (a large transitive). Capture in slice retro.

### Interactive verification

**AC-1 — Unauthenticated callable rejected**
- Platform: backend, via Firebase emulator. Tool: vitest + `firebase-functions-test` (automated above). Additionally, manual smoke: `curl` against deployed `https://us-central1-playster-406121.cloudfunctions.net/syncAllPlaylists` with no `Authorization` header → 401 / Functions-format `unauthenticated` JSON.
- Bootstrap commands: deploy via `firebase deploy --only functions:syncAllPlaylists`.
- Evidence: response body screenshot or `gcloud functions logs read syncAllPlaylists --limit 10` excerpt.
- Pass criteria: error code `unauthenticated`.

**AC-2 — Non-allowlisted uid rejected**
- Platform: backend, via emulator (automated). Manual smoke: sign in to a *second* Google account on a test device, attempt pull-to-refresh, observe `permission-denied`.
- Evidence: lazylogcat capture filtered on `playster.sync` showing the callable failure with code.
- Pass criteria: `permission-denied`.

**AC-3 — Android playlist screen renders from Firestore, no YouTube Data API calls**
- Platform: Android (AVD or device). Tool: `maestro` (per stack: `maestro` listed). Companion: `lazylogcat` (per stack).
- Bootstrap: complete the two-pass deploy (rules + ALLOWED_UID set to real uid). Ensure backend Firestore has at least one playlist (verify via `gcloud firestore documents list --collection-group=playlists`); if empty, manually invoke `scheduledSync` from Firebase Functions shell.
- Maestro flow (`android/maestro/signin-and-see-playlists.yaml`): `launchApp`, tap "Sign in with Google", complete Credential Manager flow (semi-manual — Maestro can drive Credential Manager bottom sheet on test images), assert at least one playlist tile visible by content-description.
- Concurrent capture: `lazylogcat --package com.github.jayteealao.playster --grep youtube.googleapis.com > evidence/ac3-network.log` started before launch, stopped after assertion.
- Pass criteria: Maestro assertion passes (≥1 playlist tile); `evidence/ac3-network.log` is empty (zero matches for `youtube.googleapis.com`).

**AC-4 — Pull-to-refresh triggers sync, `lastSyncedAt` advances**
- Platform: Android + backend. Tool: `maestro` + `firebase` CLI to read Firestore.
- Maestro flow (`android/maestro/pull-to-refresh.yaml`): sign in, navigate to playlist screen, capture `lastSyncedAt` from `playlists/{first-id}` via a pre-test fetch, perform `swipeDown` on the LazyColumn, wait for spinner to disappear (timeout 30s), assert `lastSyncedAt` is strictly greater.
- Bootstrap commands: same as AC-3.
- Evidence: Maestro recording + `gcloud firestore` doc snapshots before/after.
- Pass criteria: `lastSyncedAt` advances within 30s of pull gesture.

**AC-15 (partial) — Firestore rules deny strangers on `playlists/`, `videos/`, `tokens/`, `sync_state/`**
- Platform: backend, via `@firebase/rules-unit-testing` (automated above). One additional manual check after deploy: from a clean browser using the Firebase Auth REST API with a *different* Google account's id token, attempt to read `playlists/<known-id>` via the Firestore REST API → expect 403.
- Pass criteria: automated test green; manual REST test 403.

**Slice-local AC — `firebase-functions ^7` does not regress `scheduledSync`**
- Platform: backend. Tool: `firebase` CLI + Cloud Logging.
- Bootstrap: `firebase deploy --only functions:scheduledSync`; manually invoke via `gcloud scheduler jobs run firebase-schedule-scheduledSync-us-central1`.
- Evidence: `gcloud functions logs read scheduledSync --limit 50` showing `scheduledSync: starting full sync` + `scheduledSync: completed` log lines.
- Pass criteria: no errors, completion log within timeout.

## Risks / Watchouts

- **Bootstrap two-pass deploy** is operator-only — automation here would require committing the uid to the repo. Documented in runbook; high-priority discovery item below.
- **`firebase-functions ^7.x` API drift on edge surfaces** — `SecretParam` import broke in v7.0.0 (fixed v7.0.1+). Pinning to `^7.2.5`. If `pnpm install` resolves an older minor for any reason, the build will fail loudly with an import error — fail-fast.
- **Android Credential Manager vs legacy GoogleSignIn** — Existing code has *both* paths. Switching the primary button to Credential Manager is straightforward but the One-Tap path (`Identity.getSignInClient`) is dead-code-but-imported in `AuthScreen.kt`. Leaving it untouched is safer for this slice; flag as discovery question.
- **Dropping `google-api-services-youtube` may surface transitive type references** — Grep across `android/app/src/main` confirms `Playlist` type is used only in `PlaylistScreen.kt` + `PlayCard.kt` (already in scope to rewrite). Re-grep at implement-time to be sure.
- **Atomic-flip PR size** — Both subprojects touched in one PR. Mitigation: commit organization (Phase A → E above) maps to commit boundaries so reviewer can scan one logical phase at a time.
- **`google-services.json` handling** — Holds project config (API keys + sender ID). Not strictly secret (Firebase recommends checking it in for public projects), but for a single-tenant project I prefer gitignoring. Document the bootstrap step explicitly.
- **Pull-to-refresh Composable availability** — `PullToRefreshBox` shipped in Material3 1.3+. Compose BOM 2024.01.00 ships 1.2.x. May need a small bump of the compose BOM (e.g., to 2024.05+) or fall back to accompanist. Plan stage flags as implementation-time verification.
- **Firestore listener leaks** — Slice 4 risk surfaces here first if `FirestoreRepository.playlistsFlow()` isn't built on `callbackFlow { ... awaitClose { listener.remove() } }`. Catch in implementation.
- **`ALLOWED_UID` via `defineString` semantics in v7** — `defineString` reads from `.env.<projectId>` or interactive prompt at deploy time, **not** Secret Manager. That's intentional (the uid isn't secret). Verify the deploy flow with `firebase deploy --only functions` actually picks up the param without further config. If it doesn't, fall back to `process.env.ALLOWED_UID` set via `firebase functions:config:set` (deprecated path).
- **Rules placeholder string accidentally shipped** — If the operator forgets to replace `__BOOTSTRAP_UID__` in rules, no one can read Firestore. Mitigation: add a `firebase deploy:rules` precheck script (out of slice scope; mention in runbook).

## Dependencies on Other Slices

- **`summary-orchestration` (slice 3) downstream:** imports `allowlistedCall`, `requireAllowlistedUid`, `ALLOWED_UID` from `backend/functions/src/auth/verify.ts`. Extends `backend/firestore.rules` with `summaries/` and `quota/` collections (the catch-all deny-rule at the bottom currently shadows them — slice 3 must insert its blocks *above* the catch-all).
- **`summary-ui` (slice 4) downstream:** consumes the Firebase Auth state from `FirebaseAuthBridge` (now established here); follows the `FirestoreRepository` listener pattern; uses the `QuotaBanner` placeholder location.
- **`summarizer-container` (slice 2):** no direct dependency on this slice. They are parallelizable per slice docs.

## Assumptions

- Backend is already deployed (per PO Round 3 "isnt playster backend already deployed as well") and `playlists/`, `videos/`, `sync_state/`, `tokens/` collections have data populated by the existing `scheduledSync` cron. If not, the implement stage will trigger a manual `scheduledSync` invocation to seed AC-3/AC-4 data.
- Operator has a Firebase project `playster-406121` (per `.firebaserc`) with Authentication → Google provider enabled. If not, configuring the provider is a one-step operator action, documented in the bootstrap runbook.
- Operator has `gcloud`, `firebase`, `android`, `maestro`, `lazylogcat` CLIs available in their environment (per `00-index.md` stack confirmation).
- Compose Material3 `PullToRefreshBox` is available with a modest compose-BOM bump if needed.
- The hardcoded server client ID in `AuthScreen.kt` line 79 / 96 (`510333739373-ust5kheckkg2oiuoghp08l5ghm1fsmat.apps.googleusercontent.com`) is for the Firebase project tied to `playster-406121`; will work as-is for Credential Manager → Firebase Auth bridging.
- `firebase-functions ^7` accepts `firebase-admin ^13` as peer (confirmed by lockfile inspection; v7 changelog doesn't change peer constraint).

## Blockers

None.

## Open Decisions for Discovery Phase

1. **Two-step bootstrap procedure — placeholder rules vs one-shot `setAllowlistedUid` HTTP function.** The plan proposes "deploy with `__BOOTSTRAP_UID__` placeholder → operator signs in once → operator replaces placeholder and redeploys" because it adds zero attack surface and zero new code paths. The planning doc §1.4 alternative was a tiny `onRequest` function gated by a `BOOTSTRAP_TOKEN` secret. Which does the PO prefer? (Cost difference: placeholder = 0 new functions; bootstrap fn = 1 new function + 1 new secret + 1 deletion-after-bootstrap step.)
2. **Credential Manager exclusivity in this slice.** `AuthScreen.kt` currently scaffolds three sign-in paths (legacy GoogleSignIn, One-Tap `Identity`, Credential Manager). Plan removes legacy GoogleSignIn entirely (it's tied to the obsolete `YouTubeScopes.YOUTUBE_READONLY` request). Plan leaves One-Tap dead code in place to minimize churn. Does PO want a fuller cleanup that also deletes One-Tap, or is "remove only what's load-bearing for this slice" the right scope?
3. **`google-services.json` gitignore vs commit.** Plan gitignores it. Firebase officially says the file is safe to check in (no real secret material — just public project identifiers). Single-tenant + private repo nuance: checking it in makes the bootstrap easier (`firebase deploy` works without operator manual step) but stamps the project ID into git history. PO call.
4. **`firebase-functions ^7` bump scope.** If v7 install surfaces unexpected build errors that need more than ~1 hour of investigation, do we (a) push through, or (b) revert to `^6.6.0` (which the lockfile already resolves cleanly) and document v7 as a v1.1 follow-up? The shape doc commits to ^7 but the practical "peer conflict" framing isn't entirely accurate — ^6.6 + admin ^13 do install.

## Freshness Research

- **`firebase-functions` v7.0 release (Apr 2026), via [GitHub releases](https://github.com/firebase/firebase-functions/releases) + [v7.0.0 newreleases.io mirror](https://newreleases.io/project/github/firebase/firebase-functions/release/v7.0.0).** Relevance: chosen target version for this slice. Takeaway: only meaningful break is removal of `functions.config()` (we don't use it). `onCall`/`onSchedule`/`onRequest`/`defineSecret`/`defineString` unchanged at the call-site level. TypeScript v5 + ES2022 target. Pin to `^7.2.5` (v7.0.0 had a `SecretParam` export bug, fixed in patch).
- **Firebase Android BOM v34+ KTX module removal (Jul 2025), via [Migrate to KTX-in-main-modules](https://firebase.google.com/docs/android/kotlin-migration) + [Firebase KTX deprecation note](https://www.blamechris.com/archery-apprentice-docs/developer-guide/guides/firebase-ktx-deprecation).** Relevance: dictates which Gradle artifact names to use. Takeaway: use `com.google.firebase:firebase-auth` (no `-ktx` suffix). API stays Kotlin-flavored; just module rename. Pin BOM to `34.0.0` (or latest stable at implement time).
- **Android Credential Manager Sign in with Google ([developer.android.com guide](https://developer.android.com/identity/sign-in/credential-manager-siwg) + [Firebase Auth Android guide](https://firebase.google.com/docs/auth/android/google-signin)).** Relevance: replaces legacy `GoogleSignIn`. Takeaway: scaffolding already in `AuthScreen.kt`; complete it. Pattern: `GoogleIdTokenCredential.createFrom(credential.data).idToken` → `GoogleAuthProvider.getCredential(idToken, null)` → `FirebaseAuth.getInstance().signInWithCredential(...).await()`.
- **`@firebase/rules-unit-testing` v5 ([Firebase rules unit tests doc](https://firebase.google.com/docs/rules/unit-tests) + [test-rules-emulator](https://firebase.google.com/docs/firestore/security/test-rules-emulator)).** Relevance: AC-1, AC-2, AC-15 (partial) verification. Takeaway: `initializeTestEnvironment({ projectId, firestore: { rules: fs.readFileSync(...) } })` + `testEnv.authenticatedContext("uid").firestore()` + `assertFails`/`assertSucceeds`. Persists data between test invocations — call `clearFirestoreData` between specs.
- **Firestore Security Rules single-tenant patterns ([rules-conditions](https://firebase.google.com/docs/firestore/security/rules-conditions) + [rules-and-auth](https://firebase.google.com/docs/rules/rules-and-auth)).** Relevance: validate hardcoded-uid-in-rules approach. Takeaway: idiomatic for single-tenant; Admin SDK bypasses rules entirely; default-deny if no rule matches. Custom claims are a marginally cleaner alternative for v1.1.
- **`firebase-functions ^6` + `firebase-admin ^13` peer compatibility (lockfile inspection of `pnpm-lock.yaml`).** Relevance: real status of the "peer conflict" referenced in shape. Takeaway: actual lockfile shows `firebase-functions@6.6.0` resolves `firebase-admin@13.10.0` cleanly (peer accepts ^13). The v7 bump is forward-looking, not a hard install blocker — adjusts the discovery question (4) accordingly.

## Recommended Next Stage

- **Option A (default):** `/wf implement wire-android-backend-summarizer auth-and-android-firebase` — start implementation of this slice. The plan is execution-ready; discovery phase has resolved all open decisions.

## Revision History

**2026-05-18T07:49:39Z — Discovery phase reconciliation (initial plan write):**
- Discovery phase locked: **placeholder rules + 2-pass deploy** for the bootstrap chicken-and-egg (PO confirmed default). `__BOOTSTRAP_UID__` sentinel in rules + `ALLOWED_UID="__BOOTSTRAP_UID__"` initial value; operator captures the real uid from the Firebase console after first sign-in, replaces both, redeploys. No `setAllowlistedUid` bootstrap function shipped. Documented in `docs/operations/bootstrap-allowlisted-uid.md`.
- Discovery phase locked: **push through `firebase-functions ^7.2.5`** even on friction (PO confirmed default). If pnpm install or build errors surface during Phase A step 2, budget ~1 hour fixing call-site issues. No fallback to `^6.6` planned. Aligns with shape doc.
- Discovery phase accepted-default: **Credential Manager replaces legacy GoogleSignIn only** in this slice (PO accepted default in consolidated question). One-Tap dead code in `AuthScreen.kt` stays untouched; revisit cleanup in v1.1.
- Discovery phase accepted-default: **`android/app/google-services.json` is gitignored** (PO accepted default). Operator places it at bootstrap time; documented in runbook.
- No cohesion fixes required for this slice — it is the upstream root and downstream slices conform to it.

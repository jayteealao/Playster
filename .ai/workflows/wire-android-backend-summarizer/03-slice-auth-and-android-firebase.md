---
schema: sdlc/v1
type: slice
slug: wire-android-backend-summarizer
slice-slug: auth-and-android-firebase
status: defined
stage-number: 3
created-at: "2026-05-17T21:45:53Z"
updated-at: "2026-05-17T21:45:53Z"
complexity: l
depends-on: []
tags: [android, firebase-auth, firestore, backend, onCall, cleanup]
refs:
  index: 00-index.md
  slice-index: 03-slice.md
  siblings:
    - 03-slice-summarizer-container.md
    - 03-slice-summary-orchestration.md
    - 03-slice-summary-ui.md
  plan: 04-plan-auth-and-android-firebase.md
  implement: 05-implement-auth-and-android-firebase.md
---

# Slice: Auth + Android Firebase view (atomic flip)

## Goal

Atomically swap the Playster backend from unauthenticated `onRequest` HTTP triggers to Firebase-Auth-allowlisted `onCall` callable triggers, deploy Firestore security rules tied to a single operator uid, and replace the Android app's device-side YouTube Data API path with a Firestore live view. After this slice, the operator signs in once with Google → Firebase Auth, sees their playlists/videos rendered live from Firestore, and the device never calls the YouTube Data API again.

## Why This Slice Exists

This is the highest-visibility change in the workflow and the foundation every later slice depends on. The `allowlistedCall` wrapper built here is reused by slice 3's `requestVideoSummary`. The `firestore.rules` file deployed here governs every Firestore read the rest of the workflow performs. The Android Firestore-listener pattern established here is the pattern slice 4's SummaryScreen will follow.

It exists as **one atomic slice** rather than two (backend then Android) because shipping the backend lockdown alone would leave the existing Android app calling the old endpoints, which would no longer exist as `onRequest`. PO explicitly chose atomic flip to avoid that transitional state.

## Scope

**In scope:**

- Backend: new `auth/verify.ts` with `requireAllowlistedUid` + `allowlistedCall<TIn, TOut>` higher-order wrapper.
- Backend: convert `syncAllPlaylists`, `syncPlaylist`, `syncWatchLater` from `onRequest` to `onCall`, wrap with `allowlistedCall`. `scheduledSync` stays `onSchedule` (no auth context).
- Backend: bump `firebase-functions` from `^6.3.0` to `^7` to resolve the `firebase-admin ^13` peer-dependency conflict. Address any v7 API breakage that surfaces.
- Backend: introduce `defineString("ALLOWED_UID")` config param and document the bootstrap procedure (first sign-in → capture uid → set config → redeploy).
- Backend: write `backend/firestore.rules`. Single hardcoded uid for read; write denied (Admin SDK only). Cover all collections that exist today (`playlists/`, `videos/`, `tokens/`, `sync_state/`) plus the not-yet-created `summaries/` and `quota/` collections (rules ready for slice 3).
- Backend: add Firebase emulator suite configuration (`firebase.json` updates if needed) and rules-unit-test scaffolding for emulator-based AC verification.
- Backend: clean up. Remove the unauth `onRequest` versions of the three sync functions. No transitional dual-stack. If the existing legacy `setCookies` endpoint exists on `onRequest`, leave it untouched (PO deferred).
- Android: add Firebase BOM + auth/firestore/functions KTX dependencies to `android/app/build.gradle.kts`.
- Android: add `google-services.json` under `android/app/` (operator generates via Firebase console; not committed if it contains secrets — gitignore as needed).
- Android: in `authViewModel.kt`, after Google Sign-In succeeds, build `GoogleAuthProvider.getCredential(idToken, null)` and `signInWithCredential(...)` on `FirebaseAuth.getInstance()`. Persist Firebase Auth state.
- Android: define Kotlin data classes mirroring `PlaylistDocument` and `VideoDocument` from `backend/functions/src/models/index.ts`.
- Android: rewrite `PlaylistScreen.kt` to consume `Firebase.firestore.collection("playlists").snapshots()` (Flow) and render from `PlaylistDoc` objects. Remove the `LaunchedEffect` block that calls YouTube Data API.
- Android: add a video-list screen (basic list view consuming `videos/{videoId}` filtered by playlist) — required so the operator can navigate from playlist tile to video list. Stop short of the full VideoDetailScreen (that's slice 4); a click on a video tile in this slice can be a no-op or a placeholder.
- Android: pull-to-refresh on the playlist screen invokes `Firebase.functions.getHttpsCallable("syncAllPlaylists").call()`. Show a spinner during the in-flight period.
- Android: drop `google-api-services-youtube` + `google-api-client-android` + `google-http-client-android` + the `GoogleAccountCredential` usage from `authViewModel.kt`. Keep `credentials` and `play-services-auth` for Google Sign-In (those are still needed to obtain the ID token for Firebase Auth bridging).
- Android: scaffold the QuotaBanner Composable as a no-op or hidden placeholder. Slice 4 wires it to `quota/openrouter`; this slice just reserves the visual real estate so the navigation graph doesn't change later.

**Out of scope (handled by other slices):**

- VideoDetailScreen + SummaryScreen + actual QuotaBanner observation → slice 4 (`summary-ui`).
- `requestVideoSummary` callable + `summaries/` collection + dispatcher cron → slice 3 (`summary-orchestration`).
- Summarizer Cloud Run container + Dockerfile + webhook signing → slice 2 (`summarizer-container`).
- Removing legacy `setCookies` endpoint → deferred (PO).
- Removing summarize-api `mode` field → deferred (PO).

## Acceptance Criteria

Mapped to shape's ACs:

- **AC-1**: Unauthenticated callable returns `unauthenticated`. Verified via Firebase emulator test calling `syncAllPlaylists` with no ID token.
- **AC-2**: Non-allowlisted uid returns `permission-denied`. Verified via Firebase emulator test with a stranger uid token.
- **AC-3**: Android playlist screen renders from Firestore with zero `youtube.googleapis.com` network calls from the device. Verified via a Maestro flow that signs in, lands on the playlist screen, asserts at least one playlist tile is visible. Concurrent `lazylogcat` capture filtered for `youtube.googleapis.com` must show zero matches.
- **AC-4**: Pull-to-refresh triggers `syncAllPlaylists` and `lastSyncedAt` on Firestore docs advances. Verified via Maestro pull gesture + Firestore doc read assertion.
- **AC-15** (partial — covers `playlists/`, `videos/`, `tokens/`, `sync_state/`): Firestore rules deny stranger uids and allow the allowlisted uid. Verified via `@firebase/rules-unit-testing`. Slice 3 extends with `summaries/`, `quota/`.

**Slice-local AC (not in shape):** The backend `firebase-functions ^7` upgrade does not regress any existing function. Verified by:
- `pnpm --filter functions run build` succeeds.
- Existing `scheduledSync` cron still triggers correctly under emulator.

## Dependencies on Other Slices

- None upstream. This is one of two root slices (the other is `summarizer-container`).

Downstream consumers:
- `summary-orchestration` imports `allowlistedCall` and `requireAllowlistedUid` from `auth/verify.ts` (introduced here).
- `summary-orchestration` extends `firestore.rules` with `summaries/` + `quota/` collections (skeleton allowed here, contents filled there).
- `summary-ui` consumes the Firebase Auth state and Firestore listener pattern established here.

## Risks

- **Bootstrap chicken-and-egg.** Operator's Firebase Auth uid is not known until first successful sign-in. But `ALLOWED_UID` (config) and the hardcoded uid in `firestore.rules` need to exist *before* the slice is "live". Mitigation: deploy in two passes. First deploy with `ALLOWED_UID=""` (no one passes the check) and rules with a placeholder uid (no one reads); operator signs in once on dev; capture the uid from Firebase Auth console; set `ALLOWED_UID` config + replace placeholder in rules; redeploy. Document this two-step explicitly in slice 1's plan stage.
- **`firebase-functions ^7` API drift.** Bumping from `^6.3` may surface breaking changes in `onCall`, `onSchedule`, or secret-handling APIs. Plan stage must enumerate the v7 changelog deltas and adjust call sites. Worst case: a few hours of API-shape fixes.
- **PO's assertion that the backend is already deployed.** If the backend is *not* in fact deployed today, the visible-first AC (AC-3 showing playlists from Firestore) requires a deploy + at least one `scheduledSync` invocation to populate Firestore data before AC-3 can pass. Mitigation: slice 1's plan stage verifies deployment state first; if Firestore is empty, a manual `pnpm --filter functions run shell` invocation of `scheduledSync` populates data for verification.
- **Android Google Sign-In → Firebase Auth bridging edge cases.** If `GoogleSignInAccount.getIdToken()` returns null (rare but possible when the user hasn't refreshed credentials), `GoogleAuthProvider.getCredential(null, null)` will fail. Mitigation: surface a clear "Re-authenticate with Google" error path.
- **Dropping youtube-data-api dependencies might surface unexpected coupling.** PlaylistScreen's video-detail navigation may have transitive uses of `com.google.api.services.youtube.model.*` types. Mitigation: plan stage greps the Android module for residual references before declaring the cleanup safe.
- **Firestore rules are append-only across slices.** Slice 1 writes rules covering existing collections; slice 3 extends them. A regression here (e.g., rules left over-permissive) would silently allow stranger reads to `summaries/` later. Mitigation: rules-unit-test covers stranger denial for every collection mentioned in rules — including the empty-future ones.
- **Atomic flip means a single PR with both backend + Android changes.** Larger review surface; harder for one reviewer. Mitigation: organize commits so backend changes precede Android changes within the PR, and verification evidence (emulator suite + Maestro flow) is attached.

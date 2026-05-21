---
review-command: architecture
slug: wire-android-backend-summarizer
scope: diff
target: git diff main...HEAD
completed: 2026-05-21
id-prefix: ARCH-
verdict: Don't ship
---

# Architecture Review — wire-android-backend-summarizer

**Scope:** Full branch diff (`git diff main...HEAD`)
**Reviewer:** Architecture Review Agent
**Date:** 2026-05-21

## Summary

The branch introduces three new architectural subsystems (backend summarizer module, Android data + UI layers, summarizer container) and wires them into a coherent pipeline. The dependency direction is broadly correct — Android screens depend on data layer; backend summarizer depends on auth and models; summarizer container is self-contained. No circular dependencies are detected.

However, one BLOCKER-severity structural mismatch exists: the Android `FirestoreRepository.videosFlow()` queries a top-level `collection("videos")` that does not exist; the backend has always written videos to the subcollection `playlists/{playlistId}/videos/{videoId}`. This will produce an empty list at runtime. One HIGH-severity concern is present: `QuotaState` (a UI-concern sealed interface) and `SummaryStatus` (a wire-format enum) are defined in `data/firestore/` — a data-layer package — and are directly imported by multiple UI-layer files, blurring the boundary between DTO and UI model. Several MED findings address naming ambiguity and abstraction leakage.

**Architectural Style:** Layered (backend: Functions → Domain → Firestore; Android: Screen → ViewModel → Repository → Firestore)

**Severity Breakdown:**
- BLOCKER: 1
- HIGH: 1
- MED: 3
- LOW: 2
- NIT: 1

**Key Metrics:**
- Circular dependencies detected: 0
- God objects (>5 responsibilities): 0
- High coupling modules (fan-out > 10): 0
- Layer violations: 1 (BLOCKER)
- Collection-path mismatches: 1 (BLOCKER)

---

## Architectural Map

### Backend (`backend/functions/src/`)

```
index.ts (Cloud Function exports)
  ↓ imports
auth/verify.ts          — allowlist gate (ALLOWED_UID, allowlistedCall)
auth/index.ts           — re-exports: oauth, innertube, tv-oauth, handlers, verify
models/index.ts         — shared TS interfaces: PlaylistDocument, VideoDocument,
                          SummaryDocument, QuotaDocument
summarizer/
  dispatch.ts           — requestVideoSummary callable + dispatchSummary()
  dispatcher.ts         — summaryDispatcher cron, drainSummaryQueue()
  quota.ts              — reserveOpenRouterQuotaSlot, getQuotaBudget
  webhook.ts            — summaryWebhook HTTPS handler
  autoEnqueue.ts        — enqueueAutoSummary (called from sync handlers)
  secrets.ts            — SUMMARIZER_URL, SUMMARIZER_API_KEY
youtube/                — pre-existing sync engine (unchanged boundary)
```

Dependency direction: `index → auth, summarizer, youtube → models`  
No cycles. `webhook.ts` does not import `dispatch.ts`; `dispatch.ts` does not import `webhook.ts`.

### Summarizer container (`summarizer/`)

```
summarize-api/src/webhooks/
  signer.ts    — buildSignatureHeader (HMAC)
  deliver.ts   — deliverWebhook (3 retries, non-retryable 4xx)
deploy/
  entrypoint.js    — boots daemon, awaits /health, calls /v1/refresh-free, imports api
  Dockerfile       — multi-stage: daemon-build, api-build, runtime
  mock-backend/    — verification harness only (not shipped to Cloud Run)
```

The webhook signing is self-contained in `summarize-api/src/webhooks/`. The container boundary is clear: daemon runs as a child process; api imports in-process.

### Android (`android/app/src/main/`)

```
data/
  auth/FirebaseAuthBridge.kt     — Google Sign-In → Firebase Auth bridge
  firestore/
    FirestoreRepository.kt       — Firestore listeners (playlists, videos, summary, quota)
    PlaylistDoc.kt               — DTO
    VideoDoc.kt                  — DTO
    SummaryDoc.kt                — DTO + toSummaryDoc() extension
    QuotaDoc.kt                  — DTO + QuotaState sealed interface + toQuotaState()
    SummaryDoc.kt                — SummaryStatus enum (wire format) here too
  AppModule.kt                   — Hilt providers for Firebase*
  functions/SummaryFunctions.kt  — requestVideoSummary callable wrapper
screens/
  auth/                          — AuthScreen, AuthViewModel, PlaysterNavHost
  playlist/PlaylistViewModel      — → FirestoreRepository
  playlist/VideoListScreen        — ViewModel → SummaryRepository; Composable → rememberQuotaState
  videoDetail/VideoDetailViewModel — → FirestoreRepository
  videoDetail/summary/SummaryViewModel — → SummaryRepository, SummaryFunctions
  common/QuotaBanner.kt          — QuotaBannerViewModel → QuotaRepository
```

Expected dependency direction: `screens → data (repositories, DTOs) → Firestore SDK`.  
Actual violations: see ARCH-002.

---

## Findings

### ARCH-001: videosFlow queries a non-existent top-level collection [BLOCKER]

**Location:** `android/app/src/main/java/com/github/jayteealao/playster/data/firestore/FirestoreRepository.kt:41`  
**Confidence:** High  
**Category:** Data-path structural mismatch

**Issue:**  
`videosFlow(playlistId)` opens a listener on `firestore.collection("videos").whereEqualTo("playlistId", playlistId)`. But the backend sync engine has always written video documents to the Firestore subcollection path `playlists/{playlistId}/videos/{videoId}` — never to a root-level `videos` collection. The root-level collection does not exist, so this listener will always emit an empty list. The playlist/video list screen will appear permanently empty after navigation.

**Evidence:**

Backend (pre-existing, confirmed unchanged on this branch):
```typescript
// backend/functions/src/youtube/api-sync.ts:142
const videoRef = playlistRef.collection("videos").doc(videoId);
// playlistRef = db.collection("playlists").doc(playlistId)
// → path: playlists/{playlistId}/videos/{videoId}
```

Android (new in this branch):
```kotlin
// FirestoreRepository.kt:41
val listener: ListenerRegistration = firestore.collection("videos")
    .whereEqualTo("playlistId", playlistId)
    .addSnapshotListener { ... }
// queries root "videos" — which does not exist
```

Note: `VideoDoc` has no `playlistId` field (confirmed in `VideoDoc.kt`), and `VideoDocument` in backend models has no `playlistId` field either, so the `whereEqualTo` filter would fail even if the collection existed.

`videoFlow(videoId)` at line 57 correctly uses `collectionGroup("videos")` — only `videosFlow` has the bug.

**Impact:**
- All playlist video-list screens will be empty at runtime.
- The path `playlists/{playlistId}/videos/{videoId}` is also unprotected by the current Firestore rules: `match /videos/{document=**}` matches only the root collection, not the subcollection. The subcollection is implicitly readable (falls through to the catch-all deny). This is a secondary correctness issue.

**Fix — Option A (recommended):** Change `videosFlow` to use `collectionGroup`:
```kotlin
fun videosFlow(playlistId: String): Flow<List<VideoDoc>> = callbackFlow {
    val listener: ListenerRegistration = firestore
        .collectionGroup("videos")
        .whereEqualTo("playlistId", playlistId)   // still wrong — VideoDoc has no playlistId
        .addSnapshotListener { ... }
}
```
But `VideoDoc` has no `playlistId` field. The real fix requires choosing one of:

**Fix — Option A (correct):** Add `playlistId` to `VideoDocument` and `VideoDoc`, write it in the backend sync, and use `collectionGroup("videos").whereEqualTo("playlistId", playlistId)`.

**Fix — Option B:** Change the listener to query directly by the subcollection path and discard the `playlistId` filter:
```kotlin
fun videosFlow(playlistId: String): Flow<List<VideoDoc>> = callbackFlow {
    val listener: ListenerRegistration = firestore
        .collection("playlists").document(playlistId)
        .collection("videos")
        .addSnapshotListener { ... }
}
```
This requires no model change and no backend change, and aligns with how the backend writes. Recommended.

Also update `firestore.rules` to cover the subcollection explicitly:
```
match /playlists/{playlistId}/videos/{videoId} { allow read: if isAllowlisted(); }
```

**Refactoring Steps:**
1. In `FirestoreRepository.kt`, replace line 41's `firestore.collection("videos")` query with a subcollection path query as in Option B.
2. Update `backend/firestore.rules` to add the subcollection rule.
3. Verify against the existing emulator tests.

---

### ARCH-002: UI-concern types (`QuotaState`, `SummaryStatus`) defined in the data layer [HIGH]

**Location:** `android/app/src/main/java/com/github/jayteealao/playster/data/firestore/QuotaDoc.kt:22` and `SummaryDoc.kt:7`  
**Confidence:** High  
**Category:** Layer boundary violation — presentation concern leaked into infrastructure

**Issue:**  
`QuotaState` (a sealed interface with `Healthy`, `DailyExhausted`, `PerMinuteExhausted` variants used to drive UI rendering) is defined in `data/firestore/QuotaDoc.kt`, which is a Firestore DTO file in the data layer. `SummaryStatus` (a wire-format → UI mapping enum) is defined in `data/firestore/SummaryDoc.kt`.

Both are UI-driving types that encode display policy (which banner copy to show, whether to enable a CTA). Their definitions in the data layer create an upward dependency: the data layer now contains UI-level semantics. Any change to quota display policy (e.g. adding a new exhaustion variant) requires editing the data package.

Three screen-layer files import directly from the data package to get these types:
```kotlin
// QuotaBanner.kt:22-23
import com.github.jayteealao.playster.data.firestore.QuotaState
import com.github.jayteealao.playster.data.firestore.toQuotaState

// VideoListScreen.kt:44,46
import com.github.jayteealao.playster.data.firestore.QuotaState
import com.github.jayteealao.playster.data.firestore.SummaryStatus

// SummaryViewModel.kt:9
import com.github.jayteealao.playster.data.firestore.SummaryStatus
```

**Impact:**  
- The data layer cannot be independently unit-tested without pulling in Compose/UI semantics.
- Adding a new quota exhaustion variant (e.g. "credit exhausted" vs "rate limited") requires editing a Firestore DTO file and redeploying.
- `SummaryStatus.UNKNOWN` is a UI sentinel value (no-doc state) embedded in the wire-format enum.

**Fix:** Move `QuotaState`, `toQuotaState()`, `SummaryStatus` into the screen layer or a dedicated `domain/` package. The data layer should return raw DTOs (`QuotaDoc`, `SummaryDoc`); mapping to UI state belongs in the ViewModel.

```kotlin
// Move to: screens/common/QuotaState.kt
sealed interface QuotaState { ... }
fun QuotaDoc?.toQuotaState(): QuotaState { ... }

// Move to: screens/videoDetail/summary/SummaryStatus.kt  (or inline in SummaryViewModel)
enum class SummaryStatus { QUEUED, PENDING, RUNNING, COMPLETED, FAILED_TRANSIENT, FAILED_PERMANENT, UNKNOWN }
```

---

### ARCH-003: `dispatch.ts` and `dispatcher.ts` — naming collision at the module level [MED]

**Location:** `backend/functions/src/summarizer/dispatch.ts` and `backend/functions/src/summarizer/dispatcher.ts`  
**Confidence:** High  
**Category:** Unclear responsibilities / naming ambiguity

**Issue:**  
Two files differ by a suffix ("er") and export overlapping concerns:
- `dispatch.ts` exports `dispatchSummary()` (the core dispatch function) AND `requestVideoSummary` (the Cloud Function callable).
- `dispatcher.ts` exports `drainSummaryQueue()` (batch dispatch loop) AND `summaryDispatcher` (the cron Cloud Function).

`dispatcher.ts` imports `dispatchSummary` from `dispatch.ts`, creating a dependency that is not obvious from the naming. A reader looking for "how dispatch works" must read both files. The callable (`requestVideoSummary`) and the pure function (`dispatchSummary`) live in the same file, mixing the Cloud Functions adapter layer with the domain logic.

**Impact:** Naming confusion slows onboarding; the callable/pure-function conflation reduces testability of `dispatchSummary` independent of the Firebase callable wrapper.

**Fix:** Rename or split for clarity:
- `dispatch.ts` → split into `dispatch-core.ts` (pure `dispatchSummary`) and `callable.ts` (the `requestVideoSummary` export).
- Or: rename `dispatcher.ts` to `dispatch-cron.ts` to distinguish from the callable module.
- The shape doc itself mentions both in the same list (`dispatch.ts` and `dispatcher.ts`), so this confusion was inherited from planning. Fixing it here prevents long-term friction.

---

### ARCH-004: `webhookSecret` persisted in `summaries/{videoId}` is readable by the allowlisted Android client [MED]

**Location:** `backend/functions/src/models/index.ts:49`, `backend/firestore.rules:14`  
**Confidence:** High  
**Category:** Data boundary — sensitive field readable via client Firestore rules

**Issue:**  
`SummaryDocument.webhookSecret` is written by `dispatch.ts` (line 113) and read by `webhook.ts` (line 139) to verify incoming HMAC signatures. The `firestore.rules` grants the allowlisted Android client read access to all of `summaries/{document=**}`. This means the client can read `webhookSecret` directly. While the single-tenant assumption means the client and operator are the same human, the secret's purpose is to authenticate the summarizer's webhook callbacks — leaking it to any client (even the allowlisted one) is an unnecessary boundary violation.

The Android `SummaryDoc.kt` does not currently request this field (the `toSummaryDoc()` mapper does not map `webhookSecret`), so no information is actively consumed. But the field is readable, and its presence in the shared `SummaryDocument` interface signals it as a public data shape.

**Impact:** Low practical risk (single tenant) but violates the principle that signature secrets should be server-side only and never readable by the client, even if trusted. Future multi-tenant migration would immediately make this a security gap.

**Fix:** Remove `webhookSecret` from the Firestore-readable document by storing it in a separate server-side collection (`summarySecrets/{videoId}` with read=false) or by using Firestore field-level security. Minimal option: add a field mask in the Firestore rules to exclude `webhookSecret` when the allowlisted client reads.

---

### ARCH-005: Single-tenant assumption is not encapsulated in a named constant or config module [MED]

**Location:** `backend/functions/src/auth/verify.ts:16`, `backend/firestore.rules:5`  
**Confidence:** Med  
**Category:** Scattered single-tenant assumption / future migration path

**Issue:**  
The single-tenant constraint is expressed in two disjoint places:
1. `ALLOWED_UID` in `auth/verify.ts` (applied to callables via the `allowlistedCall` wrapper).
2. The hardcoded uid string `"__BOOTSTRAP_UID__"` in `firestore.rules` (isAllowlisted() function).

These are not co-located and don't reference each other. The shape doc explicitly marks multi-tenant as Out-of-Scope v1, with migration cost noted. When that migration is attempted, there is no single place to update — the developer must know both files need changing.

`autoEnqueue.ts` also writes `summaries/{videoId}` docs at the top-level (not `users/{uid}/summaries/{videoId}`), confirming the singleton path assumption is baked into data writes, not just access control. The migration path to multi-tenant requires schema migration.

**Impact:** MED — not a functional defect now, but future migration is harder than it needs to be. The shape doc acknowledges this; the implementation should document it at the code level.

**Fix (minimal):** Add a code comment in both `verify.ts` and `firestore.rules` cross-referencing each other and explicitly noting the single-tenant assumption. Consider adding a `SINGLE_TENANT_NOTE.md` or a block comment in `index.ts` that lists all the files that must change for multi-tenant. This is the "future migration path preserved" concern from the user's context.

---

### ARCH-006: `QuotaBannerViewModel` defined in `screens/common/QuotaBanner.kt` — ViewModel co-located with Composable [LOW]

**Location:** `android/app/src/main/java/com/github/jayteealao/playster/screens/common/QuotaBanner.kt:31`  
**Confidence:** Med  
**Category:** Cohesion / separation of Composable from ViewModel

**Issue:**  
`QuotaBannerViewModel` is defined in the same file as the `QuotaBanner` Composable. This couples the view model's lifecycle to the Composable file, making it harder to test the ViewModel independently. It also breaks the convention used everywhere else in the codebase (`SummaryViewModel.kt`, `PlaylistViewModel.kt`, etc. are in separate files).

**Impact:** Low — test-ability concern only. No runtime impact.

**Fix:** Extract `QuotaBannerViewModel` to `screens/common/QuotaBannerViewModel.kt` for consistency.

---

### ARCH-007: `VideoDetailScreen` passes `autoDispatch` state through navigation route as a boolean query param [LOW]

**Location:** `android/app/src/main/java/com/github/jayteealao/playster/screens/auth/PlaysterNavHost.kt:50-52`  
**Confidence:** Med  
**Category:** Navigation data coupling

**Issue:**  
`autoDispatch` is a one-shot flag serialized into the nav route (`videoDetail/{videoId}?autoDispatch=true`). The `SummaryViewModel` reads it from `SavedStateHandle`. This creates a two-parameter nav argument where one (autoDispatch) is not a stable identifier but a transient navigation intent. If the user navigates back and returns via the back-stack (without deep-link), `autoDispatch` will be false regardless — this is correct behavior but relies on the route being reconstructed cleanly.

The deeper concern is that `VideoDetailViewModel` and `SummaryViewModel` both read `videoId` from `SavedStateHandle` independently, with no shared source of truth within the detail screen hierarchy.

**Impact:** Low — works correctly with Compose Navigation's current behavior. Becomes fragile if deeper back-stack restoration is added.

**Fix (optional):** Pass `autoDispatch` as a one-time nav argument that the destination ViewModel clears after first use, or encode the intent differently (e.g., a navigation event that triggers an immediate dispatch call before populating `SavedStateHandle`).

---

### ARCH-008: `__dispatchInternals` export in `dispatch.ts` leaks test scaffolding into the module interface [NIT]

**Location:** `backend/functions/src/summarizer/dispatch.ts:221`  
**Confidence:** High  
**Category:** Leaky abstraction

**Issue:**  
```typescript
export const __dispatchInternals = { videoExists, TERMINAL_STATUSES };
```
This is a test-helper export using a name convention (`__`) that signals "internal, don't use." However, exporting it from the module surface means downstream importers can depend on it. In TypeScript without barrel/access-modifier enforcement, this relies on naming convention alone.

**Fix:** Move test helpers into a dedicated test utility file (`tests/helpers/dispatch.ts`) and import from there in tests, removing the export from the production module.

---

## Recommendations

### Immediate Actions (BLOCKER/HIGH)

1. **Fix `videosFlow` Firestore path (ARCH-001)**: Change `firestore.collection("videos")` to `firestore.collection("playlists").document(playlistId).collection("videos")`. Also add the subcollection to `firestore.rules`.
   - Files affected: `android/app/.../data/firestore/FirestoreRepository.kt`, `backend/firestore.rules`
   - Estimated effort: 30 minutes

2. **Move `QuotaState` and `SummaryStatus` out of the data layer (ARCH-002)**: Relocate to `screens/` or a `domain/` package. Update all import sites.
   - Files affected: `data/firestore/QuotaDoc.kt`, `data/firestore/SummaryDoc.kt`, `screens/common/QuotaBanner.kt`, `screens/playlist/VideoListScreen.kt`, `screens/videoDetail/summary/SummaryViewModel.kt`
   - Estimated effort: 1–2 hours

### Architectural Improvements (MED/LOW)

3. **Rename `dispatch.ts`/`dispatcher.ts` (ARCH-003)**: Disambiguate module names to reduce reader confusion.
4. **`webhookSecret` field access control (ARCH-004)**: Add Firestore field-level masking or store in a server-only collection.
5. **Cross-reference single-tenant assumption (ARCH-005)**: Add comments linking `verify.ts` ↔ `firestore.rules`.
6. **Extract `QuotaBannerViewModel` (ARCH-006)**: Match project convention of one ViewModel per file.

### Long-term Architecture Evolution

- **Sidecar refactor (v1.1-sidecar)**: The `entrypoint.js` orchestration of daemon + api is functional but fragile on SIGTERM timing. Cloud Run sidecars (GA) offer a cleaner separation once the v1 pipeline is stable.
- **Multi-tenant data paths**: When multi-tenant is needed, `summaries/{videoId}` → `users/{uid}/summaries/{videoId}` requires coordinated changes in `dispatch.ts`, `dispatcher.ts`, `webhook.ts`, `autoEnqueue.ts`, `FirestoreRepository.kt`, and the Firestore rules. All single-tenant path writes should be centralized to a path utility to make this refactor surgical.

---

## Dependency Graph (new modules only)

```
Backend:
index.ts → auth/verify.ts → [defineString, HttpsError, onCall]
index.ts → summarizer/autoEnqueue.ts → models/index.ts
summarizer/dispatch.ts → auth/verify.ts, models/index.ts, summarizer/quota.ts, summarizer/secrets.ts
summarizer/dispatcher.ts → summarizer/dispatch.ts, summarizer/quota.ts, summarizer/secrets.ts
summarizer/webhook.ts → models/index.ts
summarizer/quota.ts → models/index.ts
summarizer/secrets.ts → [defineSecret]
models/index.ts → [firebase-admin/firestore.FieldValue]

No circular dependencies.

Android (new):
screens/videoDetail/summary/SummaryViewModel → data/firestore/SummaryRepository
                                              → data/firestore/SummaryStatus   ← ARCH-002
                                              → functions/SummaryFunctions
screens/playlist/VideoListScreen → data/firestore/QuotaState                   ← ARCH-002
                                 → data/firestore/SummaryRepository
screens/common/QuotaBanner → data/firestore/QuotaState (via toQuotaState)      ← ARCH-002
data/firestore/* → [Firebase SDK only]

No circular dependencies.
```

---

## Metrics

| Metric | Value | Threshold | Status |
|--------|-------|-----------|--------|
| Circular dependencies | 0 | 0 | PASS |
| Avg fan-out (backend summarizer/) | ~3.5 | <10 | PASS |
| God objects | 0 | 0 | PASS |
| Layer violations | 2 (path mismatch + UI types in data) | 0 | FAIL |
| Max file size | ~224 lines (`webhook.ts`) | <1000 | PASS |
| Firestore collection-path mismatches | 1 | 0 | FAIL |

*Review completed: 2026-05-21*

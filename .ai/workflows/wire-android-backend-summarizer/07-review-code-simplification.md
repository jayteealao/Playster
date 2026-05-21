---
schema: sdlc/v1
type: review-command
slug: wire-android-backend-summarizer
review-scope: slug-wide
slice-slug: ""
review-command: code-simplification
status: complete
updated-at: "2026-05-20T00:00:00Z"
metric-findings-total: 10
metric-findings-blocker: 1
metric-findings-high: 2
result: blockers-found
tags: []
refs:
  review-master: 07-review.md
---

# Review: code-simplification

## 0) Scope and Codebase Context

**What was reviewed:**
- Scope: diff
- Target: `git diff main...HEAD`
- Files: 232 files, +16714 added, -1212 removed

**Existing utilities found:**
- `summarizer/summarize-api/src/webhooks/signer.ts` — `buildSignatureHeader`: computes Stripe-style HMAC header
- `summarizer/summarize-api/src/webhooks/deliver.ts` — `deliverWebhook`: HTTP POST with retry and backoff
- `backend/functions/tests/helpers/admin.ts` — `initAdminEmulator`, `clearFirestore`, `emulatorReachable`
- `backend/functions/tests/helpers/signWebhook.ts` — `signWebhook`: test-only HMAC signer (mirrors the API signer)
- `android/.../screens/common/ErrorPanel.kt` — shared error composable
- `android/.../screens/common/InProgressIndicator.kt` — shared progress composable
- `android/.../screens/common/QuotaBanner.kt` — shared quota banner and `rememberQuotaState`
- `android/.../data/firestore/FirestoreRepository.kt` — `callbackFlow`+`addSnapshotListener` pattern

**Patterns observed in codebase:**
- HMAC signing: `${timestamp}.${rawBody}` → `sha256 hex` → header `t=<unix>,v1=<hex>` is implemented in at least three distinct places (signer.ts, signWebhook.ts, mock-backend/server.js), plus implicitly verified in webhook.ts.
- Firestore repository flows: identical `callbackFlow { addSnapshotListener { … }; awaitClose { listener.remove() } }` structure repeated three times inside `FirestoreRepository.kt` and once in `SummaryRepository`.
- Maestro seed scripts: three separate `.js` seed scripts that share 30–40 lines of boilerplate (`admin.initializeApp`, playlist/video seeding, `main().catch`) with only the fixture data differing.
- SummaryScreen `Column` wrapper: repeated `Column(modifier = modifier.fillMaxSize().testTag(…))` pattern in all five `SummaryUiState` branches.

---

## 1) Executive Summary

**Merge Recommendation:** REQUEST_CHANGES

**Rationale:**
One BLOCKER exists: `backend/functions/tests/helpers/signWebhook.ts` is an acknowledged byte-for-byte copy of `summarizer/summarize-api/src/webhooks/signer.ts`. Its own doc-comment says "Kept in sync via the fixture test," and a separate `signWebhook-fixture.test.ts` exists purely to detect drift. This is the canonical form of duplicated logic that will diverge when the signing scheme evolves (e.g., adding a `v2` key version). Two HIGH findings cover the third independent HMAC implementation in `mock-backend/server.js` and the repeated Maestro seed-script boilerplate. The remaining findings are MED/LOW quality and efficiency issues.

**Simplification Opportunity:**
- Reuse findings: 4 (HMAC signer triplication, seed-script boilerplate, `callbackFlow` pattern, test-helper coverage)
- Quality findings: 4 (SummaryScreen Column repetition, hardcoded playlist subcollection path in seed scripts, `FUNCTION_REGION` double-read, TERMINAL_STATUSES duplication)
- Efficiency findings: 2 (sequential `getOnce` + auto-dispatch, `emulatorReachable` existence-check anti-pattern)

---

## 2) Findings Table

| ID | Sev | Conf | Lens | File:Line | Issue |
|----|-----|------|------|-----------|-------|
| CS-1 | BLOCKER | High | Reuse | `backend/functions/tests/helpers/signWebhook.ts:1-27` | Byte-for-byte copy of `summarize-api/src/webhooks/signer.ts`; maintainer doc explicitly says "kept in sync" |
| CS-2 | HIGH | High | Reuse | `summarizer/deploy/mock-backend/server.js:35-52` | Third independent HMAC sign/verify implementation; not reusing the signer module |
| CS-3 | HIGH | High | Reuse | `android/maestro/helpers/write-*.js` (3 files) | ~35 lines of `admin.initializeApp` + playlist/video seed boilerplate copied verbatim across all three scripts |
| CS-4 | MED | High | Quality | `android/.../screens/videoDetail/summary/SummaryScreen.kt:44-109` | All five `when` branches wrap content in an identical `Column(modifier.fillMaxSize().testTag(…))` — the wrapper is not extracted |
| CS-5 | MED | High | Reuse | `android/.../data/firestore/FirestoreRepository.kt:25-70` | Three flows share the same `callbackFlow { addSnapshotListener { … trySend(items) }; awaitClose { listener.remove() } }` shape; extractable to a helper |
| CS-6 | MED | Med | Quality | `backend/functions/src/models/index.ts:37-43` + `backend/functions/src/summarizer/dispatch.ts:18-28` | `SummaryStatus` values and the `TERMINAL_STATUSES` / `IN_FLIGHT_STATUSES` arrays are defined twice (once in `models/index.ts` as string union, once as local `ReadonlyArray` in `dispatch.ts`) |
| CS-7 | MED | High | Quality | `android/maestro/helpers/write-*.js` (3 files) | Seed scripts write to `playlists/{id}/videos/{id}` subcollection but the Firestore listener in `FirestoreRepository.videosFlow` queries the top-level `videos` collection with `whereEqualTo("playlistId", …)`. The fixture path is inconsistent with the production path |
| CS-8 | LOW | High | Quality | `backend/functions/src/summarizer/dispatch.ts:17` + `webhookUrl():44` | `FUNCTION_REGION` is read from `process.env.FUNCTION_REGION` twice — once at module level into a constant, once again inside `webhookUrl()` — making the constant dead |
| CS-9 | LOW | High | Efficiency | `android/.../screens/videoDetail/VideoListScreen.kt:80-93` + `SummaryViewModel.kt:57-65` | `onSummarizeClick` calls `summaryRepository.getOnce` (a Firestore network round-trip) and then the `SummaryViewModel.init` block may call `getOnce` again when `autoDispatch=true`; two reads for the same doc on the happy path |
| CS-10 | LOW | Med | Efficiency | `backend/functions/tests/helpers/admin.ts:41-50` | `emulatorReachable` does a `GET` existence-check before tests proceed; this is a TOCTOU anti-pattern — test setup should just attempt the operation and fail fast |

**Findings Summary:**
- BLOCKER: 1
- HIGH: 2
- MED: 3
- LOW: 3
- NIT: 1 (absorbed into CS-8)

---

## 3) Findings (Detailed)

### CS-1: `signWebhook` test helper is a verbatim copy of `signer.ts` [BLOCKER]

**Location:** `backend/functions/tests/helpers/signWebhook.ts:1-27`
**Lens:** Reuse

**Evidence:**
```typescript
// backend/functions/tests/helpers/signWebhook.ts
/**
 * Byte-for-byte replica of the summarize-api signer at
 * `summarizer/summarize-api/src/webhooks/signer.ts`. Kept in sync via the
 * fixture test in `tests/signWebhook-fixture.test.ts`.
 */
export function signWebhook(
  payload: unknown, secret: string, timestamp: number = …
): SignedWebhook {
  const rawBody = JSON.stringify(payload);
  const canonical = `${timestamp}.${rawBody}`;
  const mac = createHmac("sha256", secret).update(canonical, "utf8").digest("hex");
  return { rawBody, header: `t=${timestamp},v1=${mac}`, timestamp };
}
```

```typescript
// summarizer/summarize-api/src/webhooks/signer.ts
export function buildSignatureHeader(
  secret: string, rawBody: string, timestamp: number = …
): SignatureHeader {
  const canonical = `${timestamp}.${rawBody}`;
  const mac = createHmac("sha256", secret).update(canonical, "utf8").digest("hex");
  return { header: `t=${timestamp},v1=${mac}`, timestamp };
}
```

The logic is byte-identical; only the parameter order and return shape differ. A `signWebhook-fixture.test.ts` file exists expressly to catch drift, confirming this is a known, ongoing maintenance burden.

**Simpler alternative:**
```typescript
// backend/functions/tests/helpers/signWebhook.ts
import { buildSignatureHeader } from
  "../../../summarizer/summarize-api/src/webhooks/signer.js";

export function signWebhook(payload, secret, timestamp = Math.floor(Date.now()/1000)) {
  const rawBody = JSON.stringify(payload);
  const { header } = buildSignatureHeader(secret, rawBody, timestamp);
  return { rawBody, header, timestamp };
}
```
Then delete `signWebhook-fixture.test.ts` — it becomes tautological once both sides share one implementation.

**Severity:** BLOCKER | **Confidence:** High
**Why it matters:** The doc-comment itself says "kept in sync." When the signing scheme gains a `v2` variant or the canonical format changes, both copies must change. A fixture test cannot prevent a transient window where they diverge; it only detects the divergence after the fact — after a production misverify.

---

### CS-2: `mock-backend/server.js` implements HMAC verify independently [HIGH]

**Location:** `summarizer/deploy/mock-backend/server.js:35-52`
**Lens:** Reuse

**Evidence:**
```javascript
function parseSignature(header) { … }

function verify(rawBody, sig) {
  const canonical = `${sig.t}.${rawBody}`;
  const expectedHex = createHmac("sha256", SECRET)
    .update(canonical, "utf8").digest("hex");
  if (sig.v1.length !== expectedHex.length) return false;
  return timingSafeEqual(Buffer.from(sig.v1, "utf8"), Buffer.from(expectedHex, "utf8"));
}
```

This is the third independent reimplementation of the same HMAC scheme, separate from `signer.ts` and `webhook.ts`. It also has a subtle difference: it compares the hex strings as UTF-8 buffers (`Buffer.from(sig.v1, "utf8")`) while `webhook.ts` compares them as hex-decoded buffers (`Buffer.from(v1, "hex")`). The hex-vs-UTF-8 difference is harmless only because hex strings are ASCII-clean, but it is a latent inconsistency.

The `mock-backend` runs as a plain Node.js script, not inside the TypeScript monorepo, so it cannot `import` the `signer.ts` directly. However, a single small CommonJS/ESM adapter or a copied (not duplicated-logic) build artifact would close this gap.

**Simpler alternative:**
Extract a minimal standalone `verify.js` using the same logic as `signer.ts`, ship it as a shared artifact in `summarizer/deploy/`, and `require` it from `mock-backend/server.js`. Or compile `signer.ts` to a CJS dist and bundle it into the mock-backend Docker image.

**Severity:** HIGH | **Confidence:** High
**Why it matters:** If the signing scheme evolves (key rotation, `v2` support, replay window change), the mock-backend will silently use the old verification logic during harness runs, masking real regressions.

---

### CS-3: Maestro seed scripts share ~35 lines of identical boilerplate [HIGH]

**Location:** `android/maestro/helpers/write-cached-summary.js`, `write-fresh-state.js`, `write-quota-cap.js`
**Lens:** Reuse

**Evidence (shared boilerplate in all three):**
```javascript
const admin = require("firebase-admin");
if (!process.env.FIRESTORE_EMULATOR_HOST) {
  process.env.FIRESTORE_EMULATOR_HOST = "127.0.0.1:8080";
}
admin.initializeApp({ projectId: "playster-dev" });
const db = admin.firestore();

// Then each seeds the same playlist/video structure:
await db.collection("playlists").doc("PL_TEST_1").set({
  id: "PL_TEST_1", playlistId: "PL_TEST_1", title: "Test Playlist",
  channelTitle: "Test Channel", videoCount: 1, thumbnailUrl: "",
});
await db.collection("playlists").doc("PL_TEST_1")
  .collection("videos").doc("VID_*").set({ … same 10 fields … });
```

Each file then adds its distinctive fixture on top (`summaries/VID_CACHED` doc, or just the playlist/video, or the `quota/openrouter` doc). The admin init and the playlist/video write are identical in all three.

**Simpler alternative:**
```javascript
// android/maestro/helpers/seed-utils.js
const admin = require("firebase-admin");
if (!process.env.FIRESTORE_EMULATOR_HOST)
  process.env.FIRESTORE_EMULATOR_HOST = "127.0.0.1:8080";
admin.initializeApp({ projectId: "playster-dev" });
exports.db = admin.firestore();

async function seedBaseFixture(videoId, title) { … }
exports.seedBaseFixture = seedBaseFixture;
```
Each script then `require("./seed-utils")` and calls `seedBaseFixture`.

**Severity:** HIGH | **Confidence:** High
**Why it matters:** The playlist/video document shape appears three times. Adding a new required field (e.g., `playlistId` on the playlist doc) requires editing all three scripts. One was already missed: `write-cached-summary.js` writes the video to the `playlists/PL_TEST_1/videos/` **subcollection**, but `FirestoreRepository.videosFlow` listens on the top-level `videos` collection (see CS-7). A shared helper would make that bug immediately visible.

---

### CS-4: `SummaryScreen` branches repeat `Column(modifier.fillMaxSize().testTag(…))` wrapper [MED]

**Location:** `android/.../screens/videoDetail/summary/SummaryScreen.kt:44-109`
**Lens:** Quality

**Evidence:**
```kotlin
is SummaryUiState.InProgress -> Column(
    modifier = modifier.fillMaxSize().testTag("summary-in-progress"),
) { InProgressIndicator(…) }

is SummaryUiState.Completed -> Column(
    modifier = modifier.fillMaxSize().testTag("summary-completed")
        .verticalScroll(…).padding(16.dp),
) { … }

is SummaryUiState.FailedTransient -> Column(
    modifier = modifier.fillMaxSize().testTag("summary-failed-transient"),
) { ErrorPanel(…) }

is SummaryUiState.FailedPermanent -> Column(
    modifier = modifier.fillMaxSize().testTag("summary-failed-permanent"),
) { ErrorPanel(…) }

is SummaryUiState.NoSummary -> Column(
    modifier = modifier.fillMaxSize().testTag("summary-no-summary").padding(32.dp),
    …
) { … }
```

Every branch creates a `Column` with `modifier.fillMaxSize()` and a state-specific `testTag`. The `testTag` variation is the only meaningful difference in the wrapper; the content differs.

**Simpler alternative:**
```kotlin
Box(modifier = modifier.fillMaxSize().testTag(state.testTag)) {
    when (state) {
        is SummaryUiState.InProgress -> InProgressIndicator(…)
        is SummaryUiState.Completed -> Column(Modifier.verticalScroll(…)) { … }
        // etc.
    }
}
// with a val SummaryUiState.testTag computed property
```

**Severity:** MED | **Confidence:** High
**Why it matters:** Adding a new state (e.g., `Queued` displayed differently from `InProgress`) requires remembering to add the wrapper. The common wrapper is the screen contract, not each branch's responsibility.

---

### CS-5: `callbackFlow`+`addSnapshotListener`+`awaitClose` pattern repeated three times [MED]

**Location:** `android/.../data/firestore/FirestoreRepository.kt:25-69`
**Lens:** Reuse

**Evidence:**
```kotlin
fun playlistsFlow(): Flow<List<PlaylistDoc>> = callbackFlow {
    val listener = firestore.collection("playlists")
        .addSnapshotListener { snapshot, error ->
            if (error != null) { Log.w(TAG, …); return@addSnapshotListener }
            val items = snapshot?.documents?.mapNotNull { it.toObject(…) } ?: emptyList()
            trySend(items)
        }
    awaitClose { listener.remove() }
}

fun videosFlow(playlistId: String): Flow<List<VideoDoc>> = callbackFlow {
    val listener = firestore.collection("videos")
        .whereEqualTo("playlistId", playlistId)
        .addSnapshotListener { snapshot, error ->
            if (error != null) { Log.w(TAG, …); return@addSnapshotListener }
            val items = snapshot?.documents?.mapNotNull { it.toObject(…) } ?: emptyList()
            trySend(items)
        }
    awaitClose { listener.remove() }
}
// videoFlow repeats same shape again
```

**Simpler alternative:**
```kotlin
private inline fun <reified T : Any> Query.asFlow(tag: String): Flow<List<T>> = callbackFlow {
    val listener = addSnapshotListener { snapshot, error ->
        if (error != null) { Log.w(tag, "listen error", error); return@addSnapshotListener }
        trySend(snapshot?.documents?.mapNotNull { it.toObject(T::class.java) } ?: emptyList())
    }
    awaitClose { listener.remove() }
}
```
The pattern in `SummaryRepository.observe()` and `QuotaRepository.observe()` is slightly different (single-doc rather than collection) but a parallel helper can cover that too.

**Severity:** MED | **Confidence:** High
**Why it matters:** Error logging and the `awaitClose` teardown must be replicated correctly every time. A future change to listener semantics (e.g., adding `MetadataChanges.INCLUDE`) would need to be applied in 3+ places.

---

### CS-6: `SummaryStatus` values defined in `models/index.ts` and re-enumerated in `dispatch.ts` [MED]

**Location:** `backend/functions/src/models/index.ts:37-43` + `backend/functions/src/summarizer/dispatch.ts:18-28`
**Lens:** Quality

**Evidence:**
```typescript
// models/index.ts
export type SummaryStatus =
  | "queued" | "pending" | "running"
  | "completed" | "failed-transient" | "failed-permanent";

// dispatch.ts
const TERMINAL_STATUSES: ReadonlyArray<SummaryDocument["status"]> = [
  "completed", "failed-transient", "failed-permanent",
];
const IN_FLIGHT_STATUSES: ReadonlyArray<SummaryDocument["status"]> = [
  "queued", "pending", "running", "completed",
];
```

`TERMINAL_STATUSES` is used in dispatch's idempotency check but never used elsewhere in the module (only `IN_FLIGHT_STATUSES` is). Both arrays re-enumerate status strings that are already in the type. There is no enforcement that the arrays stay in sync with the type.

Note: `webhook.ts` defines its own `TERMINAL_PERMANENT_CODES` (separate concept — error codes vs statuses) which is appropriate and not duplicated.

**Simpler alternative:**
Move `TERMINAL_STATUSES` and `IN_FLIGHT_STATUSES` to `models/index.ts` as exported constants. Remove `TERMINAL_STATUSES` from `dispatch.ts` if it is truly unused (the `__dispatchInternals` export includes it, but it is not tested or consumed anywhere — dead export).

**Severity:** MED | **Confidence:** Med
**Why it matters:** Adding a new terminal status (e.g., `"cancelled"`) requires updating the type and both arrays across files.

---

### CS-7: Maestro seed scripts write to Firestore subcollection; repository listens to top-level collection [MED → per CS-3 combined]

**Location:** `android/maestro/helpers/write-cached-summary.js:27-37`, `write-fresh-state.js:20-37`, `write-quota-cap.js:22-37`
vs. `android/.../data/firestore/FirestoreRepository.kt:40-54`
**Lens:** Quality

**Evidence:**
```javascript
// seed scripts — write to subcollection:
await db.collection("playlists").doc("PL_TEST_1")
    .collection("videos").doc("VID_CACHED").set({ … });
```

```kotlin
// FirestoreRepository.videosFlow — listens to top-level collection:
firestore.collection("videos")
    .whereEqualTo("playlistId", playlistId)
    .addSnapshotListener { … }
```

The seed data is invisible to the production listener path. The Maestro flows that rely on these seeds for video display would fail if they actually tried to render the video list via the real repository.

**Simpler alternative:**
Change the seed scripts to write to the top-level `videos/{videoId}` collection with `playlistId` field, matching the actual Firestore schema used by the backend sync (`backend/functions/src/youtube/api-sync.ts`) and the Android listener.

**Severity:** MED | **Confidence:** High
**Why it matters:** This is a correctness issue masquerading as a simplification issue — the Maestro tests exercise the wrong Firestore path, so passing tests do not actually validate the real query used by the app.

*(Note: this finding is also reportable under correctness review. It is included here because the root cause — copy-pasted boilerplate with a subtle variation — is a code-simplification failure.)*

---

### CS-8: `FUNCTION_REGION` constant is dead — `webhookUrl()` reads `process.env` again [LOW]

**Location:** `backend/functions/src/summarizer/dispatch.ts:17` + `:43-48`
**Lens:** Quality

**Evidence:**
```typescript
// Line 17
const FUNCTION_REGION = process.env.FUNCTION_REGION ?? "us-central1";

// Lines 43-48
function webhookUrl(): string {
  const region = process.env.FUNCTION_REGION ?? FUNCTION_REGION; // reads env first, then falls back to the constant that already read env
  …
}
```

`process.env.FUNCTION_REGION ?? FUNCTION_REGION` always resolves to `FUNCTION_REGION` because `FUNCTION_REGION` was computed from the same expression. The module-level constant is never used as the sole source; the env var is re-read every call.

**Simpler alternative:**
```typescript
// Remove the module-level constant; use it inline:
function webhookUrl(): string {
  const region = process.env.FUNCTION_REGION ?? "us-central1";
  …
}
```
Or keep the constant and use it alone: `const region = FUNCTION_REGION;`

**Severity:** LOW | **Confidence:** High
**Why it matters:** Minor — creates false impression that the constant is authoritative. No behavioral difference.

---

### CS-9: `VideoListScreen.onSummarizeClick` + `SummaryViewModel.init` may double-read `summaries/{videoId}` [LOW]

**Location:** `android/.../screens/playlist/VideoListScreen.kt:80-93` + `android/.../screens/videoDetail/summary/SummaryViewModel.kt:57-65`
**Lens:** Efficiency

**Evidence:**
```kotlin
// VideoListViewModel.onSummarizeClick — first read:
fun onSummarizeClick(videoId: String, onNavigate: (String, Boolean) -> Unit) {
    viewModelScope.launch {
        val existing = summaryRepository.getOnce(videoId)  // network round-trip
        val autoDispatch = …
        onNavigate(videoId, autoDispatch)
    }
}

// SummaryViewModel.init — second read when autoDispatch=true:
if (autoDispatch) {
    viewModelScope.launch {
        val existing = summaryRepository.getOnce(videoId)  // same doc, again
        if (existing == null && autoDispatched.compareAndSet(false, true)) dispatch()
    }
}
```

On the common flow where the user taps Summarize for a video with no existing doc, two separate Firestore `get()` calls are made to `summaries/{videoId}` in quick succession. The first determines whether to pass `autoDispatch=true`; the second confirms the doc is still absent before dispatching.

**Simpler alternative:**
The `SummaryViewModel` guard (`autoDispatched` + `getOnce`) is the important safety check. The `VideoListViewModel` read could be removed: always navigate with `autoDispatch=false` and let `SummaryViewModel` decide. The user experience is the same (SummaryScreen shows `NoSummary` briefly, then triggers dispatch). This removes one round-trip on every tap.

**Severity:** LOW | **Confidence:** High
**Why it matters:** In a warm-cache Firestore SDK, the second read will likely hit local cache, so the latency cost is small. However, the two-read pattern was introduced precisely to set `autoDispatch` correctly, but the `SummaryViewModel` re-checks the same condition anyway, making the first read redundant.

---

### CS-10: `emulatorReachable` uses an existence-check before tests try to connect [LOW]

**Location:** `backend/functions/tests/helpers/admin.ts:41-50`
**Lens:** Efficiency

**Evidence:**
```typescript
export async function emulatorReachable(): Promise<boolean> {
  try {
    const res = await fetch(
      `http://${EMULATOR_HOST}/emulator/v1/projects/${PROJECT_ID}/databases/(default)/documents`,
      { method: "GET" },
    );
    return res.ok || res.status === 404;
  } catch {
    return false;
  }
}
```

Tests call this before deciding whether to skip. This is a `TOCTOU` pattern: the emulator could become unreachable between the check and the first actual operation. It also adds latency to every test suite startup, and tests that skip gracefully via this check will not fail CI when the emulator is simply misconfigured.

**Simpler alternative:**
Remove `emulatorReachable`. In CI, the emulator is always present. In local runs, let the first `clearFirestore()` fail and produce a clear error rather than silently skipping. If skip-on-missing-emulator is desired, a single `beforeAll` that calls `clearFirestore()` and catches `ECONNREFUSED` is cleaner — the error is at the point of first use.

**Severity:** LOW | **Confidence:** Med
**Why it matters:** Minor operational issue. Tests that skip silently when the emulator is down provide false green CI.

---

## 4) Triage Decisions

| ID | Sev | User Decision | Notes |
|----|-----|---------------|-------|
| CS-1 | BLOCKER | untriaged | — |
| CS-2 | HIGH | untriaged | — |
| CS-3 | HIGH | untriaged | — |
| CS-4 | MED | untriaged | — |
| CS-5 | MED | untriaged | — |
| CS-6 | MED | untriaged | — |
| CS-7 | MED | untriaged | Overlaps correctness domain |
| CS-8 | LOW | untriaged | — |
| CS-9 | LOW | untriaged | — |
| CS-10 | LOW | untriaged | — |

---

## 5) Recommendations

### Must Fix
- **CS-1 (BLOCKER):** Replace `backend/functions/tests/helpers/signWebhook.ts` with a thin wrapper over `summarize-api/src/webhooks/signer.ts`. Delete `signWebhook-fixture.test.ts`.
- **CS-7 (MED):** Fix all three Maestro seed scripts to write to the top-level `videos/{videoId}` collection, not the subcollection.

### Consider Fixing
- **CS-2:** Extract a shared `verify.js` utility for `mock-backend/server.js` rather than reimplementing HMAC verification inline.
- **CS-3:** Create a `seed-utils.js` with shared admin-init + base-fixture helpers for the three Maestro seed scripts.

### Defer (Tech Debt)
- CS-4, CS-5, CS-6, CS-8, CS-9, CS-10 — all work correctly; lower priority than the merge blockers.

---

## 6) False Positives & Context I May Have Missed

1. **CS-1:** The `signWebhook` helper is in the `backend/functions/` workspace, which may not have a TypeScript path alias pointing at `summarizer/summarize-api/`. A `tsconfig` `paths` entry or a workspace `file:` dependency would be needed before the import can be made cross-package. The maintenance cost of the current duplication is explicitly accepted and guarded by the fixture test — downgrade to HIGH if the cross-package import is not feasible.

2. **CS-2:** `mock-backend/server.js` runs inside Docker as a plain Node.js process. Importing the TypeScript-compiled `signer.ts` would require either bundling the compiled output into the Docker image or shipping a pre-built `.cjs` artifact. If the build pipeline does not already do this, the duplication may be the pragmatic choice and this finding should be LOW.

3. **CS-7:** If the backend sync (`api-sync.ts`) actually writes to `playlists/{id}/videos/{id}` subcollections (I did not read that file exhaustively), the Maestro paths would be correct and the `FirestoreRepository.videosFlow` top-level query would be the bug. Verify the actual write path in `backend/functions/src/youtube/api-sync.ts` before acting on CS-7.

4. **CS-5:** The Kotlin `callbackFlow` abstraction is straightforward but generic `inline reified` helpers in Kotlin can interact poorly with Hilt injection in some compile-time scenarios. The repeated pattern is also idiomatic for Firebase Kotlin usage — confirm the refactor doesn't hit AGP/Hilt bytecode quirks before merging.

---

*Review completed: 2026-05-20*

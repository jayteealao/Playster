---
review-command: performance
slug: wire-android-backend-summarizer
scope: diff
target: git diff main...HEAD
completed: 2026-05-20
verdict: Ship with caveats
---

# Performance Review — wire-android-backend-summarizer

**Scope:** Full branch diff (`git diff main...HEAD`), 232 files changed, +16 714 / −1 212 lines  
**Reviewer:** Claude Performance Review Agent  
**Date:** 2026-05-20

## Summary

The branch is well-structured for a single-operator, single-tenant system.
Hot paths are guarded by quotas, Firestore batching is used correctly, and the
dispatcher drain loop is bounded by budget math.  No O(n²) algorithms exist on
user-facing paths.  Six findings were identified (zero BLOCKER), primarily
around a misrouted Firestore query, a N+1 read in the VideoListScreen
onSummarizeClick handler, an unbounded `recentTimestamps` array in the quota
doc, and per-frame quota-state recomputation in Compose.

**Severity Breakdown:**
- BLOCKER: 0
- HIGH: 2 (wrong collection path causing collection-group scan; N+1 Firestore read per video row tap)
- MED: 2 (unbounded sliding-window array growth; serial Firestore reads inside dispatcher drain)
- LOW: 1 (toQuotaState() called every recomposition without memoization)
- NIT: 1 (entrypoint health-poll is a tight busy-loop rather than exponential backoff)

**Performance Health:**
- Algorithm complexity: PASS
- Database efficiency: FAIL (two issues)
- Memory management: FAIL (one issue — bounded in practice but structurally unbounded)
- I/O operations: PASS (good batching, parallel-safe quota transaction)
- Caching strategy: PASS
- Compose rendering: WARN

---

## Findings

### PERF-01 — `videosFlow` queries the wrong top-level `videos` collection [HIGH]

**Location:** `android/app/src/main/java/com/github/jayteealao/playster/data/firestore/FirestoreRepository.kt:41-54`  
**Category:** Missing index / wrong query path  
**Hot Path:** Yes — PlaylistScreen → VideoListScreen transition, rendered every time a user opens a playlist.

**Issue:**
`videosFlow()` queries `firestore.collection("videos").whereEqualTo("playlistId", playlistId)`.
There is no top-level `videos` collection in this schema.  Videos are stored at
`playlists/{playlistId}/videos/{videoId}` (a subcollection).  The backend
`api-sync.ts` and `innertube-sync.ts` both write to
`playlistRef.collection("videos").doc(video.id)`.
This means `videosFlow` will always return an empty list — the listener watches
a collection that is never written to.  In the current codebase the Firestore
rules also have `match /videos/{document=**}` which matches a top-level
`/videos` path, not the subcollection path.

**Evidence:**
```kotlin
// FirestoreRepository.kt:41
val listener: ListenerRegistration = firestore.collection("videos")
    .whereEqualTo("playlistId", playlistId)   // top-level /videos — never written
    .addSnapshotListener { ... }
```
```typescript
// api-sync.ts:142
const videoRef = playlistRef.collection("videos").doc(videoId); // /playlists/{id}/videos/{vid}
```

The `VideoDoc` Kotlin model has no `playlistId` field either, so even if the
path were corrected the filter would match nothing.

**Performance Impact:**
- Cold start: listener opens and fires an empty snapshot immediately — no latency hit.
- But operator sees no videos ever: feature is completely broken.  If "fixed" by
  switching to the subcollection path there is no query-level filter needed
  (subcollection is already scoped), so no index risk.

**Fix:**
```kotlin
// Use subcollection path; remove the whereEqualTo filter
val listener: ListenerRegistration = firestore
    .collection("playlists").document(playlistId)
    .collection("videos")
    .addSnapshotListener { snapshot, error -> ... }
```
If ordering by `position` is desired, add `.orderBy("position")` — Firestore
will create the index automatically for a simple single-field order on a
subcollection.

---

### PERF-02 — N+1 Firestore read per Summarize tap in VideoListScreen [HIGH]

**Location:** `android/app/src/main/java/com/github/jayteealao/playster/screens/playlist/VideoListScreen.kt:80-93`  
**Category:** N+1 Queries  
**Hot Path:** Yes — triggered every time the user taps the ✨ button on any video row.

**Issue:**
`onSummarizeClick` calls `summaryRepository.getOnce(videoId)` — a
point-read to `summaries/{videoId}` — before navigating.  For a list of N
videos, if the user taps rapidly (or if future code pre-fetches), this
generates N independent Firestore reads.  More critically, the status
information this read is trying to obtain is **already available from the
Firestore listener** if `summariesFlow` were colocated with `videosFlow` in
the ViewModel.  The current design adds a 100–300 ms round-trip to each
Summarize tap.

**Evidence:**
```kotlin
// VideoListScreen.kt:81-92
fun onSummarizeClick(videoId: String, onNavigate: (String, Boolean) -> Unit) {
    viewModelScope.launch {
        val existing = summaryRepository.getOnce(videoId)   // N+1 read per tap
        val autoDispatch = when (existing?.status) { ... }
        onNavigate(videoId, autoDispatch)
    }
}
```

**Performance Impact:**
- Per tap: +1 Firestore read latency (~100–300 ms on 4G), blocking navigation.
- AC-5 requires in-progress UI within 500 ms; this read consumes 20–60% of that budget before navigation even starts.

**Fix — Option A (preferred):** Add a `summariesMapFlow` listener to
`VideoListViewModel` that listens to each `summaries/{videoId}` doc as video
docs are loaded, building a `Map<String, SummaryStatus>`.  Derive `autoDispatch`
from the in-memory map synchronously:
```kotlin
// After videos list is populated, listen to summary docs in batch
// (Firestore doesn't support `in` filters on document IDs in collection
// listeners, but a map of individual snapshot listeners keyed by videoId
// tear down on ViewModel clear works for small lists < 30 items)
fun onSummarizeClick(videoId: String, onNavigate: (String, Boolean) -> Unit) {
    val status = _summaryStatusMap.value[videoId]
    val autoDispatch = status == null || status == SummaryStatus.FAILED_TRANSIENT ...
    onNavigate(videoId, autoDispatch)
}
```
**Fix — Option B (simpler):** Navigate immediately with `autoDispatch=true`;
let `SummaryViewModel.init` do the `getOnce` check before dispatching (it
already does this).  Drop the pre-navigation read entirely from
`VideoListViewModel`.

---

### PERF-03 — `recentTimestamps` array is unbounded in the quota document [MED]

**Location:** `backend/functions/src/summarizer/quota.ts:22-23, 83-85`  
**Category:** Memory / Firestore document size  
**Hot Path:** Yes — read + written on every summary dispatch.

**Issue:**
`recentTimestamps` is a `number[]` stored in the `quota/openrouter` Firestore
document.  `trimWindow` filters to the last 60 seconds, but it runs **in
memory after the array is fetched** — the array is then re-written to
Firestore with the trimmed value.  Between dispatches the array can hold up to
`perMinuteLimit` (20) entries after one minute.  This is fine in steady state.

However, if the trim logic ever fails to run before a write (e.g., a code
path sets the doc directly without going through `readQuota`), or if the
`perMinuteLimit` is raised, the array can grow past the Firestore 1 MB
document limit.  More practically: each transaction writes the entire array
back, so cost scales with `recentTimestamps.length` on every hot-path call.

**Evidence:**
```typescript
// quota.ts:83-85
const next: QuotaDocument = {
  ...
  recentTimestamps: [...current.recentTimestamps, now],  // grows by 1 each dispatch
```

**Performance Impact:**
- At current 20/min cap: negligible (≤20 entries, ~160 bytes).
- If cap is raised to 100+: document payload per-transaction grows proportionally.
- Risk is low in production at current limits, but the design lacks a hard cap on array length.

**Fix:** Add a hard length cap in `readQuota` as a second safety:
```typescript
function readQuota(data, now): QuotaSnapshot {
  ...
  const trimmed = trimWindow(data?.recentTimestamps ?? [], now);
  // Guard against pathological growth beyond the configured cap
  const recentTimestamps = trimmed.slice(-Math.max(perMinuteLimit, 100));
  ...
}
```

---

### PERF-04 — Dispatcher drain dispatches summaries serially (one await per doc) [MED]

**Location:** `backend/functions/src/summarizer/dispatcher.ts:70-89`  
**Category:** Serial I/O  
**Hot Path:** Yes — runs every 5 minutes on the scheduled dispatcher.

**Issue:**
`drainSummaryQueue` iterates `queued.docs` with `for...of` and `await
dispatchSummary(...)` inside the loop — fully serial.  Each `dispatchSummary`
call makes:
1. `videoExists` — a collection-group query
2. `summaryRef.get()` — a point read
3. `reserveOpenRouterQuotaSlot()` — a Firestore transaction
4. A second `summaryRef` transaction
5. An outbound HTTP call to the summarizer

With the per-minute cap of 20 items and a slow summarizer endpoint (e.g., 1 s
each), this chain takes up to 20 s just in latency before the first job is
even dispatched.  Parallelising the dispatch within the quota window would
drain faster.

**Evidence:**
```typescript
// dispatcher.ts:70-89
for (const doc of queued.docs) {
  attempted += 1;
  ...
  await dispatchSummary(videoId, model);  // serial — blocks on each
  dispatched += 1;
  ...
}
```

**Performance Impact:**
- 20 serial dispatches × ~500 ms each = up to 10 s drain time per cron tick.
- Target: drain 20 items comfortably within the 5-minute cron window — currently met, but the serial path is unnecessarily slow.

**Fix:** Use `Promise.allSettled` with the batch capped at `remaining`:
```typescript
const results = await Promise.allSettled(
  queued.docs.map(async (doc) => {
    const videoId = (doc.data() as Partial<SummaryDocument>)?.videoId ?? doc.id;
    const model = (doc.data() as Partial<SummaryDocument>)?.model ?? "free";
    await dispatchSummary(videoId, model);
  })
);
// count dispatched from fulfilled results
```
Note: `reserveOpenRouterQuotaSlot` is already transactional, so parallel calls
are safe — each one either succeeds or throws `resource-exhausted`.

---

### PERF-05 — `toQuotaState()` recomputes on every recomposition [LOW]

**Location:** `android/app/src/main/java/com/github/jayteealao/playster/screens/common/QuotaBanner.kt:53-54`  
**Category:** Excess recomposition  
**Hot Path:** Low — quota doc changes at most once per minute.

**Issue:**
`QuotaBanner` calls `quotaDoc.toQuotaState()` inline in the Composable body
with no `remember` wrapping.  Since `toQuotaState()` is a Kotlin extension
function (not shown, but inferred from imports), it executes on every
recomposition even when `quotaDoc` has not changed.  Similarly, `rememberQuotaState()`
in `VideoListScreen` triggers on every recomposition of the parent.

**Evidence:**
```kotlin
// QuotaBanner.kt:53-54
val quotaDoc by viewModel.quotaDoc.collectAsStateWithLifecycle()
val state = quotaDoc.toQuotaState()   // no remember — runs on every recomposition
```

**Performance Impact:**
- Negligible for a single-operator app with low UI churn.
- `toQuotaState()` involves timestamp comparison (`Date.now()`), so the result is
  technically volatile but Firestore listener only fires when the doc changes.

**Fix:**
```kotlin
val state = remember(quotaDoc) { quotaDoc.toQuotaState() }
```

---

### PERF-06 — Entrypoint health-poll is a tight 200 ms busy-loop with no backoff [NIT]

**Location:** `summarizer/deploy/entrypoint.js:64-78`  
**Category:** Blocking / busy-wait  
**Hot Path:** Cold-start only.

**Issue:**
`waitForDaemonHealth` polls every 200 ms with no exponential backoff.  On
Cloud Run cold start the daemon typically takes 2–3 s to be ready; this
generates ~10–15 fetch calls before succeeding.  Not harmful at this scale but
wastes event-loop cycles during startup.

**Evidence:**
```js
// entrypoint.js:75
await sleep(DAEMON_HEALTH_POLL_MS);  // fixed 200ms — no backoff
```

**Fix (optional):** Simple capped backoff:
```js
let pollMs = 100;
while (...) {
  await sleep(pollMs);
  pollMs = Math.min(pollMs * 1.5, 2000);
}
```

---

## Recommendations

### Immediate Actions (HIGH — fix before shipping)

1. **PERF-01**: Fix `videosFlow` to query the subcollection path
   `playlists/{playlistId}/videos` — current query returns zero results, making
   the entire VideoListScreen empty.

2. **PERF-02**: Remove the blocking `getOnce` from `onSummarizeClick`; either
   navigate immediately and rely on `SummaryViewModel.init`'s existing check,
   or build a colocated summary-status map listener in the ViewModel.

### Performance Improvements (MED)

3. **PERF-03**: Cap `recentTimestamps.length` in `readQuota` as a secondary guard.

4. **PERF-04**: Parallelise dispatcher drain with `Promise.allSettled`; the quota
   transaction is already concurrency-safe.

### Minor Improvements (LOW/NIT)

5. **PERF-05**: Wrap `toQuotaState()` in `remember(quotaDoc)` in `QuotaBanner`
   and `rememberQuotaState`.

6. **PERF-06**: Add simple exponential backoff to the entrypoint health poll.

---

## Performance Impact Summary

| ID | Finding | Current | After Fix | Improvement |
|----|---------|---------|-----------|-------------|
| PERF-01 | videosFlow wrong collection | Videos always empty | Videos load correctly | Feature-correctness |
| PERF-02 | N+1 read on summarize tap | +100–300ms per tap | ~0ms (sync map lookup) | Navigation latency |
| PERF-03 | Unbounded timestamps array | Up to 160 bytes now | Bounded at cap | Memory safety |
| PERF-04 | Serial dispatcher drain | ~10s for 20 items | ~500ms (parallel) | 20x faster drain |
| PERF-05 | toQuotaState every recompose | Extra computation each frame | Computed once per doc change | Negligible |
| PERF-06 | 200ms health poll tight loop | 10–15 wasted fetches | 3–4 polls on cold start | Negligible |

*Review completed: 2026-05-20*

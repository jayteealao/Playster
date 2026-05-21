---
review-command: backend-concurrency
slug: wire-android-backend-summarizer
slice: (slug-wide)
date: 2026-05-20
scope: diff
target: git diff main...HEAD
reviewer: Claude Code (sonnet-4-6)
verdict: Ship with caveats
---

# Backend Concurrency Review — wire-android-backend-summarizer

**Reviewed:** full branch diff (`git diff main...HEAD`)
**Date:** 2026-05-20
**Reviewer:** Claude Code

---

## 0) Scope & Concurrency Model

**What was reviewed:**
- Scope: diff (full branch vs main)
- Files changed: ~120 files; concurrency-focused surfaces: `quota.ts`, `dispatcher.ts`, `autoEnqueue.ts`, `webhook.ts`, `dispatch.ts`, `summarize-api/src/db/jobs.ts`, `SummaryViewModel.kt`, `PlaylistViewModel.kt`, `FirestoreRepository.kt`

**Concurrency model:**
- Runtime: Node.js event loop (Cloud Functions v2, Cloud Run Fastify)
- Database (backend): Firestore with Admin SDK; `locks/summaryDispatcher` + `quota/openrouter` are the shared-mutable docs
- Database (summarize-api): SQLite with `better-sqlite3` (synchronous, single-process) + WAL mode
- Android: Kotlin coroutines + StateFlow, Firestore SDK snapshot listeners on `viewModelScope`
- Expected load: single operator; concurrency surface is cron overlap + manual-vs-auto dispatch racing on the same `summaries/{videoId}` doc

**Critical operations:**
- `reserveOpenRouterQuotaSlot` — daily+per-minute gate before every dispatch
- `acquireDispatcherLock` / `drainSummaryQueue` — 5-min cron dedup
- `enqueueAutoSummary` — batch getAll → batch.set for new summary docs
- `dispatchSummary` — idempotency gate + quota pre-increment + doc reservation
- `processSummaryWebhook` — idempotency on completed/failed terminal states
- SQLite job create/update — synchronous single-process, WAL mode

---

## 1) Executive Summary

**Concurrency Safety: MOSTLY_SAFE**

The core quota path (`quota.ts`) is correctly wrapped in a single Firestore transaction that performs check + increment atomically — this is the highest-risk surface and it is done right. The dispatcher lock is also correctly transactional. The idempotency gate in `dispatchSummary` and `processSummaryWebhook` protects the two most likely replay paths. SQLite's synchronous `better-sqlite3` driver combined with WAL mode means the summarize-api has no async concurrency issues.

Two medium-severity issues remain:
1. `enqueueAutoSummary` has a check-then-act window between `getAll` and `batch.set` that allows duplicate parallel syncs to both write `queued` docs for the same `videoId` (harmless in isolation but the second write resets `requestedAt` and overwrites the `webhookSecret` if any was set — in the current schema both fields are set to known values so actual damage is nil, but the race exists).
2. `processSummaryWebhook` reads the doc outside a transaction before writing, creating a narrow window where two concurrent identical webhook deliveries both pass the terminal-state guard and both call `summaryRef.set`. The second write is idempotent in effect (same status, `FieldValue.serverTimestamp()` differs) but `content` could be overwritten by a conflicting delivery.
3. The `releaseOpenRouterQuotaSlot` rollback uses `pop()` on the trimmed array to remove "the newest entry" but pop removes the last element in sorted order, not necessarily the entry that was added by the caller under concurrent release.

No data-corrupting BLOCKERs found. No deadlocks identified. No unbounded growth: the `recentTimestamps` array is bounded by `perMinuteLimit` (20) via the check in `reserveOpenRouterQuotaSlot`, though it can grow slightly beyond 20 if the trim window moves between reserve and release.

**Overall Assessment:**
- Atomicity: Mostly Protected (quota + lock = good; enqueue + webhook = slight gap)
- Idempotency: Mostly Ensured (dispatch + webhook terminal check = good; webhook concurrent delivery = gap)
- Locking: Correct (dispatcher lock is transactional; quota is transactional)
- Transaction Safety: Good
- Async Correctness: Correct (no missing awaits found; Android StateFlow lifecycle is correct)

---

## 2) Findings Table

| ID | Severity | Confidence | Pattern | Operation | Race Condition |
|----|----------|------------|---------|-----------|----------------|
| BC-1 | MED | High | Check-then-act | `enqueueAutoSummary` → `batch.set` | Two concurrent syncs can both see doc absent → both write `queued` |
| BC-2 | MED | Med | Check-then-act (non-txn) | `processSummaryWebhook` read+write | Two identical webhook deliveries both pass terminal guard → double-set |
| BC-3 | LOW | High | Incorrect release logic | `releaseOpenRouterQuotaSlot` `pop()` | Concurrent releases remove wrong timestamp entry (trending correct over time) |
| BC-4 | LOW | Med | TOCTOU | `dispatchSummary` outer idempotency read | Non-transactional read before quota reserve; concurrent callers both pass, quota bumped twice |
| BC-5 | LOW | Med | Ordering assumption | `getQuotaBudget` read used as dispatch hint | Snapshot is stale by the time `drainSummaryQueue` iterates; per-call txn is the real gate |
| BC-6 | NIT | High | Listener not re-attached on error | `FirestoreRepository` snapshot listeners | Error callback swallows error but does not re-subscribe; permanent listen failure is silent |
| BC-7 | NIT | High | Android double-dispatch guard | `SummaryViewModel.init` | `autoDispatched.compareAndSet` + `getOnce` is correct; noted as safe |

**Findings Summary:**
- BLOCKER: 0
- HIGH: 0
- MED: 2
- LOW: 3
- NIT: 2

---

## 3) Findings (Detailed)

### BC-1: Check-Then-Act in `enqueueAutoSummary` [MED]

**Location:** `backend/functions/src/summarizer/autoEnqueue.ts:39-57`

**Concurrency Pattern:** Check-Then-Act (non-transactional batch getAll → batch.set)

**Vulnerable Code:**
```typescript
// lines 39-57 (simplified)
const refs = group.map((id) => db.doc(`summaries/${id}`));
const snaps = await db.getAll(...refs);   // ← read snapshot
const batch = db.batch();
snaps.forEach((snap, i) => {
  if (snap.exists) { skipped++; return; } // ← check
  batch.set(refs[i], doc);                // ← act
  writes++;
});
if (writes > 0) await batch.commit();    // ← commit
```

**Race Scenario:**

```
Time | Sync A (syncAllPlaylists)             | Sync B (scheduledSync, same ~time)
-----|---------------------------------------|-----------------------------------
t0   | getAll(["vid1","vid2",...])           |
t1   |                                       | getAll(["vid1","vid2",...])
t2   | both see vid1 absent → both set       |
t3   | batch.commit() → vid1 doc written     |
t4   |                                       | batch.commit() → vid1 doc OVERWRITTEN
```

Both syncs write a `SummaryDocument` with `status:"queued"`, `webhookSecret:""`, and a server-side `requestedAt`. The second write resets `requestedAt` to a later timestamp (FIFO ordering in the dispatcher is perturbed slightly). If a `pending` doc was written between the read and the second batch commit (a manual dispatch racing the auto-enqueue), the `pending` doc would be silently clobbered back to `queued` — this is the higher-severity scenario.

**Impact:**
- Worst case: a `pending` doc (dispatched but waiting for the webhook) is reset to `queued`; the dispatcher re-dispatches, burning a quota slot and generating a duplicate summarizer job. The second job's webhook will write a different `webhookSecret` (the new one); the first job's webhook delivery will fail HMAC verification and be dropped → summary content comes from the second job. Net outcome: duplicate cost, not corruption. Likelihood: low (requires manual dispatch to race a concurrent sync flush at exactly the transition moment).
- Typical case: two queued docs with the same content written; second write is idempotent in effect. No corruption.

**Severity:** MED
**Confidence:** High (requires two concurrent syncs; second scenario requires additional concurrent manual dispatch)

**Fix:**

Use a transaction or conditional create per doc:

```typescript
// Option A: conditional create inside a transaction per doc
// (suitable when group size is small)
for (const [i, snap] of snaps.entries()) {
  if (snap.exists) { skipped++; continue; }
  await db.runTransaction(async (tx) => {
    const fresh = await tx.get(refs[i]);
    if (fresh.exists) { skipped++; return; }
    tx.set(refs[i], doc);
    enqueued++;
  });
}

// Option B: use create() (fails if doc exists) and ignore AlreadyExists
for (const [i, snap] of snaps.entries()) {
  if (snap.exists) { skipped++; continue; }
  try {
    await refs[i].create(buildDoc(group[i]));
    enqueued++;
  } catch (err: any) {
    if (err.code === 6 /* ALREADY_EXISTS */) { skipped++; }
    else throw err;
  }
}
```

Option B is preferred: it is atomic at the Firestore level (create is conditional) and preserves the batch semantics without a full transaction per doc.

---

### BC-2: Non-Transactional Read+Write in `processSummaryWebhook` [MED]

**Location:** `backend/functions/src/summarizer/webhook.ts:131-189`

**Concurrency Pattern:** Read-Modify-Write without transaction

**Vulnerable Code:**
```typescript
// line 131-133 — non-transactional read
const summarySnap = await summaryRef.get();
if (!summarySnap.exists) { return { status: 404, body: "unknown-job" }; }
const summary = summarySnap.data() as Partial<SummaryDocument> | undefined;
// ...signature verification...

// line 155-169 — terminal guard check (still outside transaction)
const currentStatus = summary?.status;
if (currentStatus === "completed" || ...) {
  if (currentStatus === inboundTerminal) {
    return { status: 204, body: "" };  // idempotent
  }
  return { status: 200, body: "already-terminal" };
}

// line 172-189 — non-transactional write
await summaryRef.set(updates, { merge: true });
```

**Race Scenario:**

```
Time | Webhook delivery 1 (completed)      | Webhook delivery 2 (retry, identical)
-----|-------------------------------------|--------------------------------------
t0   | summaryRef.get() → status=running   |
t1   |                                     | summaryRef.get() → status=running
t2   | terminal guard: running ≠ terminal → proceed |
t3   |                                     | terminal guard: running ≠ terminal → proceed
t4   | summaryRef.set({status:"completed", content:"..."}) |
t5   |                                     | summaryRef.set({status:"completed", content:"..."})
```

Both writes land. The second `set({merge:true})` overwrites `content` with the same string (if payloads are identical) and updates `completedAt` to a different server timestamp. In the common case (identical payloads) this is benign. If two different webhooks deliver competing results (only possible if summarizer fires both `complete` and an error event), the second write wins.

**Impact:**
- Common case (retry): `completedAt` bumped, `content` identical — cosmetically harmless.
- Edge case (competing terminal events): final state depends on write ordering; last-write-wins on Firestore. For a single-operator system with a well-behaved summarizer this is very unlikely, but the idempotency guard doesn't actually protect against it.

**Severity:** MED
**Confidence:** Med (requires webhook retry to arrive before Cloud Functions processes the first; 300s replay window makes this possible)

**Fix:**

Wrap the terminal check + write in a transaction:

```typescript
await db.runTransaction(async (tx) => {
  const snap = await tx.get(summaryRef);
  if (!snap.exists) return; // caller already returned 404 before txn
  const data = snap.data() as Partial<SummaryDocument>;
  const currentStatus = data?.status;
  if (currentStatus === "completed" || currentStatus === "failed-permanent" || currentStatus === "failed-transient") {
    // already terminal — no-op
    return;
  }
  tx.set(summaryRef, updates, { merge: true });
});
```

Note: the signature verification and 404 check must still happen outside the transaction (they don't write), but the terminal guard + write must be atomic.

---

### BC-3: Incorrect `pop()` in `releaseOpenRouterQuotaSlot` [LOW]

**Location:** `backend/functions/src/summarizer/quota.ts:135-138`

**Concurrency Pattern:** Read-Modify-Write with incorrect index assumption

**Vulnerable Code:**
```typescript
const recentTimestamps = trimWindow(data.recentTimestamps ?? [], now);
// Drop the newest entry (we appended last).
if (recentTimestamps.length > 0) recentTimestamps.pop();
```

**Issue:**
`trimWindow` filters to `t > cutoff` (ascending order preserved from original array). Under concurrent reserve+release scenarios, the entry to remove is the one that was appended by *this* caller, not necessarily the last in the array. If two requests reserve at t=100 and t=101, then both try to release, the first release correctly pops t=101 (last), but the second release also pops the next-to-last which might be a legitimate in-flight timestamp from a different request. The comment acknowledges "trends correct over time" — this is an accepted approximation, but it can cause the per-minute counter to drift low (allowing slightly more than `perMinuteLimit` through) over a short window.

**Severity:** LOW
**Confidence:** High (the logic is acknowledged as approximate; the hard cap on `requestCount` is still correct)

**Fix (optional hardening):**

Store the exact timestamp that was reserved and remove it by value:

```typescript
// In releaseOpenRouterQuotaSlot, pass the reserved timestamp
const reservedAt: number = /* passed in from reserve */ ...;
const recentTimestamps = trimWindow(data.recentTimestamps ?? [], now)
  .filter(t => t !== reservedAt);  // remove exact entry
tx.set(ref, { ...next, recentTimestamps }, { merge: false });
```

This requires `reserveOpenRouterQuotaSlot` to return the timestamp it wrote, which is a minor API change.

---

### BC-4: Non-Transactional Idempotency Read in `dispatchSummary` [LOW]

**Location:** `backend/functions/src/summarizer/dispatch.ts:88-94`

**Concurrency Pattern:** Check-Then-Act (outer read is non-transactional)

**Vulnerable Code:**
```typescript
// Non-transactional read — early-exit idempotency gate
const existing = await summaryRef.get();
if (existing.exists) {
  const data = existing.data() as Partial<SummaryDocument> | undefined;
  if (data?.status && IN_FLIGHT_STATUSES.includes(data.status)) {
    return { summaryId: videoId };  // ← early exit, no quota consumed
  }
}
// ... then reserveOpenRouterQuotaSlot() ...
// ... then runTransaction to reserve doc ...
```

**Race Scenario:**

Two concurrent `requestVideoSummary` calls for the same videoId where no doc exists yet:

```
T1: existing = get() → doc absent
T2: existing = get() → doc absent
T1: reserveOpenRouterQuotaSlot() → quota bumped to N+1
T2: reserveOpenRouterQuotaSlot() → quota bumped to N+2
T1: runTransaction → sets doc to pending with secret S1
T2: runTransaction → sets doc to pending with secret S2 (overwrites S1!)
T1: fetch /v1/jobs → job dispatched with secret S1
T2: fetch /v1/jobs → job dispatched with secret S2
```

Result: two summarizer jobs for the same videoId, two quota slots consumed, second dispatch's `webhookSecret` wins in Firestore. The first job's webhook delivery fails HMAC verification (wrong secret). The second job completes normally.

**Impact:** Duplicate quota consumption (2 slots instead of 1) and a wasted summarizer job for rare concurrent manual dispatches. The inner transaction prevents a permanent bad state. For a single-operator system this race is extremely unlikely (requires two taps within milliseconds).

**Severity:** LOW
**Confidence:** Med (requires two concurrent callers; single-operator system makes this rare)

**Fix:**

Move the idempotency check inside the transaction:

```typescript
await db.runTransaction(async (tx) => {
  const snap = await tx.get(summaryRef);
  if (snap.exists) {
    const data = snap.data() as Partial<SummaryDocument>;
    if (data?.status && IN_FLIGHT_STATUSES.includes(data.status)) {
      return; // signal "already in flight" — caller skips quota reserve
    }
  }
  // ... proceed with set
});
```

This requires a small refactor so the transaction result drives whether `reserveOpenRouterQuotaSlot` is called.

---

### BC-5: Stale Budget Snapshot in `drainSummaryQueue` [LOW]

**Location:** `backend/functions/src/summarizer/dispatcher.ts:55-56`

**Concurrency Pattern:** Read-then-iterate with stale snapshot (non-transactional hint)

**Vulnerable Code:**
```typescript
const budget = await getQuotaBudget();                              // ← non-txn read
const remaining = Math.min(budget.remainingDaily, budget.remainingPerMinute);
// ...
for (const doc of queued.docs) {   // ← iterates up to `remaining` docs
  await dispatchSummary(videoId, model);  // ← each call runs its own quota txn
  // ...
}
```

**Issue:**
`getQuotaBudget` is a read-only non-transactional snapshot that can be stale by the time the loop starts. If a manual `requestVideoSummary` call completes between the budget read and the first loop iteration, the dispatcher may attempt one more dispatch than the real remaining budget allows. The per-call `reserveOpenRouterQuotaSlot` transaction will catch this and throw `resource-exhausted`, which the loop handles by `break`ing — so the hard cap is never violated.

This is a known and documented design decision (05-implement doc: "outer budget query is a hint, not a guarantee"). Noted for completeness.

**Severity:** LOW
**Confidence:** Med (handled correctly by per-call transaction; severity is operational — one extra HTTP call to the summarizer that gets immediately rolled back)

**Fix:** None required given per-call transactional reservation. Optional observability improvement: log when the budget hint was stale (`resource-exhausted` mid-drain without `break` from inner error).

---

### BC-6: Silent Firestore Listener Failure in `FirestoreRepository` [NIT]

**Location:** `android/app/src/main/java/com/github/jayteealao/playster/data/firestore/FirestoreRepository.kt:27-37, 84-96, 113-125`

**Concurrency Pattern:** Permanent listen failure swallowed by callback

**Vulnerable Code:**
```kotlin
.addSnapshotListener { snapshot, error ->
    if (error != null) {
        Log.w(TAG, "playlistsFlow listen error", error)
        return@addSnapshotListener  // ← listener stays detached, flow never emits again
    }
    trySend(...)
}
```

**Issue:**
On Firestore permission errors or network failures, the SDK fires the error callback and then the listener is removed internally. The `callbackFlow` never receives another event, nor does it close — the `StateFlow` in the ViewModel freezes at its last value. The UI appears to work (it shows the last data) but is silently stale. On screen rotation the ViewModel is retained (Hilt `@HiltViewModel`) so the flow is not re-subscribed.

**Severity:** NIT (not a concurrency race — single-observer, but a liveness failure under error)
**Confidence:** High

**Fix:** Re-emit an error or close the channel on permanent Firestore error:

```kotlin
if (error != null) {
    Log.w(TAG, "listen error", error)
    close(error)  // signals the flow as failed; StateFlow will retain last value; ViewModel can react
    return@addSnapshotListener
}
```

Or implement re-subscription with exponential backoff in the `callbackFlow`.

---

### BC-7: `SummaryViewModel` Auto-Dispatch Guard [NIT — SAFE]

**Location:** `android/app/src/main/java/com/github/jayteealao/playster/screens/videoDetail/summary/SummaryViewModel.kt:53-65`

**Pattern:** `compareAndSet` guard on `autoDispatched`

**Assessment:** The guard is correct. `getOnce` performs a one-shot Firestore read (not a listener) before `compareAndSet`, so there is no race between the listener and the one-time dispatch. `MutableStateFlow.compareAndSet` is atomic on the JVM. On screen rotation, Hilt retains the `@HiltViewModel` instance so `autoDispatched` is not reset. This is safe.

---

## 4) Concurrency Safety Analysis

| Operation | Atomicity | Idempotency | Locking | Risk |
|-----------|-----------|-------------|---------|------|
| `reserveOpenRouterQuotaSlot` | ✅ Transactional | ✅ Hard cap enforced atomically | ✅ Firestore txn | SAFE |
| `releaseOpenRouterQuotaSlot` | ✅ Transactional | ⚠️ pop() approximation | ✅ Firestore txn | LOW (BC-3) |
| `acquireDispatcherLock` | ✅ Transactional | ✅ TTL-based re-claim | ✅ Firestore txn | SAFE |
| `drainSummaryQueue` | ⚠️ Budget hint is stale | ✅ Per-call quota txn recovers | ✅ Outer lock | LOW (BC-5) |
| `enqueueAutoSummary` | ❌ getAll→set not atomic | ⚠️ Mostly idempotent (same status) | ❌ None on doc | MED (BC-1) |
| `dispatchSummary` idempotency | ❌ Outer read non-txn | ⚠️ Inner txn partially recovers | ❌ None on pre-check | LOW (BC-4) |
| `dispatchSummary` reservation | ✅ Transactional set | ✅ IN_FLIGHT check inside txn | ✅ Firestore txn | SAFE |
| `processSummaryWebhook` | ❌ Read+write non-txn | ⚠️ Terminal guard non-atomic | ❌ None | MED (BC-2) |
| SQLite `createJob` | ✅ Single stmt | ✅ nanoid PK | ✅ WAL, sync driver | SAFE |
| SQLite `updateJobStatus` | ✅ Single stmt | ✅ unconditional update | ✅ WAL, sync driver | SAFE |
| Android StateFlow `uiState` | ✅ `combine` is thread-safe | N/A | N/A | SAFE |
| Android `refresh()` guard | ✅ `_isRefreshing.value` check | ⚠️ Not atomic (read+write gap) | ❌ None | Low (see note) |

Note on `PlaylistViewModel.refresh()`: `if (_isRefreshing.value) return` followed by `_isRefreshing.value = true` is a check-then-act on a `MutableStateFlow`. Under concurrent `refresh()` calls from the UI (impossible from a single button but possible if called from two coroutines), both could pass the guard. In Compose single-thread UI, this is not reachable — noted for completeness.

---

## 5) SQLite Concurrency (summarize-api)

`better-sqlite3` is synchronous and serializes all calls through the Node.js event loop. WAL mode is enabled (`PRAGMA journal_mode = WAL`). There are no async read-modify-write sequences on the SQLite layer; every operation (`createJob`, `updateJobStatus`) is a single synchronous statement. The singleton `db` module (`let db: Database.Database | null = null`) is safe in a single-process Cloud Run container. No issues found.

---

## 6) Recommendations

### Medium Priority (fix before wider deployment)

**BC-1: Protect `enqueueAutoSummary` against concurrent syncs overwriting in-flight docs**
- Action: Replace `getAll → batch.set` with per-doc `create()` (throws `ALREADY_EXISTS`) or individual conditional transactions
- Effort: ~1 hour
- Risk: Duplicate dispatch + quota waste; more importantly: `pending` doc reset to `queued` in rare triple-race scenario

**BC-2: Wrap `processSummaryWebhook` terminal guard in a transaction**
- Action: Move terminal-status check + `summaryRef.set` into `db.runTransaction`
- Effort: ~30 minutes
- Risk: Concurrent identical webhook deliveries both write; edge case is cosmetic but a proper at-most-once write is cheap to achieve

### Low Priority (address in follow-up)

**BC-3: Pass reserved timestamp to `releaseOpenRouterQuotaSlot`**
- Action: Return `reservedAt` from `reserveOpenRouterQuotaSlot` and filter by value in release
- Effort: ~30 minutes
- Risk: Minor per-minute count drift under concurrent release; hard daily cap is unaffected

**BC-4: Move outer idempotency check inside `dispatchSummary` transaction**
- Action: Combine the outer `summaryRef.get()` idempotency check with the existing `runTransaction` body
- Effort: ~20 minutes
- Risk: Duplicate quota consumption + wasted summarizer job on concurrent manual dispatches; extremely unlikely in single-operator use

### NIT (optional improvements)

**BC-6: Propagate Firestore listener errors to the flow rather than swallowing them**
- Action: Call `close(error)` in snapshot listener error branch
- Effort: ~10 minutes per repository

---

## 7) False Positives & Caveats

- **BC-1 worst case** requires three concurrent operations (two syncs + one manual dispatch) within milliseconds — acceptable risk for a single-operator system; prioritized MED not HIGH.
- **BC-5** is explicitly accepted in the implementation notes. The per-call transaction makes the stale budget hint safe.
- **SQLite** is evaluated as safe because `better-sqlite3` is synchronous and the summarize-api runs as a single Cloud Run instance.
- **BC-7 (`SummaryViewModel` auto-dispatch)** is explicitly assessed as safe — listed to document the analysis.

---

*Review completed: 2026-05-20*
*Branch: feat/wire-android-backend-summarizer vs main*

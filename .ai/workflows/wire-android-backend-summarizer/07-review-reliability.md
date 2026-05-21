---
review-command: reliability
slug: wire-android-backend-summarizer
scope: slug-wide
target: git diff main...HEAD (232 files, +16 714 / -1 212 lines)
completed: "2026-05-20"
id-prefix: REL-
---

# Reliability Review — wire-android-backend-summarizer

**Scope:** Full branch diff — all four slices (auth-and-android-firebase, summarizer-container, summary-orchestration, summary-ui)
**Reviewer:** Claude Reliability Review Agent
**Date:** 2026-05-20

## Summary

The pipeline is well-structured for a single-tenant system and handles the
majority of failure modes correctly. The most significant gap is the absence
of an HTTP timeout on the backend's outbound dispatch call to Cloud Run
(`dispatch.ts`): a hung Cloud Run instance will hold an open fetch for up to
the full 60-second Cloud Function timeout, blocking quota that has already been
pre-incremented. The remaining issues are medium-severity: the Firestore
listener error path on Android does not propagate errors into the UI flow
(silent drop), the quota release on dispatch failure is best-effort-only (can
cause under-counting), and the SSE stream reader in `url-runner.ts` resolves
the promise on `done` before clearing the abort controller timeout.

No retry storm, infinite loop, or catastrophic idempotency hole was found. The
deferred crons (AC-12 sweeper, AC-13 retry) are correctly absent and no
dependent code assumes they run. The cron lock implementation is correct.

**Severity Breakdown:**
- BLOCKER: 0
- HIGH: 1 — missing fetch timeout in `dispatch.ts`
- MED: 4 — Android listener silent error, quota decrement race, SSE double-resolve, no jitter on webhook backoff
- LOW: 2 — entrypoint `/v1/refresh-free` not timed out, quota read-then-dispatch non-atomic gap
- NIT: 1 — webhook failure payload omits error `code` field

**Merge Recommendation:** REQUEST_CHANGES (HIGH finding must be addressed)

---

## Findings

### REL-01: No HTTP timeout on outbound dispatch to Cloud Run [HIGH] — Confidence: High

**Location:** `backend/functions/src/summarizer/dispatch.ts:131`

**Issue:**
The `fetch` call to `${SUMMARIZER_URL}/v1/jobs` carries no `AbortSignal` or
timeout. If Cloud Run is cold-starting, scaled-to-zero, or slow, this fetch
can hang for the full duration of the Cloud Function execution (60 s). During
that entire window the quota slot is already reserved (pre-increment at line
98), so a hung dispatch burns quota and blocks any other concurrent dispatch
from succeeding.

**Evidence:**
```typescript
// dispatch.ts:131 — no signal or timeout
response = await doFetch(`${SUMMARIZER_URL.value()}/v1/jobs`, {
  method: "POST",
  headers: { ... },
  body: JSON.stringify(body),
});
```

**Failure Scenario:**
1. Operator taps Summarize; quota slot reserved.
2. Cloud Run is cold-starting; fetch hangs for 60 s.
3. Cloud Function times out — dispatch is marked failed-transient (line 142–151) but only after the full timeout elapses.
4. Next manual retry or auto-dispatch attempt is delayed because the quota slot was held for 60 s even though it was eventually released.
5. Under concurrent auto-dispatch, several quota slots can be occupied simultaneously by hung requests, exhausting the per-minute window of 20.

**Impact:**
- User impact: Summarize appears to stall for 60 s before showing failed-transient.
- System impact: Per-minute quota slots consumed by hung dispatches, throttling legitimate auto-dispatch.
- Recovery time: Self-clears after 60 s per invocation, but cascades under auto-dispatch batch.

**Fix:**
```typescript
const dispatchController = new AbortController();
const dispatchTimeout = setTimeout(() => dispatchController.abort(), 15_000);
try {
  response = await doFetch(`${SUMMARIZER_URL.value()}/v1/jobs`, {
    method: "POST",
    headers: { ... },
    body: JSON.stringify(body),
    signal: dispatchController.signal,
  });
} catch (err) {
  clearTimeout(dispatchTimeout);
  // AbortError → treat as transient (same as current network catch block)
  ...
} finally {
  clearTimeout(dispatchTimeout);
}
```
15 s is generous for a Cloud Run dispatch; the shape spec's cold-start latency
expectation is ≤ 15 s end-to-end, so failing fast here is appropriate.

---

### REL-02: Firestore listener errors on Android are silently dropped — no UI state update [MED] — Confidence: High

**Location:** `android/app/src/main/java/com/github/jayteealao/playster/data/firestore/FirestoreRepository.kt:86-89` and `:119-121`

**Issue:**
Both `SummaryRepository.observe()` and `QuotaRepository.observe()` log the
error via `Log.w` and then `return@addSnapshotListener` without calling
`trySend` or `close`. When Firestore goes offline or the security rule denies
the read mid-session, the flow simply stops emitting — the UI stays frozen in
whatever state it last rendered (e.g., InProgress forever) rather than
surfacing an error state.

**Evidence:**
```kotlin
// SummaryRepository.kt:86-89
.addSnapshotListener { snapshot, error ->
    if (error != null) {
        Log.w(SUMMARY_TAG, "summary listen error for $videoId", error)
        return@addSnapshotListener   // ← flow stalls silently
    }
    trySend(snapshot?.toSummaryDoc())
}
```

**Failure Scenario:**
1. Operator opens SummaryScreen; Firestore listener attaches and emits `InProgress`.
2. Device goes offline (Airplane mode, tunnel).
3. Listener fires with `FirebaseFirestoreException(UNAVAILABLE)`.
4. Error is logged but not emitted — UI shows spinner forever.
5. When device reconnects, Firestore SDK reconnects automatically and the
   listener resumes, so recovery is eventual. But during the offline window the
   user has no indication of the problem.

**Impact:**
- User impact: Spinner shown indefinitely when offline; no error message, no retry prompt.
- System impact: No crash, but poor UX and no observability from Android side.
- Recovery time: Automatic when network recovers.

**Fix:**
```kotlin
if (error != null) {
    Log.w(SUMMARY_TAG, "summary listen error for $videoId", error)
    // Firestore SDK will re-attach automatically on reconnect.
    // Emit null to let UI fall back to NoSummary / show an error.
    // Do NOT close the flow — the SDK will fire again on reconnect.
    trySend(null)
    return@addSnapshotListener
}
```
Alternatively, emit a dedicated error sentinel or use `close(error)` if the
error is permanent (e.g., `PERMISSION_DENIED`), and only `trySend(null)` on
transient errors. The `QuotaRepository` listener (line 119–121) has the same
pattern and needs the same fix.

---

### REL-03: Quota slot decrement on dispatch failure is non-transactional and can over-release [MED] — Confidence: Med

**Location:** `backend/functions/src/summarizer/quota.ts:121-157` (`releaseOpenRouterQuotaSlot`)

**Issue:**
`releaseOpenRouterQuotaSlot` pops the *newest* entry from
`recentTimestamps` after trimming the window. If two concurrent dispatches
both fail and both call `releaseOpenRouterQuotaSlot` at the same time, each
transaction reads the same snapshot, both trim the same window, and both pop
the last entry from that window — effectively removing two entries from a
single timestamp slot. This can cause `recentTimestamps.length` to fall below
its true value, allowing future dispatches that should be rate-limited to
proceed.

**Evidence:**
```typescript
// quota.ts:136-138 — trim then pop newest
const recentTimestamps = trimWindow(data.recentTimestamps ?? [], now);
if (recentTimestamps.length > 0) recentTimestamps.pop();
tx.set(ref, { ..., recentTimestamps }, { merge: false });
```
Two concurrent transactions can both read the same `recentTimestamps` array
before either commits. Firestore transactions use optimistic concurrency, so
one will retry — but both will pop the same "last" element of the trimmed
array.

**Impact:**
- User impact: None visible; at worst, one extra dispatch slips through the per-minute window.
- System impact: Minor quota under-counting (1–2 extra requests per concurrent failure pair). The shape explicitly accepts over-counting as preferable to under-counting on the reserve side, but does not address this direction.
- Recovery time: Self-corrects at next day-rollover.

**Mitigation note:** This is a best-effort decrement path. The shape doc explicitly acknowledges over-counting on failed decrements as acceptable. The severity is therefore MED rather than HIGH, but the pop logic should be documented as intentionally approximate.

**Fix (documentation):** Add a comment on `releaseOpenRouterQuotaSlot` clarifying that `recentTimestamps` trim is approximate under concurrent failure, and that the daily `requestCount` decrement is the authoritative over-count guard.

---

### REL-04: SSE stream reader calls `resolve()` twice when stream ends without an explicit `done`/`complete` event [MED] — Confidence: High

**Location:** `summarizer/summarize-api/src/runners/url-runner.ts:178-188`

**Issue:**
The SSE `read()` loop calls `resolve()` when `reader.read()` returns
`{ done: true }` (EOF). If the daemon already emitted a `complete` or `done`
event earlier, `resolve()` was already called (line 155). Calling `resolve()`
on an already-settled Promise is harmless in itself, but the abort-controller
timeout is only cleared in `finally` (line 220), so there is no double-settle
issue at the Promise layer. However, when the stream ends by EOF *without* a
prior terminal event (e.g., daemon crash mid-stream), the job is still marked
`completed` with whatever partial chunks were accumulated — the `result`
variable is set to the partial join (line 192) and a webhook is sent claiming
`status: "completed"`. The backend then writes `status: completed` with
potentially empty or truncated content.

**Evidence:**
```typescript
// url-runner.ts:179-183 — EOF treated as success
if (done) {
    parser.reset({ consume: true });
    resolve();   // ← always resolves on EOF, even if stream was truncated
    return;
}
```
After the outer Promise resolves, execution falls through to:
```typescript
// url-runner.ts:192-201
const result = accumulatedChunks.join("");   // may be empty or partial
updateJobStatus(job.id, "completed", { result: { summary: result } });
await maybeDeliverWebhook(job, { ..., status: "completed", ... });
```

**Failure Scenario:**
1. Daemon starts streaming chunks then crashes (OOM, SIGKILL from Cloud Run).
2. SSE reader gets `done: true` from the HTTP response body without a `complete` event.
3. `resolve()` is called with partial chunks; webhook reports `status: completed`.
4. Backend marks `summaries/{videoId}` as `completed` with empty or partial content.
5. Operator sees a blank summary card with no retry option.

**Fix:**
```typescript
if (done) {
    parser.reset({ consume: true });
    if (accumulatedChunks.length === 0) {
        // EOF without a terminal event and no content — treat as failure.
        reject(new Error("SSE stream ended without a terminal event"));
    } else {
        resolve();
    }
    return;
}
```
Alternatively, track a `terminalEventReceived` boolean and reject on EOF if it
is false.

---

### REL-05: Webhook retry backoff has no jitter [MED] — Confidence: Med

**Location:** `summarizer/summarize-api/src/webhooks/deliver.ts:61`

**Issue:**
The backoff is `baseDelayMs * 3^i` (5 s, 15 s, 45 s) with no jitter. If a
batch of auto-dispatched jobs all complete at similar times (e.g., after a
Cloud Run cold start), all of their webhooks will retry in lockstep with
identical delays, creating a minor thundering-herd on the backend's
`summaryWebhook` endpoint. For a single-tenant system this is unlikely to
cause saturation, but it is trivial to fix.

**Evidence:**
```typescript
// deliver.ts:61
await sleep(baseDelayMs * 3 ** i);   // no jitter
```

**Fix:**
```typescript
const base = baseDelayMs * 3 ** i;
const jitter = Math.random() * base * 0.25;
await sleep(base + jitter);
```

---

### REL-06: `entrypoint.js` — `/v1/refresh-free` call has no timeout [LOW] — Confidence: Med

**Location:** `summarizer/deploy/entrypoint.js:83-89`

**Issue:**
The `refreshFree()` call issues a POST to `${DAEMON_URL}/v1/refresh-free` with
no timeout or AbortSignal. The comment says "continuing with cached free
models" on failure, which is the correct degradation strategy, but if the
daemon's refresh call itself hangs (e.g., OpenRouter is unreachable and the
daemon has no internal timeout), the entrypoint is stuck and the gateway never
starts. Cloud Run will eventually kill the container after the startup probe
deadline, but that adds latency to every cold start.

**Evidence:**
```javascript
// entrypoint.js:86-89
const res = await fetch(`${DAEMON_URL}/v1/refresh-free`, {
    method: "POST",
    headers: { Authorization: `Bearer ${token}` },
    // no timeout or signal
});
```

**Fix:**
```javascript
const ctrl = new AbortController();
const t = setTimeout(() => ctrl.abort(), 10_000);  // 10 s max for refresh
try {
    const res = await fetch(`${DAEMON_URL}/v1/refresh-free`, {
        method: "POST",
        headers: { Authorization: `Bearer ${token}` },
        signal: ctrl.signal,
    });
    ...
} catch (err) {
    logError("/v1/refresh-free failed — continuing with cached models", {
        error: err instanceof Error ? err.message : String(err),
    });
} finally {
    clearTimeout(t);
}
```

---

### REL-07: Quota getQuotaBudget read and subsequent dispatch are not atomic [LOW] — Confidence: Med

**Location:** `backend/functions/src/summarizer/dispatcher.ts:55-68`

**Issue:**
`drainSummaryQueue` reads the budget with `getQuotaBudget()` (a non-transactional
read), then issues up to `remaining` individual `dispatchSummary` calls, each
of which does its own `reserveOpenRouterQuotaSlot` transaction. Between the
initial budget read and the first dispatch, another concurrent dispatcher
invocation (theoretically impossible under the lock, but possible if the lock
acquire has a race window) or a manual callable invocation could consume
quota. This is not a correctness hole because each `dispatchSummary` call
re-checks and reserves transactionally — it just means the `remaining` limit
computed upfront is advisory, not enforced. Under the single-tenant constraint
with a 5-minute cron and a manual retry path, this is very low risk.

**Impact:** At most 1–2 extra dispatches above the per-minute window in a
race. Acceptable for MVP.

---

### REL-08: Webhook failure payload `status: "failed"` but no `error.code` field [NIT] — Confidence: High

**Location:** `summarizer/summarize-api/src/runners/url-runner.ts:214-218`

**Issue:**
The failure webhook payload includes `error: { message }` but no `error.code`.
The backend `webhook.ts` reads `parsed.error?.code` to classify
failed-transient vs failed-permanent. When `code` is absent, `classifyFailure`
returns `failed-transient` (the safe default), but the intent of the shape
spec is that the daemon signals specific codes like `quota_exhausted` or
`unrecoverable`. Since `url-runner.ts` catches a generic Error with only a
message, none of those codes are forwarded — all gateway-level errors will
land as failed-transient regardless of root cause.

**Evidence:**
```typescript
// url-runner.ts:214-218
await maybeDeliverWebhook(job, {
    client_job_id: job.client_job_id ?? job.id,
    status: "failed",
    error: { message },   // ← no `code` field
});
```

**Fix:** Parse a `code` from the error message when available (e.g., if the
daemon's SSE error event includes a code field), or add a `code: "gateway_error"`
sentinel so the backend can distinguish gateway failures from daemon failures.

---

## Dependency Analysis

**External Dependencies:**

| Dependency | Timeout | Retry | Fallback |
|---|---|---|---|
| Cloud Run summarizer (dispatch.ts) | ❌ NONE | ❌ No | failed-transient state |
| Cloud Run summarizer (url-runner.ts) | ✅ `JOB_TIMEOUT_MS` (5 min default) | ❌ No (fire once) | failed webhook |
| Daemon `/health` (entrypoint.js) | ✅ 30 s deadline | ✅ 200 ms poll | exits container |
| Daemon `/v1/refresh-free` (entrypoint.js) | ❌ NONE | ❌ No | logged + continues |
| Daemon `/v1/summarize` SSE (url-runner.ts) | ✅ same AbortController as job | N/A | fails job |
| Backend webhook receiver (deliver.ts) | ✅ implicit (no AbortSignal, but OS TCP timeout) | ✅ 3 attempts, 3^i backoff | logs failure |
| Firestore (backend) | Cloud Functions SDK default | SDK built-in | N/A |
| Firestore (Android) | SDK manages | SDK reconnects | silent on error (REL-02) |
| OpenRouter API | daemon-internal | daemon-internal | `refresh-free` rotation |

**Single Points of Failure:**
- Cloud Run summarizer: no fallback, but failure mode is well-defined (failed-transient) and the retry path (manual button / daily cron in v1.1) handles recovery.
- OpenRouter: no alternative model provider; daily limit is the only backstop.

---

## Error Handling Coverage

**Critical Paths:** 5 (dispatch, webhook receive, quota check, SSE stream, entrypoint boot)
**Missing Error Handling:** 1 (fetch timeout in dispatch)

| Path | try/catch | Timeout | Fallback / graceful degrade | Risk |
|---|---|---|---|---|
| Dispatch to Cloud Run | ✅ | ❌ | failed-transient state | HIGH (REL-01) |
| Webhook delivery (3 attempts) | ✅ | ✅ (OS TCP) | logs + gives up | LOW |
| Webhook receive (backend) | ✅ | ✅ (30 s CF) | HTTP 4xx/5xx + no doc write | LOW |
| Cron lock acquire | ✅ | ✅ (4 min TTL) | returns false, skips run | LOW |
| Entrypoint /health wait | ✅ | ✅ (30 s deadline) | exits container | LOW |
| Entrypoint /refresh-free | ✅ | ❌ | logs + continues | LOW (REL-06) |
| SSE stream parse | ✅ | ✅ (job AbortController) | failed webhook | MED (REL-04) |
| Android Firestore listener | ✅ log only | N/A | silent drop | MED (REL-02) |
| Quota reserve (transactional) | ✅ | N/A | throws HttpsError | LOW |
| Quota release (best-effort) | ✅ | N/A | logs + swallows | MED (REL-03) |

---

## Retry / Backoff Verification

**Webhook delivery (`deliver.ts`):**
- Attempts: 3 ✅ (spec: 3)
- Backoff: `baseDelayMs * 3^i` = 5 s, 15 s, 45 s — exponential ✅
- Non-retryable 4xx short-circuit: ✅ (lines 54–56, except 408/429)
- Jitter: ❌ (REL-05, MED)

**Dispatch to summarizer (`dispatch.ts`):** No retry — single attempt. Failure
sets `failed-transient`; the manual retry button and (deferred) daily cron
serve as the retry mechanism. This is by design per the slice strategy.

**Cron lock (`dispatcher.ts`):** `acquireDispatcherLock` uses a 4-minute TTL
(`LOCK_TTL_MS = 240_000`) on a 5-minute cron ✅ (spec requirement verified).

**Deferred crons (AC-12 sweeper, AC-13 retry):** Neither `sweeper.ts` nor
`retry.ts` exist in the diff. No code in the merged slices assumes they run
(e.g., no stuck-running docs are auto-cleaned by any existing path). Confirmed
acceptable per slice strategy — manual Firestore cleanup only.

---

## Idempotency Verification

**Webhook receive (`webhook.ts`):** Idempotent — if doc is already in a
terminal state and the incoming terminal state matches, returns HTTP 204 ✅.
If states differ (terminal-mismatch), logs and returns HTTP 200 keeping first
state ✅.

**Dispatch (`dispatch.ts`):** Idempotent for in-flight statuses (queued,
pending, running, completed) — returns early without re-dispatching ✅.
Failed docs (failed-transient, failed-permanent) intentionally fall through
to a fresh attempt ✅.

**Auto-enqueue:** Status=queued write is not covered in the diff (no
`autoSummarize.ts` source file found on the branch); confirm this path exists
before merge.

---

## Recommendations

### Immediate (HIGH)
1. **REL-01** — Add a 15-second `AbortSignal` timeout on the `fetch` call in
   `dispatch.ts:131`. This is a one-line fix with a `new AbortController()`.

### Short-term (MED)
2. **REL-02** — Emit `null` (or a typed error sentinel) from Firestore
   listener error handlers in `FirestoreRepository.kt` so the UI can surface
   offline/permission errors instead of hanging forever in InProgress state.
3. **REL-04** — Treat EOF without a prior terminal event in `url-runner.ts`
   as a failure, not success. Prevents blank-but-"completed" summaries when
   the daemon crashes mid-stream.
4. **REL-05** — Add ±25% jitter to webhook retry backoff in `deliver.ts`.
5. **REL-03** — Add a comment documenting that `releaseOpenRouterQuotaSlot`
   is approximate under concurrency (no code change required).

### Long-term (LOW / NIT)
6. **REL-06** — Add a 10-second timeout to the `/v1/refresh-free` fetch in
   `entrypoint.js` to bound cold-start time when OpenRouter is slow.
7. **REL-08** — Forward a `code` field in the webhook failure payload from
   `url-runner.ts` when the daemon's SSE error event includes one.

---
schema: sdlc/v1
type: review-command
slug: wire-android-backend-summarizer
review-scope: slug-wide
slice-slug: ""
review-command: correctness
status: complete
updated-at: "2026-05-20T23:10:00Z"
metric-findings-total: 9
metric-findings-blocker: 1
metric-findings-high: 2
result: blockers-found
tags: []
refs:
  review-master: 07-review.md
---

# Review: correctness

## 0) Scope, Intent, and Invariants

**What was reviewed:**
- Scope: branch diff (`git diff main...HEAD`)
- Files changed: ~232 files, +16 714 / -1 212 lines
- Primary surfaces: `backend/functions/src/summarizer/` (6 new TS modules), `summarizer/summarize-api/src/runners/url-runner.ts`, `summarizer/deploy/entrypoint.js`, `android/app/src/main/java/.../data/firestore/QuotaDoc.kt`, `backend/firestore.rules`, emulator test suites.

**Intended behavior:**
- Operator taps Summarize → `requestVideoSummary` callable → quota check → dispatch to Cloud Run → daemon → HMAC-signed webhook → Firestore doc updated → Android listener renders result.
- Auto-summarize: sync handlers enqueue `status=queued` docs; `summaryDispatcher` cron drains them within daily (1000/day) and per-minute (20/min) caps.
- Webhook signing: Stripe-style `t=<unix>,v1=<sha256-hex>`, body = `${timestamp}.${raw_body}`, replay window 300 s, constant-time compare.
- State machine: `queued → pending → running → completed | failed-transient | failed-permanent`. No reverse transitions.

**Must-hold invariants:**
1. **Daily quota cap (1000) must never be exceeded** — transactional read+write on `quota/openrouter` ensures no two concurrent requests both pass the cap check.
2. **Webhook HMAC must be verified before any Firestore write** — prevents spoofed completion events.
3. **Replay window of 300 s must be enforced** — rejects stale/replayed webhooks.
4. **`summaries/{videoId}` must transition monotonically to a terminal state** — no terminal → pre-terminal reversals.
5. **Per-summary `webhookSecret` must be generated fresh per dispatch** — a leaked secret compromises only one summary.
6. **Dispatcher cron must not double-dispatch the same queued doc** — Firestore lock + `maxInstances:1` prevents overlap.
7. **A queued doc with empty `webhookSecret` cannot receive a successful webhook** — `verifySignature` guards against empty secret.

**Key constraints:**
- Single-tenant: one allowlisted uid (`ALLOWED_UID`); Firestore rules hardcode uid.
- Quota is pessimistic-pre-increment; failed dispatches release the slot best-effort.
- Webhook idempotency: terminal-state + matching status → 204 no-op; mismatch → 200 with first-wins semantics.
- Dispatcher uses a non-transactional budget snapshot as an upper bound, then relies on per-call transactional reservations.

---

## 1) Executive Summary

**Merge Recommendation:** REQUEST_CHANGES

**Rationale:**
One blocker exists: `firestore.rules` ships with the sentinel `__BOOTSTRAP_UID__` hardcoded, meaning all client-side reads of `playlists/`, `videos/`, `summaries/`, and `quota/` are denied until the operator performs the uid-capture bootstrap and redeploys rules. This is a documented requirement, but the current deployed rules make the Android app completely non-functional as shipped. Two HIGH findings — a potential partial-content delivery as completed when the SSE stream closes without a terminal event, and the non-atomic idempotency check in `dispatchSummary` that allows two concurrent manual calls to burn double quota and launch duplicate summarizer jobs — are worth addressing before production traffic.

**Critical Issues (BLOCKER/HIGH):**
1. **CR-1**: `firestore.rules` ships with sentinel uid — all client reads denied until bootstrap re-deploy.
2. **CR-2**: SSE stream EOF without a `done`/`error` event resolves the Promise and delivers an incomplete (possibly empty) summary as `status=completed`.
3. **CR-3**: Non-atomic idempotency check in `dispatchSummary` allows two concurrent calls for the same `videoId` to both pass the pre-flight check, burn two quota slots, and dispatch two summarizer jobs.

**Overall Assessment:**
- Correctness: Good (state machine is sound; HMAC and replay window are correct; quota transaction is genuinely atomic)
- Error Handling: Adequate (all dispatch failure paths correctly release quota; network errors map to failed-transient)
- Edge Case Coverage: Incomplete (SSE early-EOF not handled; concurrent same-videoId dispatch not guarded)
- Invariant Safety: Mostly Safe (quota hard cap is protected; HMAC is correct; replay window is correct; idempotency gap is narrow for single-tenant but real)

---

## 2) Findings Table

| ID | Severity | Confidence | Category | File:Line | Failure Scenario |
|----|----------|------------|----------|-----------|------------------|
| CR-1 | BLOCKER | High | State/Deployment | `backend/firestore.rules:6` | Sentinel uid → all client reads denied at runtime |
| CR-2 | HIGH | High | Error Handling | `summarizer/summarize-api/src/runners/url-runner.ts:179-189` | SSE stream EOF without `done` event → partial/empty summary delivered as `completed` |
| CR-3 | HIGH | Med | Concurrency/Idempotency | `backend/functions/src/summarizer/dispatch.ts:88-117` | Concurrent `dispatchSummary` for same videoId → double quota burn + duplicate jobs |
| CR-4 | MED | High | Error Handling | `backend/functions/src/summarizer/dispatcher.ts:57-59` | `remaining <= 0` early `return` inside `try` block — `finally` still runs (confirmed safe) → NIT only, see note |
| CR-5 | MED | High | Boundary | `backend/functions/src/summarizer/webhook.ts:147-152` | Summarizer sends `status:"failed"` without an `error.code` — correctly maps to `failed-transient` but no error code is stored |
| CR-6 | MED | Med | Idempotency | `backend/functions/src/summarizer/dispatch.ts:104-118` | Concurrent dispatches for same videoId: second tx overwrites `webhookSecret`; first job's webhook gets 401 |
| CR-7 | LOW | Med | Boundary | `summarizer/summarize-api/src/runners/url-runner.ts:179` | SSE stream EOF with `{done:true, value:undefined}` decoded to empty string → `parser.feed("")` harmless but `decode` with `{stream:false}` semantics differs |
| CR-8 | LOW | High | Input Validation | `backend/functions/src/summarizer/dispatch.ts:43-47` | `webhookUrl()` uses `process.env.FUNCTION_REGION` which may be `undefined` in emulator, falls back to `FUNCTION_REGION` module constant — harmless for production but inconsistent |
| CR-9 | NIT | High | Determinism | `backend/functions/src/summarizer/quota.ts:135-138` | `releaseOpenRouterQuotaSlot` pops the last entry of `recentTimestamps` after trim; under concurrent release, may pop a different caller's entry — documented accepted behavior |

**Findings Summary:**
- BLOCKER: 1
- HIGH: 2
- MED: 3 (CR-4 reclassified as NIT after confirming `finally` runs on early `return` — see CR-4 note)
- LOW: 2
- NIT: 1

---

## 3) Findings (Detailed)

### CR-1: `firestore.rules` ships with sentinel `__BOOTSTRAP_UID__` — all client reads denied [BLOCKER]

**Location:** `backend/firestore.rules:6`

**Invariant Violated:**
- "Operator signs in once and sees playlists/videos from Firestore" (AC-3, AC-4) — impossible until rules are re-deployed with the real uid.

**Evidence:**
```
rules_version = '2';
service cloud.firestore {
  match /databases/{database}/documents {
    function isAllowlisted() {
      return request.auth != null && request.auth.uid == "__BOOTSTRAP_UID__";
    }
    match /playlists/{document=**} { allow read: if isAllowlisted(); ... }
    match /summaries/{document=**} { allow read: if isAllowlisted(); ... }
```

**Issue:**
The sentinel string `__BOOTSTRAP_UID__` never matches any real Firebase Auth uid. Every client-side read of `playlists/`, `videos/`, `summaries/`, and `quota/` is denied for every user, including the operator, until the operator completes the bootstrap procedure (sign in → capture uid → inject into rules → redeploy). The branch can be merged, but deploying the current rules state makes the Android app completely non-functional. This is a known and documented prerequisite, but it qualifies as a blocker under "ship-stopper" because the system as deployed does not satisfy any of the user-observable ACs (AC-3, AC-4, AC-5, AC-10).

**Failure Scenario:**
```
Operator installs APK → signs in → Google account authenticated → Firebase Auth uid = "real-uid-abc"
Firestore listener on playlists/ → denied (uid != "__BOOTSTRAP_UID__")
PlaylistScreen: empty forever, no error surfaced to user
```

**Severity:** BLOCKER | **Confidence:** High

**Fix:**
This is a documented deployment gate, not a code logic bug. The fix is operator action:
1. Sign in on Android once → capture uid from Firebase Auth console or `lazylogcat`.
2. Replace `__BOOTSTRAP_UID__` in `backend/firestore.rules:6` with the real uid.
3. `firebase deploy --only firestore:rules`.
The review blocker is lifted once this step is performed. Consider adding a CI check that fails if `__BOOTSTRAP_UID__` is present in the deployed rules artifact (not the source), or add a startup check in the Android app that displays a "Contact operator" screen if Firestore reads return PERMISSION_DENIED on the first request.

---

### CR-2: SSE stream EOF without `done`/`error` event delivers partial summary as `completed` [HIGH]

**Location:** `summarizer/summarize-api/src/runners/url-runner.ts:174-195`

**Invariant Violated:**
- "A `completed` webhook implies the daemon successfully finished summarizing with non-empty content" (shape spec §7, AC-6, AC-14).

**Evidence:**
```typescript
// Lines 174-189: read() loop
function read(): void {
  reader.read().then(({ done, value }) => {
    if (done) {
      parser.reset({ consume: true });
      resolve();   // <-- resolves the Promise even if no 'done' event was received
      return;
    }
    parser.feed(decoder.decode(value, { stream: true }));
    read();
  }, reject);
}

// Lines 192-200: after Promise resolves
const result = accumulatedChunks.join(""); // possibly "" or partial
updateJobStatus(job.id, "completed", { result: { summary: result } });
await maybeDeliverWebhook(job, {
  client_job_id: job.client_job_id ?? job.id,
  status: "completed",
  result: { summary: result },
});
```

**Failure Scenario:**
```
1. Daemon starts streaming chunks: "## Summary\n", "The video covers..."
2. Network connection between gateway and daemon drops (Cloud Run networking glitch)
3. reader.read() returns {done: true} (EOF without a 'done' SSE event having fired)
4. resolve() fires; accumulatedChunks = ["## Summary\n", "The video covers..."]
5. maybeDeliverWebhook → POST with status:"completed", result.summary:"## Summary\nThe video covers..."
6. Backend webhook handler: status 204, summaries/{videoId}.status = completed
7. Android renders the truncated partial summary without any indication of incompleteness
```

If the abort controller fires (job timeout), `reject()` is called via the `read()` rejection handler — that path is correct and maps to `failed`. But a clean TCP FIN without the `done` event (e.g., daemon crashes after emitting all chunks but before the `done` event, or a proxy terminates the connection) takes the success path.

**Severity:** HIGH | **Confidence:** High

**Fix:**
Track whether the `done`/`complete` event was explicitly received. If `resolve()` fires from the EOF path without having seen `done`, treat it as a failure:

```diff
--- a/summarizer/summarize-api/src/runners/url-runner.ts
+++ b/summarizer/summarize-api/src/runners/url-runner.ts
@@ -99,6 +99,7 @@
     const accumulatedChunks: string[] = [];
+    let terminalEventReceived = false;

     await new Promise<void>((resolve, reject) => {
       const parser = createParser({
         onEvent(event) {
           ...
           switch (event.event) {
             case "complete":
             case "done":
               ...
+              terminalEventReceived = true;
               resolve();
               break;
             case "error":
               reject(new Error(message));
               break;
           }
         },
         ...
       });

       function read(): void {
         reader.read().then(({ done, value }) => {
           if (done) {
             parser.reset({ consume: true });
-            resolve();
+            if (!terminalEventReceived) {
+              reject(new Error("SSE stream closed without a terminal event"));
+            } else {
+              resolve();
+            }
             return;
           }
```

---

### CR-3: Non-atomic idempotency check in `dispatchSummary` — concurrent calls burn double quota and launch duplicate jobs [HIGH]

**Location:** `backend/functions/src/summarizer/dispatch.ts:88-117`

**Invariant Violated:**
- "Quota: transactional reads/writes on `quota/openrouter` to prevent two concurrent requests both passing the cap check then both burning quota." (shape spec §Non-Functional, Quota).
- "Idempotency on `summaries/{videoId}` keyed by videoId." (shape spec §Non-Functional, Data integrity).

**Evidence:**
```typescript
// Line 88-94: NON-transactional read for idempotency check
const existing = await summaryRef.get();  // plain get, no transaction
if (existing.exists) {
  const data = existing.data() as Partial<SummaryDocument> | undefined;
  if (data?.status && IN_FLIGHT_STATUSES.includes(data.status)) {
    return { summaryId: videoId };  // early return
  }
}

// Line 98: Quota reservation (separate transaction)
await reserveOpenRouterQuotaSlot();  // tx 1

// Line 104: Document write (separate transaction)
await db.runTransaction(async (tx) => {  // tx 2
  const snap = await tx.get(summaryRef);  // re-read inside tx
  // ... sets doc to pending with NEW webhookSecret
  tx.set(summaryRef, next);  // unconditional overwrite
});
```

**Failure Scenario:**
```
T0: User A and User B both tap Summarize on the same video simultaneously.
    (or: requestVideoSummary fires twice due to Android retry after network timeout)
T1: Both call dispatchSummary(videoId, "free")
T2: Both read summaryRef — doc doesn't exist → both pass idempotency check
T3: Both call reserveOpenRouterQuotaSlot() → each burns 1 quota slot (total: +2)
T4: Both enter db.runTransaction():
    - Call A writes {status:"pending", webhookSecret:"secretA"}
    - Call B writes {status:"pending", webhookSecret:"secretB"}  ← overwrites secretA
T5: Both POST to /v1/jobs → summarizer creates two jobs (jobA, jobB)
T6: jobA completes → webhook with secretA → verifySignature(secretA) fails (doc has secretB) → 401
T7: jobB completes → webhook with secretB → 204 → doc becomes completed. Correct final state.
Net: 2 quota slots burned, 2 summarizer LLM calls, 1 spurious 401 logged as error.
```

For single-tenant, this requires the operator to double-tap or a network retry to trigger, making it low-frequency but real.

**Severity:** HIGH | **Confidence:** Med (requires concurrent calls; single-tenant limits exposure but Android retry behavior can trigger it)

**Fix:**
Fold the idempotency check into the quota-reservation transaction, or make the document set conditional on no prior in-flight status:

```diff
--- a/backend/functions/src/summarizer/dispatch.ts
+++ b/backend/functions/src/summarizer/dispatch.ts
@@ -82,23 +82,29 @@ export async function dispatchSummary(...) {
-  const existing = await summaryRef.get();
-  if (existing.exists) {
-    const data = existing.data() as Partial<SummaryDocument> | undefined;
-    if (data?.status && IN_FLIGHT_STATUSES.includes(data.status)) {
-      return { summaryId: videoId };
-    }
-  }
-
-  await reserveOpenRouterQuotaSlot();
-  const webhookSecret = randomBytes(32).toString("hex");
-  await db.runTransaction(async (tx) => {
-    const snap = await tx.get(summaryRef);
-    ...
-    tx.set(summaryRef, next);
-  });
+  // Single transaction: idempotency check + pending-doc reserve.
+  // reserveOpenRouterQuotaSlot is called OUTSIDE the Firestore tx (HTTP calls
+  // can't go inside txns), but the doc write is conditional on pre-tx status.
+  let shouldDispatch = false;
+  await db.runTransaction(async (tx) => {
+    const snap = await tx.get(summaryRef);
+    if (snap.exists) {
+      const data = snap.data() as Partial<SummaryDocument> | undefined;
+      if (data?.status && IN_FLIGHT_STATUSES.includes(data.status)) {
+        shouldDispatch = false;
+        return;  // idempotent early-out inside txn
+      }
+    }
+    shouldDispatch = true;
+    const webhookSecret = randomBytes(32).toString("hex");
+    const next: SummaryDocument = { ... };
+    tx.set(summaryRef, next);
+  });
+  if (!shouldDispatch) return { summaryId: videoId };
+
+  // Quota reservation happens after the doc is conditionally locked.
+  await reserveOpenRouterQuotaSlot();
```

Note: This doesn't fully close the gap because quota reservation and POST still happen outside the transaction. However, it ensures only one call writes the doc + generates the `webhookSecret`, so the second concurrent call will see the in-flight status in its transaction and return early before burning quota.

---

### CR-4: Lock not released on `remaining <= 0` early return — CONFIRMED NOT A BUG [NIT]

**Location:** `backend/functions/src/summarizer/dispatcher.ts:57-60`

**Evidence:**
```typescript
try {
  ...
  if (remaining <= 0) {
    logger.info("summaryDispatcher: no budget remaining", budget);
    return { attempted: 0, dispatched: 0 };  // early return inside try
  }
  ...
} finally {
  await releaseDispatcherLock();  // JavaScript: finally runs even on return
}
```

**Issue:** Initially appeared to be a lock-not-released bug. Verified via Node.js runtime: JavaScript `finally` blocks execute even when the `try` block exits via `return`. The lock **is** correctly released. This is a NIT about code clarity — the early return could be misleading to readers expecting `finally` to be skipped. Consider replacing `return` with a `break`-equivalent pattern or adding a comment. No correctness defect.

**Severity:** NIT | **Confidence:** High

---

### CR-5: Summarizer `failed` webhook without `error.code` stores `errorCode: "unknown"` — no actionable detail [MED]

**Location:** `backend/functions/src/summarizer/webhook.ts:183-187`

**Evidence:**
```typescript
} else {
  updates.errorCode =
    typeof parsed.error?.code === "string" ? parsed.error.code : "unknown";
  updates.errorMessage =
    typeof parsed.error?.message === "string" ?
      parsed.error.message : undefined;
}
```

**Issue:**
The summarize-api's `url-runner.ts` delivers a failed webhook when the daemon's `error` SSE event fires. The payload structure is `{ status: "failed", error: { message: "..." } }`. The `error` object does not include a `code` field for generic runtime errors — only the `TERMINAL_PERMANENT_CODES` set (`quota_exhausted`, `unrecoverable`, `transcript_impossible`) would carry a code. For all other failures, `errorCode` is stored as `"unknown"`. The `errorMessage` is populated correctly. The Android `SummaryViewModel` shows `doc.errorMessage` in the FailedTransient state, so the user sees the message. However, the `errorCode: "unknown"` means no programmatic retry classification logic can distinguish different transient failure modes.

**Impact:** Low runtime impact (correct classification to `failed-transient`); the `"unknown"` code is never tested for by any downstream code.

**Severity:** MED | **Confidence:** High

**Fix:** Consider adding a `code` field to the error payload in `url-runner.ts` for categorizable failures (e.g., `daemon_error`, `abort_timeout`) to enable future retry-classification improvements. Not blocking.

---

### CR-6: Concurrent dispatches for same videoId: first job's webhook gets 401 [MED]

**Location:** `backend/functions/src/summarizer/dispatch.ts:104-118`

**Evidence:**
```typescript
await db.runTransaction(async (tx) => {
  const snap = await tx.get(summaryRef);
  const prior = snap.exists ? (snap.data() as Partial<SummaryDocument> | undefined) : undefined;
  const next: SummaryDocument = {
    ...
    webhookSecret,  // always uses the CURRENT call's freshly generated secret
    ...
  };
  tx.set(summaryRef, next);  // unconditional overwrite — no check on current status
});
```

**Issue:**
This is the downstream consequence of CR-3. When two concurrent calls both pass the non-transactional idempotency check, both enter `runTransaction`. The second transaction to commit will overwrite the `webhookSecret` with its own value. The first summarizer job (which received `secretA` in its original dispatch) will deliver a webhook signed with `secretA`, but the doc now holds `secretB` → `verifySignature` returns false → 401 → logged as a warning. Job 1's summary is silently lost; Job 2's summary eventually lands correctly. This is lower severity than CR-3 because the final state is correct, but the 401 log entry is misleading and quota is double-burned.

**Severity:** MED | **Confidence:** Med (same trigger conditions as CR-3 — concurrent manual calls)

**Fix:** Addressed by the CR-3 fix (moving the idempotency check inside the transaction so only one call wins the doc write).

---

### CR-7: SSE stream with `{done:true, value:undefined}` decoded as empty chunk [LOW]

**Location:** `summarizer/summarize-api/src/runners/url-runner.ts:181-187`

**Evidence:**
```typescript
reader.read().then(({ done, value }) => {
  if (done) {
    parser.reset({ consume: true });
    resolve();
    return;
  }
  parser.feed(decoder.decode(value, { stream: true }));  // value could be undefined in edge case
  read();
}, reject);
```

**Issue:**
The `ReadableStreamDefaultReader` spec allows `value` to be `undefined` when `done` is `false` (though in practice, undecoded chunked HTTP responses always provide a `Uint8Array`). The `done: true` path handles this correctly (guard at top). The `done: false` path passes `value` to `decoder.decode()` — if `value` is somehow `undefined` (non-spec-compliant transport), `TextDecoder.decode(undefined)` returns `""` (empty string), which is harmless. Low risk in practice.

**Severity:** LOW | **Confidence:** Med

---

### CR-8: `webhookUrl()` double-reads `process.env.FUNCTION_REGION` unnecessarily [LOW]

**Location:** `backend/functions/src/summarizer/dispatch.ts:43-48`

**Evidence:**
```typescript
const FUNCTION_REGION = process.env.FUNCTION_REGION ?? "us-central1";

function webhookUrl(): string {
  const region = process.env.FUNCTION_REGION ?? FUNCTION_REGION;  // reads env again, then falls back to module const
  const project = process.env.GCLOUD_PROJECT ?? process.env.GCP_PROJECT ?? "demo-playster";
  return `https://${region}-${project}.cloudfunctions.net/summaryWebhook`;
}
```

**Issue:**
The module-level `FUNCTION_REGION` constant already defaults to `"us-central1"` if `process.env.FUNCTION_REGION` is absent. Inside `webhookUrl()`, `process.env.FUNCTION_REGION ?? FUNCTION_REGION` is a redundant double-read that always equals `FUNCTION_REGION`. The URL construction is functionally correct for all cases; this is a code clarity issue. The `demo-playster` fallback for `GCP_PROJECT` means if neither env var is set in production, the webhook URL silently points to the wrong project and the webhook receiver returns 404.

**Severity:** LOW | **Confidence:** High

**Fix:** Use the module constant directly: `const region = FUNCTION_REGION;`. And ensure the deploy runbook confirms `GCLOUD_PROJECT` is set.

---

### CR-9: `releaseOpenRouterQuotaSlot` pops arbitrary entry under concurrent releases [NIT]

**Location:** `backend/functions/src/summarizer/quota.ts:135-138`

**Evidence:**
```typescript
const recentTimestamps = trimWindow(data.recentTimestamps ?? [], now);
// Drop the newest entry (we appended last). If multiple racers
// dropped at once it still trends correct over time.
if (recentTimestamps.length > 0) recentTimestamps.pop();
```

**Issue:**
After `trimWindow`, the array is ordered by insertion time (oldest first). `pop()` removes the last (newest) entry, which is assumed to be the one we reserved. Under concurrent concurrent releases (e.g., the dispatcher dispatches 5 jobs and 3 fail simultaneously), multiple calls to `releaseOpenRouterQuotaSlot` run concurrently. Each call reads the doc inside its own transaction, does a `trimWindow + pop`, and writes back. Since Firestore transactions are optimistically concurrent, the last writer wins. The net effect is that some pops are applied and some are lost, meaning the sliding window may end up with slightly more or fewer entries than expected. The implementation note acknowledges this as "trends correct over time." This is an accepted approximation, not a correctness invariant violation.

**Severity:** NIT | **Confidence:** High

---

## 4) Invariants Coverage Analysis

| Invariant | Enforcement | Status |
|-----------|-------------|--------|
| Daily quota cap (1000) | Firestore transaction with hard-cap check | ✅ Protected (atomic, verified by concurrent test) |
| Per-minute cap (20) | Sliding-window trim in same transaction | ✅ Protected |
| Webhook HMAC verification | `timingSafeEqual` with length pre-check | ✅ Correct |
| Replay window (300 s) | `Math.abs(now - sig.t) > 300` | ✅ Correct |
| `summaries/{videoId}` terminal monotonicity | Idempotency check in webhook handler | ✅ Protected (terminal-state mismatch keeps first) |
| Per-summary `webhookSecret` freshness | `randomBytes(32)` per dispatch call | ✅ Correct |
| Dispatcher no double-dispatch | Firestore lock + `maxInstances:1` | ✅ Protected for cron; ⚠️ Not for concurrent manual calls (CR-3) |
| SSE `completed` implies non-empty summary | No explicit guard on EOF-without-done | ❌ Vulnerable (CR-2) |
| `firestore.rules` allows operator reads | Hardcoded uid | ❌ Blocked by sentinel (CR-1) |

---

## 5) Edge Cases Coverage

| Edge Case | Handled? | Evidence |
|-----------|----------|----------|
| Stale webhook (replay) | ✅ Yes | `Math.abs(now - sig.t) > 300` → 401 |
| Unknown `client_job_id` | ✅ Yes | Firestore doc missing → 404 |
| Bad signature | ✅ Yes | `timingSafeEqual` failure → 401 |
| Length-mismatch signature | ✅ Yes | Length pre-check → 401, no throw |
| Dispatch 4xx | ✅ Yes | failed-permanent + quota release |
| Dispatch 5xx | ✅ Yes | failed-transient + quota release |
| Dispatch network error | ✅ Yes | failed-transient + quota release |
| Day rollover mid-run | ✅ Yes | Transaction re-reads date and resets |
| Dispatcher lock overlap | ✅ Yes | TTL 240s + `maxInstances:1` |
| Auto-enqueue idempotency | ✅ Yes | `db.getAll` pre-check per chunk |
| Auto-enqueue dedup within call | ✅ Yes | `new Set(videoIds)` |
| SSE stream EOF without `done` | ❌ No | Treated as successful completion (CR-2) |
| Concurrent manual dispatch same videoId | ⚠️ Partial | Correct final state but double quota burn (CR-3) |
| Empty `webhookSecret` on queued doc | ✅ Yes | `verifySignature` guards `!secret` → false |
| Firestore rules with sentinel uid | ❌ Deployment gate | Not a code bug; documented bootstrap step (CR-1) |

---

## 6) Error Handling Assessment

**Good practices:**
- All three dispatch error paths (network, 4xx, 5xx) correctly release quota and return `summaryId` rather than throwing — avoids Android retry storms.
- Webhook handler returns typed HTTP status codes (400/401/404/204) without ever leaking internal error details.
- `autoEnqueueSafe` wraps `enqueueAutoSummary` in try/catch so a failing auto-enqueue never fails the sync.
- `releaseDispatcherLock` is wrapped in try/catch (best-effort).
- `entrypoint.js` exits process on daemon crash (correct Cloud Run behavior).

**Gaps:**
- SSE stream EOF → success (CR-2): should be an error path.
- Concurrent dispatch → double quota burn (CR-3): only partial error recovery.
- `updateJobStatus` in `url-runner.ts` is synchronous SQLite — if the DB is corrupt or locked, the unhandled exception escapes the SSE Promise chain, propagates to `runUrlJob`, and the catch block at line 202 handles it correctly. Low risk.

---

## 7) Concurrency & Race Conditions

| Surface | Assessment |
|---------|------------|
| Quota transaction | ✅ Atomic — N=10 concurrent test confirms hard cap |
| Dispatcher lock | ✅ Transactional acquire with TTL |
| Auto-enqueue vs dispatch | ✅ Auto-enqueue writes `queued`; dispatch idempotency check returns early on `queued` |
| Concurrent manual dispatch (same videoId) | ⚠️ Non-atomic idempotency check (CR-3) |
| Webhook idempotency | ✅ Terminal-state mismatch → 200 first-wins |
| `releaseOpenRouterQuotaSlot` concurrent | ⚠️ Pop may remove wrong entry (NIT, accepted) |

---

## 8) Summary

| | Count |
|---|---|
| Total findings | 9 |
| BLOCKER | 1 |
| HIGH | 2 |
| MED | 3 |
| LOW | 2 |
| NIT | 1 |

**Status: Blockers Found**

The HMAC signing and verification, quota transaction atomicity, replay window enforcement, dispatcher lock, and state machine transitions are all implemented correctly. The three issues that should be addressed before production load are:

1. **CR-1 (BLOCKER):** Deploy Firestore rules with the real operator uid — this is an operator action, not a code change. The branch can merge once this gate is documented as a required post-merge step.

2. **CR-2 (HIGH):** Guard the SSE EOF path in `url-runner.ts` so a stream that closes without a `done`/`error` event is treated as a transient failure, not a successful completion.

3. **CR-3 (HIGH):** Move the idempotency check inside the doc-reserve transaction so concurrent manual calls cannot both pass the pre-flight check and burn double quota.

CR-4 through CR-9 are not blocking. CR-6 is resolved by fixing CR-3.

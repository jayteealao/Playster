---
schema: sdlc/v1
type: review-command
slug: wire-android-backend-summarizer
review-scope: slug-wide
slice-slug: ""
review-command: testing
status: complete
updated-at: "2026-05-21T00:00:00Z"
metric-findings-total: 12
metric-findings-blocker: 0
metric-findings-high: 3
metric-findings-med: 5
metric-findings-low: 3
metric-findings-nit: 1
result: Ship with caveats
refs:
  review-master: 07-review.md
---

# Review: testing

**Verdict:** Ship with caveats
**Reviewed:** diff / git diff main...HEAD (232 files, +16 714 / -1 212)
**Slug:** wire-android-backend-summarizer

---

## Findings

| ID | Severity | Confidence | Category | Location | Issue |
|----|----------|------------|----------|----------|-------|
| TST-1 | HIGH | High | Coverage gap | `backend/functions/tests/callable.test.ts` | `requestVideoSummary` callable never exercised through the auth wrapper with a real dispatchSummary; only syncAllPlaylists is wrapped-and-tested end-to-end |
| TST-2 | HIGH | High | Coverage gap | `backend/functions/src/summarizer/dispatcher.ts:LOCK_TTL_MS` | No test for lock-TTL expiry path — stale lock held longer than 240 s is not reclaimed in any test case |
| TST-3 | HIGH | High | Coverage gap — deferred (noted) | *(no source files)* | `summarySweeper` (AC-12) and `summaryRetryCron` (AC-13) crons are not implemented; AC-12/AC-13 have no automated coverage at all — noted per slice strategy, but impacts verdict |
| TST-4 | MED | High | Coverage gap | `backend/functions/tests/dispatch.test.ts` | `dispatchSummary` when called while `quota/openrouter` is at the daily cap is never tested — the callable path from `requestVideoSummary` should throw `resource-exhausted` to the caller, but only `quota.test.ts` exercises this, not the full dispatch chain |
| TST-5 | MED | High | Coverage gap | `android/.../SummaryScreenComposeTest.kt` | `SummaryUiState.NoSummary` render state is never rendered in the compose test; the "no prior summary" entry state is the cold-start path |
| TST-6 | MED | High | Missing failure-mode test | `backend/functions/tests/webhook.test.ts` | No test for the terminal-state mismatch case where the doc is already `completed` but the arriving webhook says `failed` (the code returns `200 already-terminal` but no test asserts this branch) |
| TST-7 | MED | Med | Brittle mock / wrong level | `backend/functions/tests/dispatcher.test.ts:drainSummaryQueue respects per-minute cap` | Test monkey-patches `globalThis.fetch` to sidestep `dispatchSummary`'s own fetchImpl injection — fragile if the internal call chain changes; should inject fetchImpl through the drain path instead |
| TST-8 | MED | High | Missing concurrency test | `backend/functions/tests/quota.test.ts` | `releaseOpenRouterQuotaSlot` is only tested single-threaded; no concurrent reserve+release race test (N dispatches, some fail mid-flight) — the pop()-from-trimmed-array logic in release is subtly racy under concurrency |
| TST-9 | LOW | High | Missing edge-case | `backend/functions/tests/autoEnqueue.test.ts` | `enqueueAutoSummary` is not tested with a very large batch (e.g. 500 videoIds hitting Firestore batched-write limits of 500); Firestore batch max is exactly 500, and the function does not chunk |
| TST-10 | LOW | Med | Missing failure-mode test | `summarizer/summarize-api/tests/url-runner-webhook.test.ts` | No test for the case where the webhook receiver returns `401` — the retry logic skips non-retryable 4xx, so the job should stay failed without retry, but this exact scenario is not exercised |
| TST-11 | LOW | Med | Assertion under-specification | `backend/functions/tests/dispatcher.test.ts:drainSummaryQueue respects per-minute cap` | `expect(result.dispatched).toBeLessThanOrEqual(2)` — allows dispatched=0, which would pass even if the drain never fires; should assert `toBe(2)` (exact remaining budget) |
| TST-12 | NIT | High | Missing compose-test state | `android/.../SummaryScreenComposeTest.kt` | Retry button callback (`onRetry`) is never exercised to confirm it fires; test only asserts the button exists, not that tapping it triggers the handler |

---

## Detailed Findings

### TST-1: `requestVideoSummary` callable not tested through auth wrapper [HIGH]

**Location:** `backend/functions/tests/callable.test.ts` (all lines), `backend/functions/tests/dispatch.test.ts` (all lines)

**Untested behavior:**
The `requestVideoSummary` callable exported from `backend/functions/src/summarizer/dispatch.ts` is wrapped with `allowlistedCall`. The `callable.test.ts` suite tests `syncAllPlaylists` through `functionsTest.wrap` (verifying the auth gate), but there is no analogous test that exercises `requestVideoSummary` through `functionsTest.wrap`. The dispatch inner function is exercised directly in `dispatch.test.ts`, but the full path — auth gate → dispatch → quota → Firestore — is never exercised as a single callable invocation.

**Scenarios not tested:**
1. Unauthenticated call to `requestVideoSummary` → should throw `unauthenticated`
2. Stranger uid call → should throw `permission-denied`
3. Allowlisted call → should dispatch and return `{ summaryId }`
4. Allowlisted call while quota is exhausted → callable should surface `resource-exhausted` to the caller

**Suggested test:**
```typescript
// backend/functions/tests/callable.test.ts — extend existing suite
import { requestVideoSummary } from "../src/index.js";

describe("requestVideoSummary callable — auth gating", () => {
  it("rejects unauthenticated calls", async () => {
    const wrapped = functionsTest.wrap(mod.requestVideoSummary);
    await expect(
      wrapped({ data: { videoId: "v1" }, auth: undefined } as never),
    ).rejects.toMatchObject({ code: "unauthenticated" });
  });

  it("rejects stranger uid", async () => {
    const wrapped = functionsTest.wrap(mod.requestVideoSummary);
    await expect(
      wrapped({ data: { videoId: "v1" }, auth: { uid: "stranger" } } as never),
    ).rejects.toMatchObject({ code: "permission-denied" });
  });
});
```

**Severity:** HIGH | **Confidence:** High

---

### TST-2: Dispatcher lock TTL expiry not tested [HIGH]

**Location:** `backend/functions/src/summarizer/dispatcher.ts:24` (`LOCK_TTL_MS = 240_000`), `backend/functions/tests/dispatcher.test.ts`

**Untested behavior:**
`acquireDispatcherLock` uses a 240 000 ms TTL: if `now - acquiredAt < LOCK_TTL_MS`, return `false` (lock still held). The existing test only exercises the happy acquire → false on overlap → release → re-acquire cycle. It never sets a stale lock (`acquiredAt = Date.now() - 241_000`) and verifies that a subsequent `acquireDispatcherLock()` still returns `true` (reclaims the expired lock).

**Consequence:** The TTL reclaim path — which is the primary defense against a crashed cron leaving the dispatcher locked forever — has zero test coverage.

**Suggested test:**
```typescript
it("reclaims a lock whose TTL has expired", async () => {
  const { acquireDispatcherLock } = await import("../src/summarizer/dispatcher.js");
  // Manually seed a stale lock doc.
  await admin.firestore().doc("locks/summaryDispatcher").set({
    acquiredAt: Date.now() - 241_000, // older than LOCK_TTL_MS
    ttlMs: 240_000,
  });
  // Should acquire successfully (stale lock is reclaimed).
  expect(await acquireDispatcherLock()).toBe(true);
});
```

**Severity:** HIGH | **Confidence:** High

---

### TST-3: AC-12 (summarySweeper) and AC-13 (summaryRetryCron) have no implementation or tests [HIGH — deferred per slice strategy]

**Location:** `backend/functions/src/summarizer/` (no `sweeper.ts` or `retry.ts`)

**Context:** Per `03-slice-summary-orchestration.md`:
> `summarySweeper` cron (AC-12 stuck-job recovery) → **deferred to v1.1**.
> `summaryRetryCron` (AC-13 daily retry of failed-transient) → **deferred to v1.1**.

Neither file exists. AC-12 and AC-13 are listed in the shape's Definition of Done as requiring automated emulator verification. The shape's DoD states "All 16 ACs verified via their stated method." AC-12 and AC-13 are currently unverifiable.

**Impact:** Stuck jobs (`status=running` for >1h) never automatically recover. Failed-transient summaries require manual user Retry forever. These are the two most important resilience mechanisms in the system.

**Noted per instructions — not re-flagging as a blocker.** Flagging HIGH because the AC-to-test mapping is incomplete and the DoD is not satisfied for these two ACs. The user-observable risk is that `status=running` docs can accumulate indefinitely post-ship.

**Severity:** HIGH (deferred — do not treat as gate) | **Confidence:** High

---

### TST-4: `dispatchSummary` + quota-exhausted interaction not tested end-to-end [MED]

**Location:** `backend/functions/tests/dispatch.test.ts`

**Untested behavior:**
`dispatch.test.ts` seeds a fresh quota doc for every test (via `clearFirestore`), so `reserveOpenRouterQuotaSlot` always succeeds. There is no test where `dispatchSummary` is called with the `quota/openrouter` doc already at the daily cap (requestCount=1000). In this scenario the function should propagate the `HttpsError("resource-exhausted")` from quota up to the callable caller. Without this test, if a bug silently swallows the quota error and dispatches anyway, it would not be caught.

**Suggested test:**
```typescript
it("throws resource-exhausted when daily cap is at limit", async () => {
  const { dispatchSummary } = await import("../src/summarizer/dispatch.js");
  const today = new Date().toISOString().slice(0, 10);
  await admin.firestore().doc("quota/openrouter").set({
    date: today,
    requestCount: 1000,
    dailyLimit: 1000,
    perMinuteLimit: 20,
    recentTimestamps: [],
  });
  await expect(
    dispatchSummary(VIDEO_ID, "free", { fetchImpl: stubbedFetch(200) }),
  ).rejects.toMatchObject({ code: "resource-exhausted" });
  // Doc should remain at failed-permanent (quota exhausted = permanent error)
  const doc = await admin.firestore().doc(`summaries/${VIDEO_ID}`).get();
  // No doc written — quota gate throws before any write
  expect(doc.exists).toBe(false);
});
```

**Severity:** MED | **Confidence:** High

---

### TST-5: `SummaryUiState.NoSummary` render state not exercised in compose test [MED]

**Location:** `android/app/src/androidTest/.../SummaryScreenComposeTest.kt`

**Untested behavior:**
`SummaryScreenComposeTest` covers `InProgress`, `Completed`, `FailedTransient`, and `FailedPermanent`. The fifth state, `NoSummary` (the initial state when the screen opens with no prior summary), is never rendered in any test. This is the cold-start path every new user hits first, and it renders the "Summarize" CTA button. If its `testTag` (`summarize-button-enabled`) is accidentally removed or renamed, no test would catch it.

**Suggested test:**
```kotlin
@Test
fun noSummary_rendersSummarizeButton() {
    composeTestRule.setContent {
        SummaryScreenContent(
            state = SummaryUiState.NoSummary,
            onRetry = {},
            onSummarize = {},
        )
    }
    composeTestRule.onNodeWithTag("summarize-button-enabled").assertIsDisplayed()
}
```

**Severity:** MED | **Confidence:** High

---

### TST-6: Webhook terminal-state mismatch branch not tested [MED]

**Location:** `backend/functions/src/summarizer/webhook.ts:179-188`, `backend/functions/tests/webhook.test.ts`

**Untested code path:**
```typescript
// webhook.ts lines ~179-188
if (currentStatus === inboundTerminal) {
  return { status: 204, body: "" }; // tested by "idempotent replay"
}
logger.warn("summaryWebhook: terminal-state mismatch — keeping first", { ... });
return { status: 200, body: "already-terminal" }; // NOT TESTED
```

The mismatch branch — where an already-`completed` doc receives a subsequent `failed` webhook — returns `200` and preserves the first status. No test exercises this scenario. This is a real production scenario: a transient network failure causes the summarizer to retry a `completed` webhook delivery as `failed`.

**Suggested test:**
```typescript
it("terminal-state mismatch: completed doc + failed webhook → 200, content preserved", async () => {
  const { processSummaryWebhook } = await import("../src/summarizer/webhook.js");
  await admin.firestore().doc(`summaries/${VIDEO_ID}`).set({
    videoId: VIDEO_ID,
    status: "completed",
    content: "original content",
    webhookSecret: SECRET,
  });
  const payload = {
    client_job_id: VIDEO_ID,
    status: "failed",
    error: { code: "openrouter_timeout", message: "late failure" },
  };
  const { header, rawBody } = signWebhook(payload, SECRET);
  const result = await processSummaryWebhook({ signatureHeader: header, rawBody: Buffer.from(rawBody) });
  expect(result.status).toBe(200);
  const doc = await admin.firestore().doc(`summaries/${VIDEO_ID}`).get();
  expect(doc.data()?.status).toBe("completed");
  expect(doc.data()?.content).toBe("original content");
});
```

**Severity:** MED | **Confidence:** High

---

### TST-7: `drainSummaryQueue` monkey-patches `globalThis.fetch` — brittle mock [MED]

**Location:** `backend/functions/tests/dispatcher.test.ts:54-72`

**Brittle test code:**
```typescript
const realFetch = globalThis.fetch;
(globalThis as { fetch: typeof fetch }).fetch = fetchImpl;
try {
  // ... seeded quota, then drain ...
  const result = await drainSummaryQueue();
} finally {
  (globalThis as { fetch: typeof fetch }).fetch = realFetch;
}
```

`dispatchSummary` accepts `opts.fetchImpl` as an injection point precisely to avoid this pattern. The dispatcher calls `dispatchSummary(videoId, model)` without passing `fetchImpl`, which falls through to `globalThis.fetch`. This works today but will silently break if `drainSummaryQueue` is refactored to pass a fetchImpl option, or if tests run in parallel and the global is clobbered.

**Better approach:** Either thread a `fetchImpl` parameter through `drainSummaryQueue` → `dispatchSummary`, or spy on the module's internal `fetch` reference. The test currently validates dispatch count by counting `spy.mock.calls.length` — this side-effect assertion is correct, but the injection mechanism is fragile.

**Severity:** MED | **Confidence:** Med

---

### TST-8: `releaseOpenRouterQuotaSlot` concurrent reserve+release race not tested [MED]

**Location:** `backend/functions/tests/quota.test.ts`

**Untested behavior:**
`releaseOpenRouterQuotaSlot` uses `recentTimestamps.pop()` to drop the newest entry — an approximation that is reasonable under low concurrency but incorrect if two concurrent releases both trim and pop the same slot. The N=10 concurrent `reserveOpenRouterQuotaSlot` test verifies the hard cap, but there is no test that fires N concurrent reserve → dispatch failure → release cycles and verifies the final `requestCount` is correct (0 if all dispatches fail).

**Suggested test:**
```typescript
it("N concurrent reserve+release pairs leave requestCount at 0", async () => {
  const { reserveOpenRouterQuotaSlot, releaseOpenRouterQuotaSlot } =
    await import("../src/summarizer/quota.js");
  const N = 5;
  await Promise.all(
    Array.from({ length: N }, async () => {
      await reserveOpenRouterQuotaSlot();
      await releaseOpenRouterQuotaSlot();
    }),
  );
  const data = (await admin.firestore().doc("quota/openrouter").get()).data();
  // Best-effort release — allow off-by-one tolerance.
  expect(data?.requestCount).toBeLessThanOrEqual(1);
});
```

**Severity:** MED | **Confidence:** Med

---

### TST-9: `enqueueAutoSummary` not tested at Firestore batch write limit (500) [LOW]

**Location:** `backend/functions/src/summarizer/autoEnqueue.ts`, `backend/functions/tests/autoEnqueue.test.ts`

**Untested behavior:**
The current `enqueueAutoSummary` implementation does not chunk batches. Firestore batch operations have a hard limit of 500 writes per commit. A first-sync with 500+ videos would throw `RESOURCE_EXHAUSTED` from Firestore. The test suite only covers batches of ≤5 videoIds.

**Note:** This is an implementation gap that testing exposes; the test should both document the limit and, once fixed, protect against regression.

**Severity:** LOW | **Confidence:** High

---

### TST-10: Webhook `401` response from backend not tested in url-runner-webhook [LOW]

**Location:** `summarizer/summarize-api/tests/url-runner-webhook.test.ts`

**Untested behavior:**
`deliverWebhook` in `summarize-api` is well-tested for `200`, `503` (retry), `408`/`429` (retry), and `401` non-retry cases via `webhook-deliver.test.ts`. However the integration test `url-runner-webhook.test.ts` never seeds the webhook receiver to return `401`. The non-retryable path in the integration harness is unexercised end-to-end. If `deliverWebhook`'s non-retry list is accidentally widened, the integration test would not catch it.

**Severity:** LOW | **Confidence:** Med

---

### TST-11: Dispatcher drain assertion allows `dispatched=0` [LOW]

**Location:** `backend/functions/tests/dispatcher.test.ts:75-76`

**Under-specified assertion:**
```typescript
expect(result.dispatched).toBeLessThanOrEqual(2);
expect(spy.mock.calls.length).toBeLessThanOrEqual(2);
```

With 18 slots used and perMinuteLimit=20, the remaining budget is exactly 2. The assertion allows 0 or 1 dispatches to pass the test even if the drain is not dispatching at capacity. Should be `toBe(2)` (with `toBeGreaterThan(0)` as a minimum floor) to catch under-dispatching regressions.

**Severity:** LOW | **Confidence:** High

---

### TST-12: Compose test does not verify retry-button callback fires [NIT]

**Location:** `android/app/src/androidTest/.../SummaryScreenComposeTest.kt:failedTransient_rendersRetryButton`

**Issue:**
```kotlin
composeTestRule.onNodeWithTag("summary-retry-button").assertIsDisplayed()
// onRetry callback is never invoked or verified
```

The test confirms the button exists but never calls `composeTestRule.onNodeWithTag("summary-retry-button").performClick()` and verifies that `onRetry` was invoked. A broken button click handler would not be caught.

**Severity:** NIT | **Confidence:** High

---

## Summary

The branch ships strong test coverage on its core happy-path and error-path scenarios across the backend orchestration layer and summarizer service. The emulator-backed Vitest suite for quota (including the N=10 concurrency test), webhook verification (seven cases), dispatcher lock acquire/release, and auto-enqueue idempotency are genuinely good integration tests. The summarize-api webhook delivery suite and signer fixture cross-checking are also solid.

**Three HIGH findings require action before treating the DoD as satisfied:**

1. **TST-1** (`requestVideoSummary` not tested through the auth wrapper) — straightforward 3-case addition to `callable.test.ts`.
2. **TST-2** (dispatcher lock TTL expiry untested) — one new emulator test with a manually seeded stale lock doc.
3. **TST-3** (AC-12 sweeper + AC-13 retry cron deferred) — explicitly tracked by the slice strategy; noted here to align with the shape's DoD requirement.

The five MED findings are genuine gaps in failure-mode coverage: quota-exhausted in the dispatch chain, the `NoSummary` compose state, the terminal-state mismatch webhook branch, the brittle globalThis.fetch monkey-patch in the dispatcher test, and the concurrent reserve+release correctness gap. None of these individually block shipping, but collectively they represent a real regression risk surface.

**Verdict:** Ship with caveats — TST-1 and TST-2 should be closed before the branch lands; TST-3 is acknowledged deferred. MED findings are recommended follow-ups.

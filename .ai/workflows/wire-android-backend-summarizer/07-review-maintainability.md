---
review-command: maintainability
slug: wire-android-backend-summarizer
date: 2026-05-20
scope: diff
target: git diff main...HEAD
paths: "(all changed files; focused on backend/functions/src/summarizer/, summarizer/summarize-api/src/, android/ summary+quota surfaces)"
related:
  shape: 02-shape.md
  slice: 03-slice.md
  implement-auth: 05-implement-auth-and-android-firebase.md
  implement-summarizer: 05-implement-summarizer-container.md
  implement-orchestration: 05-implement-summary-orchestration.md
  implement-ui: 05-implement-summary-ui.md
id-prefix: MNT
---

# Review: maintainability

**Reviewed:** diff / git diff main...HEAD  
**Date:** 2026-05-20  
**Files:** 232 changed, +16 714 −1 212  
**Reviewer:** Claude Code

---

## 0) Scope, Intent, and Conventions

**What was reviewed:**  
The complete branch diff adding the summarizer pipeline — Firebase Functions backend orchestration, summarize-api gateway extensions (webhook signing, delivery, DB schema migrations), Android summary and quota UI.

**Intent (from shape doc):**  
Wire Android → Firebase Auth → callable → quota → summarizer Cloud Run → webhook → Firestore → listener. Single-tenant, operator-only, zero LLM cost at steady state. Stripe-style HMAC webhook. Cron-driven dispatcher/sweeper/retry pattern.

**Conventions observed:**  
- TypeScript backend: named exports per module, `firebase-functions/v2` patterns, JSDoc on exported functions.  
- Kotlin Android: Hilt DI, `StateFlow`/`collectAsStateWithLifecycle`, sealed-interface state machines, `@Singleton` repositories.  
- Test-injection via optional parameters (`fetchImpl`, `sleepImpl`, `nowSeconds`).

**Review focus (maintainability rubric):**  
Cohesion, coupling, complexity, naming, documentation, magic constants, change amplification.

---

## 1) Executive Summary

**Merge Recommendation:** APPROVE_WITH_COMMENTS

The implementation is structurally sound. Module responsibilities are generally well-bounded, the HMAC contract is clearly documented in signer.ts, and the Android state machine is cleanly typed. The main maintainability concerns are (a) a cluster of undocumented magic constants across several modules that will confuse a future maintainer, (b) one genuine mixed-concerns spot in `dispatch.ts` where the "exists?" idempotency check mixes with the "quota pre-flight" logic in a way that makes the invariant non-obvious, (c) duplicated hardcoded defaults between backend TypeScript and Android Kotlin, and (d) the `__webhookTestOverrides` mutable global in `url-runner.ts` which is a hidden coupling smell.

No BLOCKER findings. Three HIGH, four MED, four LOW/NIT.

**Top Maintainability Issues:**  
1. **MNT-01** (HIGH): Magic quota constants (`1000`, `20`, `60_000`, `300`, `240_000`, `200`) scattered across at least five files with no shared source of truth. Adding a second quota tier or changing the replay window requires hunting all occurrences.  
2. **MNT-02** (HIGH): `dispatch.ts:88-98` — `IN_FLIGHT_STATUSES` name is misleading; `completed` is included, meaning "already finished" collapses silently into the idempotency return instead of re-dispatching, which is a non-obvious design invariant with no comment explaining why `completed` is in that set.  
3. **MNT-03** (HIGH): `url-runner.ts:32-35` — mutable module-level `__webhookTestOverrides` object is a test backdoor through hidden global state. If two tests run in the same process (e.g., Vitest with `--threads`) they will race on this object.  

**Overall Assessment:**  
- Cohesion: Good (most modules do one thing; one mixing spot in `dispatch.ts`)  
- Coupling: Acceptable (clear layering; one hidden global)  
- Complexity: Manageable (no function exceeds ~90 lines; SSE loop in `url-runner.ts` is the most complex block)  
- Consistency: Good (naming conventions followed throughout)  
- Change Amplification: Moderate (quota constants and status strings duplicated; adding a new status requires changes in 4+ files)

---

## 2) Module Structure Analysis

| Module | Lines | Responsibilities | Cohesion | Dependencies | Verdict |
|--------|-------|------------------|----------|--------------|---------|
| `backend/functions/src/auth/verify.ts` | 61 | Allowlist check + `onCall` wrapper | ✅ Focused | 2 | Good |
| `backend/functions/src/models/index.ts` | 67 | Firestore document shapes | ✅ Focused | 1 | Good |
| `backend/functions/src/summarizer/secrets.ts` | 6 | Secret param definitions | ✅ Focused | 1 | Good |
| `backend/functions/src/summarizer/quota.ts` | 157 | Transactional quota read/write | ✅ Focused | 2 | Good |
| `backend/functions/src/summarizer/dispatch.ts` | 222 | Idempotency + quota pre-flight + HTTP dispatch + doc lifecycle | ⚠️ 3 concerns | 6 | See MNT-02 |
| `backend/functions/src/summarizer/webhook.ts` | 224 | Signature verify + doc update | ✅ Focused | 3 | Good |
| `backend/functions/src/summarizer/dispatcher.ts` | 110 | Cron drain + lock | ✅ Focused | 4 | Good |
| `backend/functions/src/summarizer/autoEnqueue.ts` | 70 | Batch queued-doc creation | ✅ Focused | 2 | Good |
| `summarizer/summarize-api/src/webhooks/signer.ts` | 26 | HMAC header builder | ✅ Focused | 1 | Good |
| `summarizer/summarize-api/src/webhooks/deliver.ts` | 70 | Retry-wrapped HTTP delivery | ✅ Focused | 2 | Good |
| `summarizer/summarize-api/src/runners/url-runner.ts` | 279 | SSE consumption + webhook delivery + job status | ⚠️ Long | 5 | See MNT-04 |
| `summarizer/summarize-api/src/schemas.ts` | 37 | Zod input schemas | ✅ Focused | 1 | Good |
| `summarizer/summarize-api/src/routes/jobs.ts` | 221 | HTTP routing (url/rss/upload) | ⚠️ 3 job types | 7 | Acceptable |
| `android/.../SummaryUiState.kt` | 14 | Five-state sealed interface | ✅ Focused | 0 | Excellent |
| `android/.../SummaryViewModel.kt` | 122 | State machine over Firestore + callable | ✅ Focused | 4 | Good |
| `android/.../SummaryDoc.kt` | 65 | Firestore DTO + mapper | ✅ Focused | 2 | Good |
| `android/.../QuotaDoc.kt` | 57 | Quota DTO + state derivation | ✅ Focused | 1 | Good |
| `android/.../QuotaBanner.kt` | 97 | Composable + embedded ViewModel | ⚠️ Minor mixing | 3 | See MNT-07 |
| `android/.../FirestoreRepository.kt` | 130 | Three Firestore listeners | ⚠️ Three repos in one file | 2 | See MNT-08 |
| `android/.../FirebaseAuthBridge.kt` | 60 | Google→Firebase sign-in bridge | ✅ Focused | 2 | Good |

---

## 3) Coupling Analysis

### Dependency Graph (backend)

```
Cloud Scheduler
    │
    ▼
summaryDispatcher.ts  ───────────────┐
    │ (via dispatchSummary)           │
    ▼                                 │
dispatch.ts  ←── requestVideoSummary callable
    │   │
    │   ├── quota.ts       (transactional slot)
    │   ├── secrets.ts     (param values)
    │   └── models/index.ts (types)
    │
    ▼
Summarizer Cloud Run HTTP
    │
    ▼ (webhook)
webhook.ts  ─── models/index.ts
    │
    ▼
Firestore summaries/{videoId}
```

**Cross-layer violations:** None found. The dependency direction (cron → dispatch → quota/secrets/models → Firestore) is correctly one-way.

**Circular dependencies:** None found.

**Hidden coupling (MNT-03):** `__webhookTestOverrides` in `url-runner.ts` is a mutable module-level global. The production path never sets it, but any test that sets it leaves state visible to subsequent tests in the same worker.

### Dependency Graph (Android)

```
SummaryScreen → SummaryViewModel → SummaryRepository (Firestore)
                               └── SummaryFunctions (callable)
QuotaBanner   → QuotaBannerViewModel → QuotaRepository (Firestore)
```

No cross-layer leaks observed. The Android layer correctly depends on the data layer, not on Firebase SDK internals (the repository pattern is respected).

---

## 4) Findings Table

| ID | Severity | Confidence | Category | File:Line | Issue |
|----|----------|------------|----------|-----------|-------|
| MNT-01 | HIGH | High | Naming / Magic Constants | Multiple files | Quota limits, replay window, lock TTL, chunk size as bare numbers |
| MNT-02 | HIGH | High | Cohesion / Naming | `dispatch.ts:18-28,88-98` | `IN_FLIGHT_STATUSES` includes `completed` — intent not documented; misleading name |
| MNT-03 | HIGH | High | Coupling / Hidden Global | `url-runner.ts:32-35` | Mutable module-level test-override object |
| MNT-04 | MED | High | Complexity | `url-runner.ts:101-189` | SSE promise callback is 88 lines; hard to follow |
| MNT-05 | MED | High | Duplication | `quota.ts` vs `QuotaDoc.kt` | Quota defaults duplicated across TypeScript and Kotlin |
| MNT-06 | MED | High | Naming | `dispatch.ts:39-41` | `DispatchSummaryOptions` with single field `fetchImpl` — unnecessary wrapper type |
| MNT-07 | MED | Med | Cohesion | `QuotaBanner.kt:31-39` | ViewModel defined inline in the Composable file |
| MNT-08 | LOW | High | Cohesion | `FirestoreRepository.kt` | Three `@Singleton` repositories (`Firestore`, `Summary`, `Quota`) colocated in one file |
| MNT-09 | LOW | Med | Naming | `dispatch.ts:43-48` | `webhookUrl()` function mutates nothing but has a side effect of reading env — function vs. val not obvious |
| MNT-10 | LOW | High | Naming | `SummaryScreen.kt:32-33` | `onRetry` and `onSummarize` both resolve to `viewModel::retry` — conflated callbacks |
| MNT-11 | NIT | High | Documentation | `dispatcher.ts:10-11` | Lock TTL constant undocumented; why 4 min for a 5-min schedule? |
| MNT-12 | NIT | Med | Documentation | `autoEnqueue.ts:10` | `CHUNK_SIZE = 200` — undocumented; reference to Firestore `getAll` batch limit not mentioned |

**Findings Summary:**  
- BLOCKER: 0  
- HIGH: 3  
- MED: 4  
- LOW: 3  
- NIT: 2  

---

## 5) Detailed Findings

---

### MNT-01: Magic Constants — Quota Limits, Replay Window, Lock TTL [HIGH]

**Severity:** HIGH | **Confidence:** High | **Category:** Naming / Magic Constants

**Locations:**

```
backend/functions/src/summarizer/quota.ts:7-9
  const DEFAULT_DAILY_LIMIT = 1000;
  const DEFAULT_PER_MINUTE_LIMIT = 20;
  const WINDOW_MS = 60_000;

backend/functions/src/summarizer/webhook.ts:12
  const REPLAY_WINDOW_SECONDS = 300;

backend/functions/src/summarizer/dispatcher.ts:11
  const LOCK_TTL_MS = 240_000;

backend/functions/src/summarizer/autoEnqueue.ts:10
  const CHUNK_SIZE = 200;

android/app/src/main/java/.../QuotaDoc.kt:13-14
  val dailyLimit: Long = 1000L,
  val perMinuteLimit: Long = 20L,

android/app/src/main/java/.../QuotaDoc.kt:34
  val windowStart = nowMillis - 60_000L
```

**Issue:**  
The quota cap (`1000`/day, `20`/min, 60-second window) is defined independently in the TypeScript backend and then duplicated as Kotlin fallback defaults. The `300` replay window appears only in `webhook.ts`. The `240_000` lock TTL appears only in `dispatcher.ts`. None of these are linked by comment to the source-of-truth or to each other.

**Impact:**  
- **Change amplification:** Changing the daily cap from 1000 to 2000 (e.g., on a second OpenRouter credit purchase) requires editing at least `quota.ts` (backend) and `QuotaDoc.kt` (Android). The Android default is a fallback for a doc that doesn't exist yet — it will silently read `Healthy` state for a new deployment if the Firestore doc uses the new value but the app wasn't updated.  
- **Readability:** A new maintainer reading `quota.ts` has no hint that `1000` also appears in Android code.

**Change scenario:**  
> Operator buys a second $10 credit. What constants change?  
> Answer: `DEFAULT_DAILY_LIMIT` in `quota.ts` (backend) and the fallback `1000L` in `QuotaDoc.kt`. Also the banner copy "Daily summary limit reached" may need a footnote about the new cap. Two modules, one is in a different language.

**Smallest Fix:**  
Add cross-reference comments linking the Android defaults to the Firestore doc as the actual source of truth:

```kotlin
// QuotaDoc.kt — Kotlin fallback defaults are only active before the first
// Firestore write. The backend writes `dailyLimit`/`perMinuteLimit` into
// `quota/openrouter` on first reservation; these values are the actual caps.
// See backend/functions/src/summarizer/quota.ts DEFAULT_DAILY_LIMIT.
val dailyLimit: Long = 1000L,
val perMinuteLimit: Long = 20L,
```

And in `webhook.ts`:
```typescript
// REPLAY_WINDOW_SECONDS: must match the signer's timestamp tolerance. Stripe
// recommends 300s (5 minutes). Change both signer and verifier together.
const REPLAY_WINDOW_SECONDS = 300;
```

**Larger Fix (optional):**  
Extract all quota constants to `backend/functions/src/summarizer/quotaConstants.ts` and import everywhere on the backend side. The Android side is harder (different language); document it clearly instead.

---

### MNT-02: Misleading `IN_FLIGHT_STATUSES` Name — `completed` Silently Included [HIGH]

**Severity:** HIGH | **Confidence:** High | **Category:** Cohesion / Naming

**Location:** `backend/functions/src/summarizer/dispatch.ts:18-28, 88-98`

```typescript
// Line 18-28
const IN_FLIGHT_STATUSES: ReadonlyArray<SummaryDocument["status"]> = [
  "queued",
  "pending",
  "running",
  "completed",     // ← completed is NOT in-flight — name is a lie
];

// Line 88-98
const existing = await summaryRef.get();
if (existing.exists) {
  const data = existing.data() as Partial<SummaryDocument> | undefined;
  if (data?.status && IN_FLIGHT_STATUSES.includes(data.status)) {
    return { summaryId: videoId };   // ← silently returns for completed docs
  }
}
```

**Issue:**  
The name `IN_FLIGHT_STATUSES` suggests the set of statuses representing active work. Including `completed` is intentional (per the idempotency policy: a completed summary is not re-dispatched), but the name contradicts the intent. The comment on `dispatchSummary` says "Failed docs fall through to a fresh attempt" which is correct, but does not explain why `completed` is collapsed into the same early-return branch rather than having its own comment.

**Impact:**  
- **Readability:** A maintainer adding a new status (e.g., `cancelled`) must decide which set to add it to. The misleading name makes this non-obvious.  
- **Future bug risk:** If the set is ever refactored (e.g., "no, we should allow re-dispatch for completed"), the name gives no guidance.

**Change scenario:**  
> Add a "force re-summarize" feature. Should it bypass this check?  
> Answer: Unclear from the name alone. A developer must read the inline comment carefully to realize `completed` was intentionally included.

**Smallest Fix:**  
Rename to `NON_REDISPATCH_STATUSES` and add a comment:

```typescript
// Statuses for which a duplicate requestVideoSummary call is a no-op.
// `completed` is included because an existing completed summary should not
// be re-dispatched by the manual callable; the force-re-summarize feature
// (out of scope for v1) would bypass this check explicitly.
const NON_REDISPATCH_STATUSES: ReadonlyArray<SummaryDocument["status"]> = [
  "queued",
  "pending",
  "running",
  "completed",
];
```

---

### MNT-03: Mutable Module-Level Test Override Global [HIGH]

**Severity:** HIGH | **Confidence:** High | **Category:** Coupling / Hidden Global

**Location:** `summarizer/summarize-api/src/runners/url-runner.ts:32-35`

```typescript
// Override exposed for tests. Production code never sets this.
export const __webhookTestOverrides: {
  baseDelayMs?: number;
  fetchImpl?: typeof fetch;
} = {};
```

**Issue:**  
This is a mutable module-level singleton used as a test backdoor. `url-runner.ts` is a module that gets `import`-cached, so any test that sets `__webhookTestOverrides.fetchImpl` in one test case will have that value persist into the next test case unless explicitly reset. If Vitest runs tests in parallel threads sharing the same module instance this is a data race; even with single-threaded execution it is brittle.

**Impact:**  
- **Test isolation:** A failing test that does not reset the overrides will corrupt subsequent tests.  
- **Discoverability:** The coupling between `url-runner.ts` and its tests is invisible from the type system — no interface documents which callers can set this.

**Change scenario:**  
> Add a second test that uses a different `fetchImpl`. Developer sets the override, forgets to reset — next test picks up the wrong mock.

**Smallest Fix:**  
Change `runUrlJob` to accept the overrides inline as an optional parameter, mirroring the pattern already used in `dispatch.ts` (`DispatchSummaryOptions`):

```typescript
// Remove the module-level export.
// Add to runUrlJob signature:
export async function runUrlJob(
  job: Job,
  config: Config,
  eventStore: EventStore,
  db: Database.Database,
  opts: { fetchImpl?: typeof fetch; sleepImpl?: (ms: number) => Promise<void> } = {},
): Promise<void>
```

Tests pass overrides at the call site; production callers pass nothing. No mutable global needed.

---

### MNT-04: SSE Promise Callback is 88 Lines — Extract Named Event Handlers [MED]

**Severity:** MED | **Confidence:** High | **Category:** Complexity

**Location:** `summarizer/summarize-api/src/runners/url-runner.ts:101-189`

```typescript
await new Promise<void>((resolve, reject) => {
  const parser = createParser({
    onEvent(event) {
      // ... 75 lines of switch/case ...
    },
    onError(error) { reject(error); },
  });
  // ... recursive reader loop ...
});
```

**Issue:**  
The SSE consumption promise body is 88 lines. It contains: parsing logic, event routing, chunk accumulation, event store writes, error handling, and the recursive stream reader. Reading the function requires holding all of this in mind simultaneously.

**Impact:**  
- **Readability:** Modifying the `chunk` event handling (e.g., to strip markdown fences) requires finding the right arm of the switch inside the callback inside the promise.  
- **Testability:** The chunk-accumulation logic cannot be tested independently from the full SSE stream setup.

**Change scenario:**  
> Daemon adds a new terminal event type `cancelled`. Developer must find the right `case` inside the 88-line callback, risk missing the `resolve()` call.

**Smallest Fix:**  
Extract the `onEvent` handler to a named function:

```typescript
function handleDaemonSseEvent(
  event: ParsedEvent,
  accumulatedChunks: string[],
  jobId: string,
  eventStore: EventStore,
  resolve: () => void,
  reject: (err: Error) => void,
): void {
  // ... switch/case ...
}
```

The recursive reader loop can also be extracted:

```typescript
function pumpReader(
  reader: ReadableStreamDefaultReader<Uint8Array>,
  parser: EventSourceParser,
  decoder: TextDecoder,
  resolve: () => void,
  reject: (err: unknown) => void,
): void { ... }
```

**Benefit:** Each concern is named and independently readable.

---

### MNT-05: Quota Defaults Duplicated in TypeScript and Kotlin [MED]

**Severity:** MED | **Confidence:** High | **Category:** Duplication

**Locations:**

```typescript
// quota.ts:7-8
const DEFAULT_DAILY_LIMIT = 1000;
const DEFAULT_PER_MINUTE_LIMIT = 20;
```

```kotlin
// QuotaDoc.kt:13-14
val dailyLimit: Long = 1000L,
val perMinuteLimit: Long = 20L,
```

**Issue:**  
These values are structurally the same constant (`1000`/`20`) expressed in two languages. The Kotlin values are "fallback defaults" active before the backend writes the Firestore doc, but there is no comment linking them to the canonical TypeScript definitions. If they drift, the Android UI will classify quota state differently from the backend.

**Impact:**  
- **Change amplification:** Any quota-cap change requires two files in two languages.  
- **Drift risk:** Silent. The only symptom is the Android banner not appearing when the backend considers itself exhausted (or appearing spuriously).

**Smallest Fix:**  
Add inline doc comments explaining the relationship (see also MNT-01). A structural fix (generating the Android constants from the backend) is out of scope for v1.

---

### MNT-06: `DispatchSummaryOptions` Wrapper Type Adds Ceremony for One Field [MED]

**Severity:** MED | **Confidence:** High | **Category:** API Ergonomics

**Location:** `backend/functions/src/summarizer/dispatch.ts:39-41`

```typescript
interface DispatchSummaryOptions {
  fetchImpl?: typeof fetch;
}
```

**Issue:**  
An interface wrapping a single optional field adds nominal indirection without clarity benefit. The test-injection pattern is inconsistently applied: `dispatch.ts` uses `DispatchSummaryOptions`, while `deliver.ts` uses inline `fetchImpl` and `sleepImpl` directly on `DeliverWebhookOptions`. If `dispatchSummary` eventually needs a second injectable (e.g., `nowFn`), it can be added to the interface — but that is speculative.

**Impact:**  
- **Readability:** Call sites that pass `opts` feel ceremonial for one field.  
- **Inconsistency:** Three different conventions for the same pattern (`DispatchSummaryOptions`, `DeliverWebhookOptions`, `__webhookTestOverrides`) make the pattern unpredictable.

**Smallest Fix:**  
Inline the option directly on the function signature and remove the interface:

```typescript
export async function dispatchSummary(
  videoId: string,
  model: string,
  fetchImpl?: typeof fetch,
): Promise<RequestVideoSummaryOutput>
```

Or keep `DispatchSummaryOptions` and add a comment explaining it is intentionally extensible.

---

### MNT-07: `QuotaBannerViewModel` Defined Inside the Composable File [MED]

**Severity:** MED | **Confidence:** Med | **Category:** Cohesion

**Location:** `android/app/src/main/java/.../QuotaBanner.kt:31-39`

```kotlin
@HiltViewModel
class QuotaBannerViewModel @Inject constructor(
    quotaRepository: QuotaRepository,
) : ViewModel() {
    val quotaDoc: StateFlow<QuotaDoc?> = quotaRepository.observe().stateIn(...)
}
```

**Issue:**  
By Android convention, ViewModels live in their own file (either alongside the screen or in a dedicated `viewmodel/` package). Placing `QuotaBannerViewModel` inside `QuotaBanner.kt` breaks the expectation that `.kt` Composable files contain only `@Composable` functions. This complicates grepping for ViewModels and makes the file dual-purpose.

**Impact:**  
- **Discoverability:** A developer searching for ViewModels will miss this one if they look only in `screens/*/...ViewModel.kt` files.  
- **Change amplification:** If `QuotaBannerViewModel` needs a new injected dependency (e.g., `AnalyticsTracker`), the developer must open a Composable file to do it.

**Smallest Fix:**  
Move `QuotaBannerViewModel` to `screens/common/QuotaBannerViewModel.kt`. No logic change needed.

---

### MNT-08: Three Repositories Colocated in `FirestoreRepository.kt` [LOW]

**Severity:** LOW | **Confidence:** High | **Category:** Cohesion

**Location:** `android/app/src/main/java/.../FirestoreRepository.kt`

```kotlin
// Contains: FirestoreRepository, SummaryRepository, QuotaRepository (130 lines)
@Singleton class FirestoreRepository { ... }
@Singleton class SummaryRepository { ... }
@Singleton class QuotaRepository { ... }
```

**Issue:**  
Three distinct `@Singleton` repositories are in one file. Each has a clear single responsibility, so this is not a God-object problem — it is a file-organisation preference. However, the file name `FirestoreRepository` (singular) suggests one class. A developer searching for `SummaryRepository` via file-name search will not find it.

**Impact:** Discoverability cost only; no logic coupling issue.

**Smallest Fix:**  
Either rename the file to `FirestoreRepositories.kt` (plural, truthful) or extract to three files. The rename is a 30-second change with no logic impact.

---

### MNT-09: `webhookUrl()` Looks Like a Pure Accessor But Reads `process.env` [LOW]

**Severity:** LOW | **Confidence:** Med | **Category:** Naming

**Location:** `backend/functions/src/summarizer/dispatch.ts:43-48`

```typescript
function webhookUrl(): string {
  const region = process.env.FUNCTION_REGION ?? FUNCTION_REGION;
  const project = process.env.GCLOUD_PROJECT ?? process.env.GCP_PROJECT ?? "demo-playster";
  return `https://${region}-${project}.cloudfunctions.net/summaryWebhook`;
}
```

**Issue:**  
The name `webhookUrl()` sounds like a property accessor. Reading `process.env` at call time (vs. at module init) is intentional (allows test injection via env), but the function's side-effect-like runtime reads are invisible at the call site.

**Smallest Fix:**  
Rename to `buildWebhookUrl()` (verb form signals computation) and add a one-line comment:

```typescript
// Reads region/project from env at call time so tests can override via process.env.
function buildWebhookUrl(): string { ... }
```

---

### MNT-10: `onRetry` and `onSummarize` Callbacks Are Identical [LOW]

**Severity:** LOW | **Confidence:** High | **Category:** Naming

**Location:** `android/app/src/main/java/.../SummaryScreen.kt:28-33`

```kotlin
SummaryScreenContent(
    state = state,
    onRetry = viewModel::retry,
    onSummarize = viewModel::retry,   // same lambda
)
```

**Issue:**  
Two distinct parameter names (`onRetry`, `onSummarize`) both wire to `viewModel::retry`. The `SummaryScreenContent` signature implies they can differ (e.g., retry might skip quota check, summarize might prompt). In practice they are identical, making the API surface misleading.

**Impact:**  
- A future developer might wire them differently thinking they have different semantics.  
- Or they might collapse them assuming they are the same, removing the flexibility.

**Smallest Fix:**  
Use a single `onAction: () -> Unit` callback in `SummaryScreenContent`, or document why both exist and that they intentionally share the same implementation.

---

### MNT-11: Lock TTL Constant Undocumented [NIT]

**Severity:** NIT | **Confidence:** High | **Category:** Documentation

**Location:** `dispatcher.ts:11`

```typescript
const LOCK_TTL_MS = 240_000;   // 4 minutes
```

**Issue:**  
No comment explains why 4 minutes for a 5-minute schedule. The design intent (shorter than the cron period so a stuck lock does not block the next run) is documented in the shape doc but not in code.

**Smallest Fix:**

```typescript
// 4 minutes: one minute less than the every-5-minutes cron schedule so a
// crashed run's lock expires before the next invocation fires.
const LOCK_TTL_MS = 240_000;
```

---

### MNT-12: `CHUNK_SIZE = 200` Undocumented [NIT]

**Severity:** NIT | **Confidence:** Med | **Category:** Documentation

**Location:** `autoEnqueue.ts:10`

```typescript
const CHUNK_SIZE = 200;
```

**Issue:**  
200 is Firestore's `getAll` batch limit. This should be noted so future maintainers don't wonder if the value is arbitrary.

**Smallest Fix:**

```typescript
// Firestore getAll() accepts at most 500 refs per call; 200 is a safe margin.
const CHUNK_SIZE = 200;
```

---

## 6) Change Amplification Analysis

### Scenario 1: Add a new `SummaryStatus` variant (e.g., `cancelled`)

Files that would need changes:
1. `backend/functions/src/models/index.ts` — add to `SummaryStatus` union ✅ expected
2. `backend/functions/src/summarizer/dispatch.ts` — update `IN_FLIGHT_STATUSES` (or `NON_REDISPATCH_STATUSES`) ⚠️ non-obvious
3. `backend/functions/src/summarizer/webhook.ts` — update `classifyFailure` if relevant ✅ expected
4. `android/.../SummaryDoc.kt` — add to `SummaryStatus` enum + `fromWire` ✅ expected
5. `android/.../SummaryViewModel.kt` — add arm to `mapDocToState` ✅ expected

**Assessment:** Moderate amplification. MNT-02 fix (rename + comment) would make step 2 more obvious.

### Scenario 2: Change replay window from 300s to 600s

Files that would need changes:
1. `backend/functions/src/summarizer/webhook.ts` (`REPLAY_WINDOW_SECONDS`) ✅ expected

**Assessment:** Low. Single constant. But no test or comment links the window to the signer — a developer might change one and forget the pair. Add a comment pointing to signer.ts.

### Scenario 3: Change daily quota cap (new OpenRouter credit tier)

Files:
1. `backend/functions/src/summarizer/quota.ts` (`DEFAULT_DAILY_LIMIT`) ✅ expected
2. `android/.../QuotaDoc.kt` fallback default ⚠️ easy to miss
3. Any hardcoded banner copy referencing the number ✅ no hardcoded numbers found in copy

**Assessment:** Low-moderate. The Android fallback is a silent-drift risk (MNT-01, MNT-05).

---

## 7) Positive Observations

✅ **HMAC contract is clearly documented in `signer.ts`:** The comment block explains canonical bytes, header format, and the whitespace/key-order invariant. This is the most important security-adjacent invariant in the codebase; it is documented correctly.

✅ **`processSummaryWebhook` is extracted as a pure function:** The HTTPS function just wraps it. Tests can drive the pure function directly without a Fastify/Cloud Functions harness. Excellent testability design.

✅ **`SummaryUiState` sealed interface:** Five states, zero magic strings, exhaustive `when` at the Composable. Clean state machine.

✅ **`releaseOpenRouterQuotaSlot` never throws:** The best-effort design is documented and the rationale (over-counting is safer than under-counting) is stated in the JSDoc.

✅ **`SummaryDoc.toSummaryDoc()` mapper:** Null-safe with fallback defaults; `webhookSecret` is deliberately omitted from the Android DTO (no need to expose it to the client layer).

✅ **`deliverWebhook` retry logic:** 4xx-non-retryable fast-path is correct and documented. `baseDelayMs` and `sleepImpl` injectable for tests.

✅ **Lock acquire-release in `drainSummaryQueue` uses `finally`:** Lock is always released even if dispatch throws mid-drain. Correct concurrency hygiene.

---

## 8) Recommendations

### Must Address Before Merge (HIGH)

**MNT-02 (5 min):** Rename `IN_FLIGHT_STATUSES` → `NON_REDISPATCH_STATUSES` and add a one-line comment explaining why `completed` is included. The risk is a future developer incorrectly modifying the set when adding a new status.

**MNT-03 (20 min):** Replace `__webhookTestOverrides` module global with an `opts` parameter on `runUrlJob`. Tests that currently set the global become test-local — no shared state.

**MNT-01 (10 min):** Add cross-reference comments on the magic constants in `quota.ts`, `webhook.ts`, `dispatcher.ts`, `autoEnqueue.ts`, and `QuotaDoc.kt`. No value changes needed; just comments linking them.

### Should Address (MED)

**MNT-04 (30 min):** Extract SSE event handler and stream pump from the 88-line promise block into named functions in `url-runner.ts`. Improves readability and future testability of individual event types.

**MNT-05 (5 min):** Comment in `QuotaDoc.kt` explaining the fallback-default nature and linking to the TypeScript source of truth.

**MNT-06 (5 min):** Either inline `DispatchSummaryOptions` (removing the single-field interface) or add a comment explaining why the extensibility wrapper exists.

**MNT-07 (5 min):** Move `QuotaBannerViewModel` to its own file `QuotaBannerViewModel.kt`.

### Consider (LOW/NIT)

**MNT-08:** Rename `FirestoreRepository.kt` → `FirestoreRepositories.kt` or split into three files.

**MNT-09:** Rename `webhookUrl()` → `buildWebhookUrl()`.

**MNT-10:** Collapse `onRetry`/`onSummarize` to a single `onAction` or document why they are distinct.

**MNT-11/12:** Add one-line comments on `LOCK_TTL_MS` and `CHUNK_SIZE`.

---

## 9) Refactor Cost/Benefit

| Finding | Effort | Benefit | Risk | Recommendation |
|---------|--------|---------|------|----------------|
| MNT-01 | 10 min | High (prevents silent drift) | None | Do now |
| MNT-02 | 5 min | High (prevents future mis-edit) | None | Do now |
| MNT-03 | 20 min | High (test isolation) | Low | Do now |
| MNT-04 | 30 min | Med (readability) | Low | Do now (pre-merge) |
| MNT-05 | 5 min | Med (documentation) | None | Do now |
| MNT-06 | 5 min | Low (clarity) | None | Consider |
| MNT-07 | 5 min | Low (convention) | None | Consider |
| MNT-08 | 5 min | Low (discoverability) | None | Defer |
| MNT-09 | 2 min | Low (naming clarity) | None | Defer |
| MNT-10 | 5 min | Low (API clarity) | None | Defer |
| MNT-11 | 1 min | Low (NIT) | None | Defer |
| MNT-12 | 1 min | Low (NIT) | None | Defer |

**Total effort for HIGH+MED must-fixes:** ~70 minutes  
**Total effort for all HIGH+MED:** ~75 minutes

---

## 10) Summary

**Merge Recommendation:** APPROVE_WITH_COMMENTS

The branch delivers a well-structured multi-component pipeline. Module cohesion is good across the board. The one genuine coupling smell (`__webhookTestOverrides` global) is fixable in 20 minutes. The most impactful maintainability improvements are the constant cross-references (MNT-01), the status-array rename (MNT-02), and the global-to-parameter refactor (MNT-03). All are low-risk changes. The SSE extraction (MNT-04) is a readability improvement but not a blocker.

Nothing here blocks shipping. The HIGH findings should be addressed before or alongside merge; the MED findings can be a follow-up PR if time is short.

---

*Review completed: 2026-05-20*

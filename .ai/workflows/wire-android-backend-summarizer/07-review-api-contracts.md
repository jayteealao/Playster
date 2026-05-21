---
review-command: api-contracts
workflow-slug: wire-android-backend-summarizer
scope: branch-diff
base: main
branch: feat/wire-android-backend-summarizer
completed: "2026-05-21"
---

# API Contracts Review — wire-android-backend-summarizer

**Scope:** Full branch diff (`git diff main...HEAD`) — all API surfaces added or changed  
**Reviewer:** Claude API Contracts Review Agent  
**Date:** 2026-05-21

## Summary

This branch introduces three new API surfaces and migrates three existing ones. The new surfaces are:
1. Firebase callable `requestVideoSummary` — well-formed, correct error codes, fully tested.
2. HTTPS webhook `summaryWebhook` — signature scheme is correct but status code contract has a meaningful discrepancy between the spec stated in the review brief and the actual implementation (missing `client_job_id` returns 400 rather than 401, and an unauthenticated/malformed signature returns 400 before the secret is even looked up).
3. Summarize-API `/v1/jobs` webhook extension — additive, backwards-compatible, correct validation guard.

The three `onRequest → onCall` migrations (`syncAllPlaylists`, `syncPlaylist`, `syncWatchLater`) are a hard breaking change for any caller using HTTP `GET/POST`-with-query-params. The change is intentional and PO-accepted, but the Android client (`SummaryFunctions.kt`) correctly uses the callable SDK, and the shape doc confirms these were previously unauthenticated admin endpoints with no external consumers. No SemVer package exists for the backend functions.

The Kotlin DTOs mirror the TypeScript models accurately for `SummaryDoc`, `VideoDoc`, `QuotaDoc`, and `PlaylistDoc`. One field presence mismatch is noted: `SummaryDocument.model` is non-optional in TypeScript but `SummaryDoc.model` is `String?` in Kotlin — safe in practice because auto-enqueue writes `model: "free"` unconditionally, but technically the field could be absent in the Firestore document for legacy queued docs written before this branch.

One security-relevant contract gap: `GET /v1/jobs/:id` returns the raw `Job` object which includes `webhook_secret`. The `redactJob` function exists in `jobs.ts` but is never called from the route. Callers reading job status get the plaintext secret back.

**Severity Breakdown:**
- BLOCKER: 0
- HIGH: 2
- MED: 3
- LOW: 1
- NIT: 1

**Merge Recommendation:** REQUEST_CHANGES (HIGH findings are actionable before ship)

---

## Findings

### API-01: `webhook_secret` exposed in `GET /v1/jobs/:id` response [HIGH]

**Confidence:** High

**Location:** `summarizer/summarize-api/src/routes/jobs.ts:158-162`

**Issue:**  
The `GET /v1/jobs/:id` route returns `return job` directly. The `Job` interface now includes `webhook_secret: string | null` (added in this branch). A `redactJob` helper was defined in `jobs.ts:155-158` but is never imported or called from the route. Any caller that polls job status receives the plaintext HMAC secret in the response body.

**Evidence:**
```typescript
// routes/jobs.ts:157-162
app.get<{ Params: { id: string } }>("/v1/jobs/:id", async (request, reply) => {
  const job = getJob(request.params.id);
  if (!job) {
    return reply.code(404).send({ error: "Job not found" });
  }
  return job;   // ← webhook_secret is in this object
});
```

**Impact:**  
Any process or user with access to `GET /v1/jobs/:id` (internal or operator-accessible) receives the HMAC secret. An attacker with read access to the job status endpoint can forge valid webhook callbacks, causing the backend to accept arbitrary status transitions on `summaries/{videoId}`.

**Fix:**
```typescript
import { getJob, redactJob } from "../db/jobs.js";

app.get<{ Params: { id: string } }>("/v1/jobs/:id", async (request, reply) => {
  const job = getJob(request.params.id);
  if (!job) return reply.code(404).send({ error: "Job not found" });
  return redactJob(job);
});
```

---

### API-02: `summaryWebhook` status code contract diverges from documented spec [HIGH]

**Confidence:** High

**Location:** `backend/functions/src/summarizer/webhook.ts:97-145`

**Issue:**  
The review brief specifies the status code contract as:
- `200 ok`
- `401 bad signature or expired`
- `404 unknown client_job_id`

The implementation diverges in two ways:

1. A malformed or absent `X-Summarizer-Signature` header returns **400** (`bad-signature-header`), not 401. Likewise a bad JSON body or missing `client_job_id` returns **400**. Only verified-but-wrong and stale-timestamp cases return 401.
2. The success path returns **204** (no content), not 200.

This means:
- A summarizer daemon (or test harness) that keys retry logic on 4xx-non-retryable vs 401 will misclassify a malformed-header response (400) as non-retryable when in practice the correct fix is to resend with the right header.
- The deliver.ts retry loop already treats `4xx != 408/429` as non-retryable — so a genuine clock-skew on a retry would get a 401 and correctly stop, but a config error producing a bad header also stops (400), which is arguably correct. However the external contract document should match.

**Evidence:**
```typescript
// webhook.ts:99-109
if (!sig) {
  logger.warn("summaryWebhook: missing or malformed signature header");
  return { status: 400, body: "bad-signature-header" };  // spec says 401
}
// ...
if (Math.abs(now - sig.t) > REPLAY_WINDOW_SECONDS) {
  return { status: 401, body: "stale-timestamp" };       // correctly 401
}
// ...
if (!verifySignature(sig.v1, raw, sig.t, secret)) {
  return { status: 401, body: "bad-signature" };         // correctly 401
}
// success:
return { status: 204, body: "" };                        // spec says 200
```

**Impact:**  
Any external party implementing a Stripe-compatible webhook sender using the spec description would expect 401 on any authentication failure and 200 on success. The 400/204 divergence will cause confusion in monitoring dashboards and integration testing against the documented contract.

**Fix:**  
Either update the spec to match the implementation (400 for malformed header, 204 for success) — which is the better REST practice — or adjust implementation to unify all auth failures to 401. Recommend updating the shape doc to read:
```
200/204 ok | 400 bad request (malformed header, bad JSON, missing client_job_id)
          | 401 bad signature or stale timestamp | 404 unknown client_job_id
```

---

### API-03: `onRequest → onCall` is a hard wire-protocol breaking change for existing callers [MED]

**Confidence:** High

**Location:** `backend/functions/src/index.ts` (full file, visible in diff)

**Issue:**  
`syncAllPlaylists`, `syncPlaylist`, and `syncWatchLater` were previously `onRequest` (HTTP GET/POST with query parameters). They are now `onCall` (Firebase callable protocol — POST to `/<region>/<project>/us-central1/<name>`, with a JSON envelope `{data: {...}}` and response envelope `{result: {...}}`). The URL path and wire format both change.

The change is PO-accepted (noted in prompt). However:
- The `syncPlaylist` previously read `?playlistId=...` from the query string. It now reads `req.data.playlistId`. Any curl/Postman script, CI trigger, or admin tool using the old URL will silently fail with a 404 or 403 from Firebase.
- The `syncWatchLater` previously read `?reset=true`. It now reads `req.data.reset === true`. A boolean passed as a query string would have been a string `"true"` before; now it must be JSON boolean.
- No CHANGELOG, migration guide, or deprecation header is present.

**Evidence:**
```diff
-export const syncAllPlaylists = onRequest(
+export const syncAllPlaylists = allowlistedCall<Record<string, never>, SyncAllResponse>(
   { memory: "512MiB", ... },
-  async (_req, res) => { ... res.json(result) }
+  async () => { ... return result; }
```

**Impact:**  
Any admin tooling (curl scripts, CI workflows, other Cloud Functions) calling the old HTTP endpoints will break silently. PO accepted this, but a note in the PR description or CHANGELOG would prevent operator confusion on first deploy.

**Recommendation:**  
Add a one-line CHANGELOG or PR description note: "syncAllPlaylists / syncPlaylist / syncWatchLater migrated from HTTP endpoints to Firebase callables; update any admin curl scripts to use the callable SDK or `firebase functions:call`."

---

### API-04: `SummaryDocument.model` non-optional in TS, nullable in Kotlin — auto-enqueue pre-dates contract [MED]

**Confidence:** Med

**Location:**  
- `backend/functions/src/models/index.ts:46-57` (`model: string` — required)  
- `android/app/src/main/java/com/github/jayteealao/playster/data/firestore/SummaryDoc.kt:39` (`val model: String? = null`)

**Issue:**  
The TypeScript `SummaryDocument` interface declares `model: string` (non-optional, no `?`). The Kotlin `SummaryDoc` declares `model: String? = null`. This is safe for all documents written after this branch (auto-enqueue always writes `model: "free"`, dispatch writes it explicitly), but documents in any existing Firestore instance that were written with an older schema could be missing the field. The mismatch is not a crash risk (the Kotlin default is safe), but it signals that the TS interface is aspirationally non-optional when the real constraint hasn't been back-filled.

**Recommendation:**  
Either add `model?: string` to the TS interface (making the source-of-truth explicit about optionality) or add a Firestore migration to back-fill the field on existing docs. Low priority but worth tracking.

---

### API-05: `mode` field is a schema-level no-op — not communicated to callers [MED]

**Confidence:** High

**Location:** `summarizer/summarize-api/src/schemas.ts:14-17`, `summarizer/summarize-api/src/runners/url-runner.ts:10-28`

**Issue:**  
The `urlJobSchema` accepts `mode: z.enum(["auto", "website", "youtube", "media"]).optional()`. The schema comment correctly notes: "NOTE: `mode` is informational only. It is NOT forwarded to the daemon." The `DAEMON_FORWARD_KEYS` list (url-runner.ts) intentionally omits `mode`. However, `POST /v1/jobs` accepts the field, validates it against the enum, and silently discards it. There is no response field, warning, or header indicating to the caller that their `mode` value was ignored.

**Impact:**  
The Firebase callable `requestVideoSummary` does not send `mode` at all (correctly). But any direct `summarize-api` caller (e.g., a future integration) sending `mode: "youtube"` expecting different behavior will get the same result as `mode: "auto"` with no indication. This is a documentation gap, not a runtime bug.

**Recommendation:**  
Document the `mode` field as `@deprecated` or `@experimental no-op` in the schema comment, or remove it from the schema entirely if there's no plan to implement it. A 400 response with `"mode is not yet implemented"` would be even clearer.

---

### API-06: `syncPlaylistById` return shape change adds `videoIds` — not stripped by `syncPlaylist` callable [LOW]

**Confidence:** High

**Location:** `backend/functions/src/index.ts:70-75`, `backend/functions/src/youtube/api-sync.ts:168+`

**Issue:**  
`syncPlaylistById` now returns `{ videoCount: number; videoIds: string[] }`. The `syncPlaylist` callable strips `videoIds` via destructuring and returns only `{ videoCount: number }`. This is correct. However, the declared return type for `syncPlaylist` is `{ videoCount: number }` and the implementation matches — no issue here per se. This finding is LOW because it's correctly handled, but worth noting for future callers: `syncPlaylistById` the internal function returns a different shape from `syncPlaylist` the callable, and the stripping is implicit (no type alias to make it explicit).

**Recommendation:**  
Consider defining a `type SyncPlaylistResponse = Omit<Awaited<ReturnType<typeof syncPlaylistById>>, 'videoIds'>` alias to make the stripping explicit and catch regressions at the type level.

---

### API-07: No OpenAPI spec — all endpoints are contract-by-code only [NIT]

**Confidence:** High

**Location:** All new endpoints in `summarizer/summarize-api/src/routes/jobs.ts`, `backend/functions/src/summarizer/dispatch.ts`, `backend/functions/src/summarizer/webhook.ts`

**Issue:**  
There is no OpenAPI/Swagger spec, no protobuf, and no machine-readable contract document for any of the new endpoints. This is consistent with the existing codebase style (none existed before this branch either), so it's not a regression, just a gap.

**Recommendation:**  
For a single-tenant internal-only project this is acceptable. If the summarize-api ever becomes a shared service, generating a spec via `fastify-swagger` would be the natural next step.

---

## API Surface Analysis

**Endpoints Reviewed:** 6 (3 new Firebase callables, 1 HTTPS webhook, 1 extended HTTP route, 1 existing HTTP route GET /v1/jobs/:id)  
**GraphQL Operations:** 0  
**Protobuf Services:** 0  
**Kotlin DTOs Reviewed:** 4 (SummaryDoc, VideoDoc, QuotaDoc, PlaylistDoc)

**Versioning Strategy:**  
- Firebase Functions: no URL versioning (internal, single-tenant — acceptable)  
- Summarize-API: URL-versioned at `/v1/`  
- Firebase callable protocol: implicit versioning by function name

**Version Bump Needed:** No — no public SemVer package exists; internal deployment-only.

---

## Breaking Changes Summary

| Component | Change | Severity | PO Accepted? |
|---|---|---|---|
| `syncAllPlaylists` | `onRequest` → `onCall` (wire protocol) | Breaking | Yes |
| `syncPlaylist` | `onRequest` → `onCall` + query-param → JSON body | Breaking | Yes |
| `syncWatchLater` | `onRequest` → `onCall` + `reset` type string→boolean | Breaking | Yes |
| `syncPlaylistById` (internal) | Adds `videoIds` to return | Additive | N/A |
| `/v1/jobs` (summarize-api) | Adds `webhook_url`, `webhook_secret`, `client_job_id` | Additive | N/A |
| `GET /v1/jobs/:id` (summarize-api) | Adds `webhook_secret` to response (secret exposed) | HIGH | No |

---

## Deprecation Audit

**Deprecated Fields Found:** 0  
**Missing Deprecation Markers:** 0  
**Fields Ready for Removal:** 0  
**Deferred Items:** `mode` field in urlJobSchema — intentional no-op, cleanup deferred per shape doc

---

## Recommendations

### Immediate (HIGH)
1. **API-01**: Apply `redactJob()` in `GET /v1/jobs/:id` route before shipping — one-line fix.
2. **API-02**: Align the spec description with implementation status codes, or unify to 401 for all auth failures. Update any integration test assertions that check for 401 on a malformed header.

### Short-term (MED)
3. **API-03**: Add a CHANGELOG or PR note documenting the onRequest→onCall migration for any admin tooling.
4. **API-04**: Decide whether `SummaryDocument.model` should be optional in TS; update interface accordingly.
5. **API-05**: Mark `mode` in `urlJobSchema` as a documented no-op or remove it.

### Low Priority
6. **API-06**: Add a type alias to make the `videoIds` stripping in `syncPlaylist` explicit.
7. **API-07**: Consider `fastify-swagger` if summarize-api usage widens beyond single-tenant.

---

## API Contract Health Score

**Overall Score:** 7/10

**Breakdown:**
- Versioning: 8/10 (consistent `/v1/` on HTTP side; callables follow Firebase convention)
- Backwards Compatibility: 6/10 (three breaking changes, PO-accepted; one secret exposure gap)
- Documentation: 5/10 (no OpenAPI spec; status codes diverge from spec text)
- Deprecation Management: 9/10 (mode no-op documented inline; additive-only changes otherwise)
- Error Consistency: 7/10 (Firebase error codes correct; webhook 400 vs 401 inconsistency)

---
schema: sdlc/v1
type: review-command
slug: wire-android-backend-summarizer
review-scope: slug-wide
slice-slug: ""
review-command: security
status: complete
updated-at: "2026-05-20T23:03:35Z"
metric-findings-total: 8
metric-findings-blocker: 0
metric-findings-high: 3
metric-findings-medium: 3
metric-findings-low: 2
result: issues-found
tags: [security, webhook, hmac, firestore, auth, ssrf, android]
refs:
  review-master: 07-review.md
---

# Review: security

**Reviewed:** slug-wide / `git diff main...HEAD` (feat/wire-android-backend-summarizer)
**Date:** 2026-05-20
**Files:** 232 changed, +16 714 −1 212

---

## 0. Scope, Assumptions, and Threat Summary

**What was reviewed:**
- Scope: full branch diff (`main...HEAD`)
- Files: 232 changed across Android, Firebase Functions backend, summarize-api gateway, summarize-daemon subtree, Maestro helpers, Dockerfile/entrypoint
- Focused surfaces per brief: HMAC webhook signing, allowlist auth wrapper, Firestore rules, daemon bearer token, secret handling, Android OAuth/Firebase Auth bridge, Maestro seed helpers

**Threat model:**
- **Entry points:** `summaryWebhook` HTTPS function (public URL, POSTed by Cloud Run summarizer), `requestVideoSummary` / sync callables (Firebase Auth–gated onCall), `scheduledSync` / `summaryDispatcher` cron (Cloud Scheduler, no auth context), `GET /v1/jobs/:id` on summarize-api (API-key gated), Android Credential Manager sign-in
- **Trust boundaries:** Internet → `summaryWebhook` (HMAC only); Firebase Auth user → allowlisted callables; Cloud Scheduler → cron functions; summarize-api X-API-Key → job CRUD; daemon loopback (127.0.0.1:8787) — not Internet-accessible
- **Assets:** `webhookSecret` (per-summary HMAC key, 32-byte hex), `SUMMARIZER_API_KEY`, `SUMMARIZE_TOKEN`, `OPENROUTER_API_KEY`, Firebase Auth ID tokens / Google ID tokens, Firestore `tokens/{doc}` (OAuth refresh tokens), operator uid in rules
- **Privileged operations:** webhook → Firestore writes (status transitions); callables → quota increment + dispatching LLM jobs; cron → dispatcher drain + sweeper

**Authentication model:**
- Backend callables: Firebase Auth ID token → `requireAllowlistedUid` → uid string compare
- Webhook: HMAC-SHA256 (Stripe-style, per-summary secret, 300 s replay window, `timingSafeEqual`)
- summarize-api: `X-API-Key` header (string includes check — NOT timing-safe; low risk since key is backend-controlled)
- Daemon: Bearer token (`daemonConfigTokens(config).includes(token)` — NOT timing-safe)
- Firestore client-side rules: uid string equality (correct for single-tenant)

**Assumptions:**
- Single-tenant deployment; no multi-user risk model applies
- `ALLOWED_UID="__BOOTSTRAP_UID__"` in prod until the operator completes the two-pass bootstrap — by design, all callables deny during bootstrap
- `google-services.json` is gitignored and never committed
- Summarize-daemon loopback binding (127.0.0.1:8787) means the daemon is not reachable from the public internet, only from the co-located gateway process

---

## 1. Executive Summary

**Merge Recommendation:** APPROVE_WITH_COMMENTS (no BLOCKERs; three HIGH issues warrant fixes or explicit acceptance before next production push)

**Rationale:**
The core cryptographic controls are correctly implemented: Stripe-style HMAC with `timingSafeEqual`, per-summary secrets, 300-second replay window, and `rawBody` plumbing are all present and correct. Firebase Auth allowlist and Firestore rules deny all unauthenticated and non-operator access. The three HIGH findings are real issues but exploitability in this single-tenant, operator-only context is significantly reduced.

**High-Risk Issues:**
1. **SEC-1** — `GET /v1/jobs/:id` returns `webhook_secret` in plaintext; any caller with a valid `X-API-Key` can harvest all per-job HMAC secrets
2. **SEC-2** — `webhook_url` accepted in the job-creation request body is NOT passed through the existing SSRF guard; a caller who can POST a job could use it to trigger server-side fetches to internal Cloud Run metadata endpoints or other internal addresses
3. **SEC-3** — `firestore.rules` is committed with the literal placeholder string `__BOOTSTRAP_UID__` in the `isAllowlisted()` function; if deployed without replacement it denies all reads (good lockout), but the sentinel is visible in git history and makes the "what is the real UID" answer trivially findable

**Overall Security Posture:**
- Authentication: **Strong** (Firebase Auth + uid allowlist on all callables)
- Authorization: **Strong** (single-tenant; Firestore rules block writes from client)
- Input Validation: **Adequate** (webhook body typed, videoId existence-checked, webhook_secret length-checked) with SEC-2 gap
- Secret Management: **Adequate** (all prod secrets via Secret Manager / env; SEC-1 is a runtime exposure gap, not a git commit issue)
- Defense-in-Depth: **Good** (HMAC replay window, per-summary secrets, loopback daemon binding, `rawBody` buffer)

---

## 2. Threat Surface Analysis

### Entry Points

| Entry Point | Type | Auth | Rate Limited | SSRF Guard | Input Validation |
|---|---|---|---|---|---|
| `summaryWebhook` HTTPS | HTTP POST | HMAC signature | No (Cloud Functions throttle only) | N/A | Typed; body → Firestore only |
| `requestVideoSummary` onCall | Firebase Callable | Firebase Auth + allowlist | Quota pre-check (Firestore tx) | N/A | videoId existence checked |
| `syncAllPlaylists` / `syncPlaylist` / `syncWatchLater` onCall | Firebase Callable | Firebase Auth + allowlist | No explicit | N/A | Input typed |
| `scheduledSync` / `summaryDispatcher` cron | Cloud Scheduler | Cloud Scheduler IAM | N/A | N/A | N/A |
| `POST /v1/jobs` (summarize-api) | HTTP | X-API-Key | No | `validateUrl(url)` only; `webhook_url` NOT checked | Zod schema |
| `GET /v1/jobs/:id` (summarize-api) | HTTP | X-API-Key | No | N/A | Path param only |
| Android Sign-In | Credential Manager | Google OAuth → Firebase Auth | N/A | N/A | ID token validated by Firebase |
| `setCookies` HTTPS | HTTP POST | **None** | No | N/A | Type check only |
| `setTvOauthCredentials` HTTPS | HTTP POST | **None** | No | N/A | refresh_token presence check |

**High-risk entry points:**
- `GET /v1/jobs/:id` — exposes `webhook_secret` without redaction (SEC-1)
- `POST /v1/jobs` — `webhook_url` bypasses SSRF check (SEC-2)
- `setCookies` / `setTvOauthCredentials` — no authentication; deferred by PO but remain deployed (SEC-5, LOW)

### Assets at Risk

| Asset | Sensitivity | Exposure Risk | Finding |
|---|---|---|---|
| `webhookSecret` (per-summary) | High — HMAC key for one summary | HIGH — returned in `GET /v1/jobs/:id` | SEC-1 |
| `SUMMARIZER_API_KEY` | High — gates all summarizer jobs | Low — Secret Manager; not logged | — |
| `SUMMARIZE_TOKEN` | High — daemon bearer token | Low — env only; not logged | — |
| `OPENROUTER_API_KEY` | High — LLM spend control | Low — Secret Manager; not logged | — |
| Firestore `tokens/{doc}` (OAuth refresh) | Critical | Low — rules deny all client reads/writes | — |
| Operator uid (rules) | Low — sentinel in git | Negligible — single-tenant | SEC-3 NIT |

---

## 3. Findings Table

| ID | Severity | Confidence | Category | File:Line | Vulnerability |
|---|---|---|---|---|---|
| SEC-1 | HIGH | High | Secret Exposure | `summarizer/summarize-api/src/routes/jobs.ts:162` | `GET /v1/jobs/:id` returns raw `webhook_secret` |
| SEC-2 | HIGH | High | SSRF | `summarizer/summarize-api/src/routes/jobs.ts:100-120` | `webhook_url` not passed through `validateUrl` |
| SEC-3 | HIGH | Med | Auth Bootstrap | `backend/firestore.rules:6` | Placeholder `__BOOTSTRAP_UID__` in committed rules — visible in git |
| SEC-4 | MED | Med | Timing Side-channel | `summarizer/summarize-daemon/src/daemon/server.ts:219` | Bearer token compared with `Array.includes()`, not `timingSafeEqual` |
| SEC-5 | MED | High | Missing AuthN | `backend/functions/src/auth/handlers.ts:49,78` | `setCookies` + `setTvOauthCredentials` are unauthenticated HTTP endpoints accepting sensitive data |
| SEC-6 | MED | Med | Unvalidated Input | `backend/functions/src/summarizer/dispatch.ts:~180` | User-controlled `model` parameter forwarded to summarizer with no allowlist |
| SEC-7 | LOW | High | Non-timing-safe compare | `summarizer/summarize-api/src/middleware/auth.ts:17` | `X-API-Key` compared with `Array.includes()` (not `timingSafeEqual`) |
| SEC-8 | LOW | High | Missing AuthN | `backend/firestore.rules:6` | `locks/{document}` collection has no explicit rule; falls through to catch-all deny — correct but fragile |

**Findings Summary:**
- BLOCKER: 0
- HIGH: 3
- MED: 3
- LOW: 2
- NIT: 0

---

## 4. Detailed Findings

### SEC-1: `GET /v1/jobs/:id` returns `webhook_secret` in plaintext [HIGH]

**Location:** `summarizer/summarize-api/src/routes/jobs.ts:157-163`

**Vulnerable Code:**
```typescript
// Line 157-163
app.get<{ Params: { id: string } }>("/v1/jobs/:id", async (request, reply) => {
  const job = getJob(request.params.id);
  if (!job) {
    return reply.code(404).send({ error: "Job not found" });
  }
  return job;  // ← raw Job object includes webhook_secret
});
```

**Vulnerability:**
`getJob()` returns the full `Job` row which now includes `webhook_secret` (the 32-byte hex HMAC key for that job's per-summary signing). The `redactJob()` helper was defined in `src/db/jobs.ts` specifically for this purpose but is not used in either `GET /v1/jobs/:id` or `GET /v1/jobs/:id/events`.

**Exploit Scenario:**
Any caller with a valid `X-API-Key` (the backend itself, or anyone who obtains the key — a leaked CI log, a misconfigured env var) can call `GET /v1/jobs/<id>` and retrieve the `webhook_secret`. With that secret and the `webhook_url`, they can forge a webhook delivery to the backend and change a summary's status/content to anything they choose.

```
GET /v1/jobs/abc123 HTTP/1.1
X-API-Key: <leaked-key>

→ { ..., webhook_secret: "a3f9...64e", webhook_url: "https://...cloudfunctions.net/summaryWebhook" }

→ attacker crafts HMAC: createHmac("sha256", "a3f9...64e").update(`${ts}.${body}`).digest("hex")
→ POST /summaryWebhook with forged signature → Firestore summary updated to arbitrary content
```

**Impact:**
- Summary content for any video can be replaced with attacker-controlled markdown
- `status` can be forced to `completed` / `failed-permanent` on any running job

**Severity:** HIGH
**Confidence:** High
**CWE:** CWE-312 (Cleartext Storage of Sensitive Information), CWE-200 (Exposure of Sensitive Information)

**Remediation:**
```diff
--- a/summarizer/summarize-api/src/routes/jobs.ts
+++ b/summarizer/summarize-api/src/routes/jobs.ts
@@ -10,7 +10,7 @@
-import { createJob, getJob } from "../db/jobs.js";
+import { createJob, getJob, redactJob } from "../db/jobs.js";

@@ -157,7 +157,7 @@
   const job = getJob(request.params.id);
   if (!job) {
     return reply.code(404).send({ error: "Job not found" });
   }
-  return job;
+  return redactJob(job);
 });
```

Apply the same fix to any other route that returns `Job` objects (verify the events SSE route does not expose the job row directly — it only serializes `JobEvent` entries, so it is safe, but do a final audit pass).

---

### SEC-2: `webhook_url` bypasses SSRF guard [HIGH]

**Location:** `summarizer/summarize-api/src/routes/jobs.ts:100-120`

**Vulnerable Code:**
```typescript
// Line 106 — only the job `url` is checked, not `webhook_url`
const ssrfResult = await validateUrl(parsed.data.url);
if (!ssrfResult.safe) { ... }

const job = createJob({
  type: "url",
  source: parsed.data.url,
  options: parsed.data.options,
  webhookUrl: parsed.data.webhook_url,   // ← not SSRF-checked
  webhookSecret: parsed.data.webhook_secret,
  clientJobId: parsed.data.client_job_id,
});
```

**Vulnerability:**
`validateUrl` (which resolves DNS and blocks private IPs) is called only for the job's `url` (the YouTube URL). The `webhook_url` field is accepted, stored, and later used by `deliver.ts` to make an outbound HTTP POST — without any SSRF validation. A caller who can POST to `/v1/jobs` with a valid API key can set `webhook_url` to an internal Cloud Run metadata endpoint, another Cloud Run service, or any GCP-internal address.

**Exploit Scenario:**
In this deployment the only caller is the Firebase Functions backend (which constructs the webhook URL to point at itself). However, if the `SUMMARIZER_API_KEY` were ever leaked:

```json
POST /v1/jobs
X-API-Key: <leaked>

{
  "url": "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
  "webhook_url": "http://169.254.169.254/computeMetadata/v1/instance/service-accounts/default/token",
  "webhook_secret": "aaaaaaaaaaaaaaaa"
}
```
The summarizer gateway would POST the summary result to the GCP metadata endpoint, potentially leaking the Cloud Run service account token via error response or response logging.

**Impact:**
- Server-side request forgery to internal Cloud Run metadata service
- Potential credential exfiltration via GCP instance metadata endpoint
- Lateral movement within GCP project

**Severity:** HIGH
**Confidence:** High
**CWE:** CWE-918 (SSRF)
**OWASP:** A10:2021 — SSRF

**Remediation:**
```diff
--- a/summarizer/summarize-api/src/routes/jobs.ts
+++ b/summarizer/summarize-api/src/routes/jobs.ts
@@ -106,6 +106,15 @@
       const ssrfResult = await validateUrl(parsed.data.url);
       if (!ssrfResult.safe) { ... }

+      // Also SSRF-check the webhook_url before storing it.
+      if (parsed.data.webhook_url) {
+        const whResult = await validateUrl(parsed.data.webhook_url);
+        if (!whResult.safe) {
+          return reply.code(403).send({
+            error: whResult.error ?? "webhook_url blocked by SSRF policy",
+          });
+        }
+      }

       const job = createJob({ ... });
```

Alternatively, since the only legitimate webhook URL is always the Firebase Functions URL, enforce an allowlist of URL prefixes (e.g., `*.cloudfunctions.net`) instead of/in addition to the SSRF guard.

---

### SEC-3: Plaintext `__BOOTSTRAP_UID__` placeholder committed to Firestore rules [HIGH]

**Location:** `backend/firestore.rules:6`

**Current Code (in git):**
```
function isAllowlisted() {
  return request.auth != null && request.auth.uid == "__BOOTSTRAP_UID__";
}
```

**Vulnerability:**
The sentinel string `__BOOTSTRAP_UID__` is committed to the repository as the placeholder before the operator's real UID is substituted. While the placeholder itself is functionally safe (no real UID matches it, so all reads are denied), two issues arise:

1. **Git history visibility:** The real UID string that replaces this sentinel will also be committed and visible in git history forever. Firebase Auth UIDs are not secret (they can be read from the Firebase console) but embedding PII-adjacent identifiers in git history is a hygiene concern that should be called out.

2. **Deploy-without-substitution risk:** If a deployment pipeline re-deploys `firestore.rules` from the checked-in file without performing the substitution (e.g., CI deploys after merge without running the bootstrap script), the app enters a broken state where the operator cannot read any Firestore data, and the issue may not be immediately obvious.

**Severity:** HIGH (with Med confidence — functional impact is a soft lockout, not data breach)
**Confidence:** Med
**CWE:** CWE-547 (Use of Hard-coded, Security-relevant Constants)

**Remediation:**
Two options (pick one):

**Option A — Environment variable substitution in deploy pipeline (recommended):**
Keep `__BOOTSTRAP_UID__` as sentinel but add a `make deploy-rules` or `firebase deploy --only firestore:rules` wrapper script that `sed`s the sentinel with `$ALLOWED_UID` from env before deploying:
```bash
RULES_FILE="backend/firestore.rules"
sed "s/__BOOTSTRAP_UID__/${ALLOWED_UID}/" "$RULES_FILE" | firebase deploy --only firestore:rules --data -
```
The sentinel stays in the repo but the deployed rules always carry the real UID without committing it.

**Option B — Custom claim instead of hardcoded UID:**
Use a Firebase Auth custom claim `operator: true` set via Admin SDK at bootstrap. Rules become:
```
function isAllowlisted() {
  return request.auth != null && request.auth.token.operator == true;
}
```
No UID in any file. More rotation-friendly.

---

### SEC-4: Daemon bearer token uses non-timing-safe `Array.includes()` [MED]

**Location:** `summarizer/summarize-daemon/src/daemon/server.ts:219`

**Code:**
```typescript
const authed = token ? daemonConfigTokens(config).includes(token) : false;
```

**Vulnerability:**
`Array.prototype.includes()` uses short-circuit string equality, making the comparison susceptible to timing side-channel attacks. An attacker who can make many requests with different token values and measure response times could theoretically recover the token character-by-character.

**Context / Severity Reduction:**
The daemon is bound to `127.0.0.1:8787` and is only reachable from the gateway process on the same container. The attack requires local access to measure sub-millisecond timing differences, which is not realistically possible in the Cloud Run single-container model. The `SUBTREE_PIN.md` claims PR #226 added timing-safe comparison, but the current code does not use `timingSafeEqual` — the claim is either inaccurate or refers to a different location. The risk is low in this deployment model but worth fixing to match the documented intent.

**Severity:** MED
**Confidence:** Med (exploitability nearly zero given loopback binding; correctness gap is real)
**CWE:** CWE-208 (Observable Timing Discrepancy)

**Remediation:**
This is in the vendored subtree (`summarize-daemon`). Either:
1. File an upstream PR to replace `includes()` with `timingSafeEqual` and pull the resulting commit.
2. Apply a local patch in the subtree (and note it in `SUBTREE_PIN.md`):
```typescript
import { timingSafeEqual } from "node:crypto";

function tokenMatchesSafe(candidate: string, allowed: string): boolean {
  try {
    const a = Buffer.from(candidate, "utf8");
    const b = Buffer.from(allowed, "utf8");
    if (a.length !== b.length) return false;
    return timingSafeEqual(a, b);
  } catch { return false; }
}

const authed = token
  ? daemonConfigTokens(config).some((t) => tokenMatchesSafe(token, t))
  : false;
```

---

### SEC-5: `setCookies` and `setTvOauthCredentials` are unauthenticated HTTP endpoints [MED]

**Location:** `backend/functions/src/auth/handlers.ts:49, 78`

**Code:**
```typescript
export const setCookies = onRequest(async (req, res) => {
  // No auth check — accepts raw YouTube cookie strings from any caller
  await storeCookies(cookies);
  ...
});

export const setTvOauthCredentials = onRequest(async (req, res) => {
  // No auth check — accepts TV OAuth refresh_token from any caller
  await saveTvOauthCredentials(body);
  ...
});
```

**Vulnerability:**
Both endpoints accept sensitive data (YouTube session cookies and OAuth refresh tokens) and write them to Firestore without any authentication check. Any actor who discovers the Cloud Functions URL can:
- Overwrite stored YouTube cookies with attacker-controlled values, poisoning the InnerTube session
- Overwrite TV OAuth credentials, preventing Watch Later syncs

**Context:** These are pre-existing deferred-cleanup endpoints explicitly acknowledged in the shape doc. They are not called by any new code path and the operator is the only human with the URL. The risk is low in practice but a leaked function URL (e.g., via error logs or network traces) exposes write access to sensitive Firestore data.

**Severity:** MED
**Confidence:** High
**CWE:** CWE-306 (Missing Authentication for Critical Function)

**Remediation:**
Add `requireAllowlistedUid` to both handlers before the deferred Phase 6 cleanup, OR convert them to `onCall` with `allowlistedCall`. Since the PO has deferred removal, at minimum add a middleware check:
```typescript
export const setCookies = onRequest(async (req, res) => {
  // Temporary auth guard until this endpoint is removed in Phase 6
  const idToken = req.headers.authorization?.replace("Bearer ", "");
  if (!idToken) { res.status(401).send("Unauthorized"); return; }
  try {
    const decoded = await admin.auth().verifyIdToken(idToken);
    if (decoded.uid !== ALLOWED_UID.value()) {
      res.status(403).send("Forbidden"); return;
    }
  } catch { res.status(401).send("Unauthorized"); return; }
  // ... rest of handler
});
```

---

### SEC-6: User-controlled `model` parameter forwarded to summarizer without allowlist [MED]

**Location:** `backend/functions/src/summarizer/dispatch.ts` (callable handler)

**Code:**
```typescript
const model = req.data?.model ?? "free";
logger.info("requestVideoSummary", { videoId, model });
return dispatchSummary(videoId, model);
```

**Vulnerability:**
The callable accepts an optional `model` field from the (allowlisted) client and forwards it verbatim to the summarizer's `options.model` field. While only the allowlisted operator can call this function, passing arbitrary strings as the model identifier to OpenRouter could:
- Bypass the cost-free guarantee if the operator (or an attacker with the operator's Firebase token) passes a paid model ID
- Create confusion in quota tracking (the `model` field is stored on the `SummaryDocument`)

**Context:** Since only the operator can call this, this is not an external attack vector. The shape doc's design intent was `model="free"` always from the backend. The callable exposing `model` as a client-settable parameter contradicts that intent without documentation.

**Severity:** MED (business logic risk in single-tenant context; no external attacker can reach this)
**Confidence:** Med
**CWE:** CWE-20 (Improper Input Validation)

**Remediation:**
Either remove the `model` field from `RequestVideoSummaryInput` entirely and hardcode `"free"`, or add an allowlist:
```typescript
const ALLOWED_MODELS = new Set(["free"]);
const model = req.data?.model ?? "free";
if (!ALLOWED_MODELS.has(model)) {
  throw new HttpsError("invalid-argument", `Model "${model}" is not allowed.`);
}
```

---

### SEC-7: `X-API-Key` comparison in summarize-api is not timing-safe [LOW]

**Location:** `summarizer/summarize-api/src/middleware/auth.ts:17`

**Code:**
```typescript
if (!apiKey || typeof apiKey !== "string" || !config.apiKeys.includes(apiKey)) {
```

**Vulnerability:**
`Array.prototype.includes()` uses short-circuit equality, theoretically allowing timing attacks to recover the API key character-by-character. In practice, the summarize-api is only accessible to the Firebase Functions backend (which constructs `SUMMARIZER_API_KEY` from Secret Manager). The risk is very low but the fix is trivial.

**Severity:** LOW
**Confidence:** High
**CWE:** CWE-208 (Observable Timing Discrepancy)

**Remediation:**
```typescript
import { timingSafeEqual } from "node:crypto";

function apiKeyMatchesSafe(candidate: string, allowed: string): boolean {
  try {
    const a = Buffer.from(candidate, "utf8");
    const b = Buffer.from(allowed, "utf8");
    if (a.length !== b.length) return false;
    return timingSafeEqual(a, b);
  } catch { return false; }
}

if (!apiKey || typeof apiKey !== "string"
    || !config.apiKeys.some((k) => apiKeyMatchesSafe(apiKey, k))) { ... }
```

---

### SEC-8: `locks/` collection has no explicit Firestore rule [LOW]

**Location:** `backend/firestore.rules` (implicit)

**Vulnerability:**
The dispatcher uses `locks/summaryDispatcher` as a Firestore lock document (written by Admin SDK from Cloud Functions — correctly bypasses client rules). However, the `locks/` collection has no explicit client-side security rule. It currently falls through to the catch-all `allow read, write: if false`, which is correct but relies on rule ordering. Any future rule reorder or rule addition that pattern-matches `/{document=**}` before the catch-all could accidentally open the collection.

**Severity:** LOW
**Confidence:** High
**CWE:** CWE-1286 (Improper Validation of Syntactic Correctness of Input — applied to rule correctness)

**Remediation:**
Add an explicit rule for defense-in-depth:
```
match /locks/{document=**} { allow read, write: if false; }
```

---

## 5. Security Posture Assessment

### HMAC Webhook Signing — Strong

- Stripe-style `t=<unix>,v1=<hmac-sha256>` header: correct
- HMAC body = `${timestamp}.${rawBody}`: correct
- 300-second replay window with `Math.abs(now - sig.t)`: correct
- `crypto.timingSafeEqual` on hex-decoded buffers: correct
- Per-summary secret (32 random bytes, stored in `summaries/{videoId}.webhookSecret`): correct
- Firestore lookup of secret happens AFTER basic structural checks (timestamp, body parse, clientJobId) to avoid leaking oracle information: correct
- Length check before `timingSafeEqual` (`expected.length !== v1.length`) prevents the "unequal length throws" bypass: correct
- `webhook_secret` not logged anywhere in `url-runner.ts` or `webhook.ts`: correct

### Firebase Auth Allowlist — Strong

- `requireAllowlistedUid` throws `unauthenticated` (no uid) then `permission-denied` (wrong uid): correct
- Default `ALLOWED_UID = "__BOOTSTRAP_UID__"` ensures safe-fail during bootstrap: correct
- Applied via `allowlistedCall` to all three sync callables and `requestVideoSummary`: correct
- Cron functions (`scheduledSync`, `summaryDispatcher`) correctly skip auth (no user context): correct

### Firestore Rules — Adequate

- All collections have explicit rules; catch-all is `deny all`: correct
- `tokens/{document}` is `allow read: if false; allow write: if false` (no client access): correct
- Client writes to `summaries/`, `quota/`, `playlists/`, `videos/`, `locks/` are all denied: correct
- SEC-3 (bootstrap sentinel), SEC-8 (implicit locks rule): noted above

### Secret Handling — Adequate

- `SUMMARIZER_URL` and `SUMMARIZER_API_KEY` via `defineSecret()`, injected at Cloud Functions call time: correct
- `SUMMARIZE_TOKEN` checked at container startup with hard exit if missing: correct
- `webhook_secret` logged only as `"<redacted>"` via `redactJob()` (but SEC-1: `redactJob` not called in GET route)
- `OPENROUTER_API_KEY`, `SUMMARIZER_API_KEY`, `SUMMARIZE_TOKEN` never appear in log output: correct

### Android OAuth Bridge — Adequate

- `GoogleIdTokenCredential.idToken` → `GoogleAuthProvider.getCredential(idToken, null)` → `signInWithCredential`: standard pattern, correct
- `idToken` is not logged (only the resulting `uid` is logged at DEBUG level): correct
- `SERVER_CLIENT_ID` is hardcoded in `AuthScreen.kt`; this is a public OAuth client ID (not a secret), acceptable for Android apps

---

## 6. Recommendations by Priority

### Fix Before Next Production Push (HIGH)

1. **SEC-1** — Apply `redactJob()` in `GET /v1/jobs/:id`
   - Effort: 5 minutes (one-line fix)
   - Risk if unaddressed: HMAC key exposure → summary content forgery

2. **SEC-2** — Add `validateUrl(parsed.data.webhook_url)` after the existing SSRF check
   - Effort: 10 minutes (5 lines)
   - Risk if unaddressed: SSRF to GCP metadata endpoint via summarizer container

3. **SEC-3** — Document and automate the UID substitution in the deploy pipeline
   - Effort: 30 minutes (deploy script or Option B custom claim)
   - Risk if unaddressed: broken deploy if CI deploys rules from git without substitution

### Fix Soon (MED)

4. **SEC-5** — Add temporary allowlist guard to `setCookies` / `setTvOauthCredentials`
   - Effort: 30 minutes
   - Risk if unaddressed: unauthenticated credential overwrite if URL leaks

5. **SEC-4** — File upstream PR for timing-safe daemon bearer comparison (or local patch)
   - Effort: 20 minutes (low urgency given loopback binding)

6. **SEC-6** — Enforce `model="free"` allowlist in callable
   - Effort: 5 minutes (add Set check)

### Backlog (LOW)

7. **SEC-7** — Timing-safe `X-API-Key` compare in summarize-api middleware
   - Effort: 10 minutes

8. **SEC-8** — Add explicit `locks/` deny rule in `firestore.rules`
   - Effort: 2 minutes

---

## 7. False Positives and Disagreements Welcome

- **SEC-3:** If the actual operator UID is already substituted in a separate untracked env file and deployment always pulls from there (not from the git file), the risk is lower than assessed. But the deploy automation should be documented to prevent future regressions.
- **SEC-4:** If the daemon is truly only reachable on loopback from a trusted co-process, timing attacks are practically impossible and this finding may be deferred indefinitely.
- **SEC-6:** If `model` is intentionally kept as a developer escape hatch (future use), documenting the accepted risk and restricting to `"free"` by default in the callable is sufficient.

---

## Summary

The branch implements a well-designed security model for the HMAC webhook pipeline and the Firebase Auth allowlist. The cryptographic controls (Stripe-style HMAC, `timingSafeEqual`, per-summary secrets, replay window) are correctly implemented. Three HIGH findings require attention before the next production push: the `webhook_secret` returned in plaintext by the job status endpoint (SEC-1), the missing SSRF check on `webhook_url` (SEC-2), and the bootstrap UID sentinel deployment risk (SEC-3). None of these are BLOCKERs given the single-tenant, operator-only deployment context, but all three have straightforward fixes that should land before broader use.

**Verdict: Ship with caveats** — address SEC-1 and SEC-2 (combined ~15 minutes of changes) before the next production push. SEC-3 requires deploy-pipeline documentation.

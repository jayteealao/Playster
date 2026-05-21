---
review-command: privacy
slug: wire-android-backend-summarizer
date: 2026-05-21
scope: diff
target: git diff main...HEAD
files-changed: 232
lines-added: ~16714
lines-removed: ~1212
related:
  shape: 02-shape.md
  index: 00-index.md
---

# Privacy Review — wire-android-backend-summarizer

**Reviewed:** full branch diff (`git diff main...HEAD`)
**Date:** 2026-05-21
**Reviewer:** Claude Code (privacy rubric)

---

## 0) Scope, Context, and Data Classification

**Privacy context:**
- **Jurisdiction:** US (single-operator personal tool; no EU data-subject obligations for self-hosted single-tenant, but good practice observed)
- **Applicable regulations:** Informal — no external users; operator is the sole data subject
- **Data sensitivity encountered:**
  - Sensitive: Firebase Auth uid (single operator), YouTube video/playlist titles, video descriptions, summary markdown content
  - Operational secrets: `SUMMARIZER_API_KEY`, `SUMMARIZE_TOKEN`, `OPENROUTER_API_KEY`, per-summary `webhookSecret`
  - Less sensitive: quota counters, video metadata (public YouTube data), Firestore lifecycle timestamps

**Data flows:**
```
Android (Google Sign-In ID token)
  → Firebase Auth → uid
  → Firestore listener (playlists/videos/summaries/quota) [TLS]
  → Firebase Callable → Cloud Function [Auth ID token in Authorization header]
  → summarizer Cloud Run (X-API-Key) [TLS]
  → OpenRouter API (OPENROUTER_API_KEY) [TLS]
  → webhook POST back to Cloud Function (HMAC-signed) [TLS]
  → Firestore summaries/{videoId} (markdown content at rest)
  → Android Firestore listener → rendered markdown
```

---

## 1) Executive Summary

**Merge Recommendation:** APPROVE_WITH_COMMENTS

**Rationale:**
The branch handles secrets correctly in the critical path (keys via Secret Manager, never hardcoded, not logged). The main privacy concern is a LOW-severity uid leak in an Android debug log (`Log.d`) and a MED-severity design decision to persist the per-summary `webhookSecret` in Firestore alongside the summary document. No PII is logged on the backend; no tokens appear in URLs. The `__BOOTSTRAP_UID__` sentinel in `firestore.rules` is the intended pre-deploy placeholder and is documented as such — not a real uid commit.

**Overall Privacy Posture:**
- Data Minimization: Good — logs contain videoId and status counts only
- Storage Security: Adequate — Firestore at rest, secrets via Secret Manager; per-summary HMAC secret stored in the summary doc itself (see PRIV-02)
- Transmission Security: Strong — all channels TLS; HMAC signature on webhook; Bearer token for daemon; API key in `X-API-Key` header (not URL)
- Logging Hygiene: Mostly Clean — one Android debug log emits the Firebase uid (PRIV-01); backend logs are clean
- User Rights: N/A — single-operator system; no multi-user deletion requirements
- Third-Party Risk: Low — OpenRouter receives YouTube video content (video titles + transcript text) to generate summaries; no identity data sent

---

## 2) Data Inventory

| Field | Sensitivity | Where stored | Logged? | Notes |
|-------|-------------|--------------|---------|-------|
| Firebase Auth uid | Sensitive (identifier) | Firebase Auth only | YES — Android `Log.d` (PRIV-01) | Not stored in Firestore directly |
| Google Sign-In ID token | Highly Sensitive (auth) | Not persisted | No | Consumed in-memory by FirebaseAuthBridge |
| `SUMMARIZER_API_KEY` | Secret | Secret Manager | No | Used in `X-API-Key` header |
| `SUMMARIZE_TOKEN` | Secret | Secret Manager | No | CLI `--token` arg (PRIV-03) |
| `OPENROUTER_API_KEY` | Secret | Secret Manager | No | Forwarded to daemon env |
| `webhookSecret` (per-summary) | Operational secret | Firestore `summaries/{videoId}.webhookSecret` | No | PRIV-02 — stored with the doc it protects |
| Video titles / playlist titles | Less Sensitive (public) | Firestore | No | Public YouTube metadata |
| Summary markdown content | Less Sensitive (derived) | Firestore `summaries/{videoId}.content` | No | Sent to OpenRouter (PRIV-04) |
| `errorMessage` from summarizer | Less Sensitive | Firestore `summaries/{videoId}.errorMessage` | No | Truncated to 256 chars |
| Quota `recentTimestamps` | Not sensitive | Firestore `quota/openrouter` | No | Epoch-ms integers |
| `ALLOWED_UID` config value | Sensitive (operator uid) | Firebase Functions config | No (defineString, not logged) | Set via `.env` file or Secret Manager |
| Firestore rules uid (`__BOOTSTRAP_UID__`) | Sensitive | `backend/firestore.rules` | — | Sentinel only; must be replaced before deploy (PRIV-05) |

---

## 3) Findings Table

| ID | Severity | Confidence | Category | File:Line | Summary |
|----|----------|------------|----------|-----------|---------|
| PRIV-01 | LOW | High | PII in Logs | `authViewModel.kt:1056` | Firebase uid logged in `Log.d` (debug-level) |
| PRIV-02 | MED | High | Storage Design | `models/index.ts:22`, `dispatch.ts:119` | `webhookSecret` persisted in Firestore doc it protects |
| PRIV-03 | LOW | High | Secret Exposure | `entrypoint.js:47-51` | `SUMMARIZE_TOKEN` passed as CLI argument; visible in `/proc/<pid>/cmdline` |
| PRIV-04 | LOW | Med | Third-Party Data | `dispatch.ts:125-132` | Video content (URL → transcript) sent to OpenRouter; no policy documented |
| PRIV-05 | NIT | High | Sentinel Hygiene | `firestore.rules:7` | `__BOOTSTRAP_UID__` sentinel correct in code, but deploy runbook must enforce replacement |

**Findings Summary:**
- BLOCKER: 0
- HIGH: 0
- MED: 1
- LOW: 3
- NIT: 1

---

## 4) Findings (Detailed)

### PRIV-01: Firebase uid logged at `Log.d` in AuthViewModel [LOW]

**Location:** `android/app/src/main/java/com/github/jayteealao/playster/screens/auth/authViewModel.kt:1056`

**Code:**
```kotlin
Log.d(TAG, "Bridged to Firebase Auth uid=$uid")
```

**Privacy Issue:**
The Firebase Auth uid is the primary operator identifier in this single-tenant system (`ALLOWED_UID`). Logging it at `Log.d` (DEBUG level) means it appears in Android logcat output and in any bug-report captures. For a single-operator personal tool this is low risk, but uid exposure in logs is against best practice for any auth identifier.

**Removed logs (positive):** Prior diff lines show `Log.d("Signed in as " + googleAccount.email)` and `Log.d("Signed in as " + oneTapCredential.displayName)` were **deleted** — that's a positive cleanup. The uid line is a leftover from the new bridge.

**Severity:** LOW — single-operator system; uid is not equivalent to PII for a personal tool but represents an auth identifier.
**Confidence:** High

**Remediation:**
```kotlin
// Option A: remove entirely (preferred for production builds)
// Option B: replace with truncated hash for correlation
Log.d(TAG, "Bridged to Firebase Auth uid=${uid.take(8)}…")
```
Or gate on `BuildConfig.DEBUG`:
```kotlin
if (BuildConfig.DEBUG) Log.d(TAG, "Bridged to Firebase Auth uid=$uid")
```

---

### PRIV-02: Per-summary `webhookSecret` persisted in the same Firestore doc it protects [MED]

**Location:** `backend/functions/src/models/index.ts:22`, `backend/functions/src/summarizer/dispatch.ts:106-119`

**Code:**
```typescript
// models/index.ts
export interface SummaryDocument {
  webhookSecret: string;   // ← stored in summaries/{videoId}
  ...
}

// dispatch.ts:106-119
const webhookSecret = randomBytes(32).toString("hex");
await db.runTransaction(async (tx) => {
  const next: SummaryDocument = {
    ...
    webhookSecret,          // ← written to Firestore
  };
  tx.set(summaryRef, next);
});
```

**Privacy/Security Issue:**
The HMAC secret is used in `webhook.ts` to verify the incoming webhook from the summarizer. It is read from `summaries/{videoId}.webhookSecret` at verification time. Storing the secret in the same document means:
1. Any code or admin SDK call that can read `summaries/{videoId}` also gets the secret.
2. The Android client can read `summaries/{videoId}` (per Firestore rules). The `webhookSecret` is therefore exposed to the Android client over TLS, which is not a breach but violates the principle of minimal exposure.
3. If a Firestore backup/export is shared, HMAC secrets are included.

**Firestore rules context:** The rules allow the allowlisted uid to read `summaries/{document=**}`, so the operator's Android app receives `webhookSecret` as part of every Firestore snapshot.

**Severity:** MED — single-tenant system; the uid who can read the secret is the same operator who owns the system. The practical attack surface is narrow, but the design violates least-privilege storage.
**Confidence:** High

**Remediation options:**

Option A (preferred): Store the secret out-of-band in a separate locked collection `webhook_secrets/{videoId}` that Android rules deny:
```
match /webhook_secrets/{document=**} { allow read, write: if false; }
```
Backend reads the secret via Admin SDK (bypasses rules). Android never receives it.

Option B (minimal): Strip `webhookSecret` from the Firestore document returned to the Android client using a Firestore field-mask in the listener. Field masks are not enforced by Firestore security rules, so this requires an architecture change.

Option C (no-change accepted-risk): Document as accepted risk for single-tenant v1; add a TODO to move secrets to a `webhook_secrets/` subcollection before any multi-tenant expansion.

---

### PRIV-03: `SUMMARIZE_TOKEN` passed as CLI argument — visible in process table [LOW]

**Location:** `summarizer/deploy/entrypoint.js:43-52`

**Code:**
```javascript
const daemon = spawn(
  process.execPath,
  ["/opt/daemon/dist/cli.js", "daemon", "run", "--token", token, "--port", "8787"],
  { stdio: ["ignore", "inherit", "inherit"], env: process.env },
);
```

**Privacy Issue:**
The `SUMMARIZE_TOKEN` bearer is passed as the `--token` CLI argument to the daemon child process. On Linux (Cloud Run), this is visible in `/proc/<pid>/cmdline` to any process in the same container namespace. Since this is a single-container deployment with no sidecar, the practical blast radius is low — there are no other processes that could read it — but it is a security/privacy anti-pattern.

**Severity:** LOW — Cloud Run containers share no process namespace with other tenants; risk is theoretical in this deployment model.
**Confidence:** High

**Remediation:**
Use an environment variable instead of a CLI flag (the daemon may already support `SUMMARIZE_TOKEN` env-var — check daemon docs at `0ec12ac`):
```javascript
const daemon = spawn(
  process.execPath,
  ["/opt/daemon/dist/cli.js", "daemon", "run", "--port", "8787"],
  {
    stdio: ["ignore", "inherit", "inherit"],
    env: { ...process.env, SUMMARIZE_TOKEN: token },
  },
);
```

---

### PRIV-04: Video transcript/content sent to OpenRouter without documented data retention or policy [LOW]

**Location:** `backend/functions/src/summarizer/dispatch.ts:125-132`

**Data flow:**
```
dispatch.ts → summarizer Cloud Run (entrypoint.js) → daemon
  → YouTube URL → transcript extraction (yt-dlp / youtubei)
  → OpenRouter API (model="free") with transcript content
  → markdown summary → webhook → Firestore
```

**Privacy Issue:**
The summarizer sends the video's transcript (and fallback `shortDescription`) to OpenRouter as LLM input. OpenRouter's free-tier models are operated by third-party providers (Mistral, Google, Meta, etc. — the rotation is dynamic via `/v1/refresh-free`). OpenRouter's data retention and training policies for free-tier requests should be reviewed.

For this single-operator personal tool, the data is the operator's own YouTube library content (primarily public or private-but-owned videos). This is low risk for PII leakage, but:
- Video descriptions may include email addresses, phone numbers, or personal details if they are unlisted personal videos.
- The shape doc notes no data retention assumption is documented.

**Severity:** LOW — content is the operator's own YouTube data; no third-party user PII.
**Confidence:** Med (depends on what videos the operator summarizes)

**Remediation:**
Document in `docs/architecture/summarize-pipeline.md`:
- OpenRouter data retention policy citation (link to their Terms of Service).
- Note that video content (transcripts, descriptions) is sent to third-party LLM providers.
- Recommend operator avoid summarizing videos containing sensitive personal data (medical, financial, etc.).

---

### PRIV-05: `__BOOTSTRAP_UID__` sentinel correct but deploy runbook must enforce replacement [NIT]

**Location:** `backend/firestore.rules:7`, `backend/functions/src/auth/verify.ts:15`

**Code:**
```
// firestore.rules
function isAllowlisted() {
  return request.auth != null && request.auth.uid == "__BOOTSTRAP_UID__";
}

// verify.ts
export const ALLOWED_UID = defineString("ALLOWED_UID", {
  default: "__BOOTSTRAP_UID__",
});
```

**Assessment:**
The sentinel string `__BOOTSTRAP_UID__` is the documented, intentional placeholder. The shape doc (§ Verification Strategy) calls out the uid-capture step as a human-in-the-loop prerequisite. No real uid is committed anywhere in the diff — this is correct. The `verify.ts` default also uses the sentinel, so first-deploy denies all callers until the real uid is configured.

**Risk:** The Maestro helpers (`.js` seed scripts) and `write-cached-summary.js` use fixture IDs (`PL_TEST_1`, `VID_CACHED`) — no real uids embedded. The helper scripts target the Firebase Emulator only (`FIRESTORE_EMULATOR_HOST`).

**Severity:** NIT — no action required in code; operational runbook item only.

**Recommendation:**
Add an assertion to the CI deploy script:
```bash
if grep -q '__BOOTSTRAP_UID__' backend/firestore.rules; then
  echo "ERROR: firestore.rules still contains sentinel; set real ALLOWED_UID before deploy"
  exit 1
fi
```

---

## 5) Positive Findings (Privacy-Preserving Patterns)

- **No tokens in URLs:** `SUMMARIZER_API_KEY` is sent via `X-API-Key` header; Firebase Auth ID token is in the Firebase SDK bearer; no secret appears in a query param or URL path.
- **No tokens in logs:** Backend `firebase-functions/logger` calls log only `videoId`, `model`, `httpStatus`, error messages — never API keys, HMAC secrets, or Auth tokens.
- **Replay protection:** Webhook HMAC uses Stripe-style `t=<unix>,v1=<hex>` with 300s replay window and `timingSafeEqual` — protects against token reuse.
- **Per-summary secret:** Each dispatch gets `randomBytes(32)` — a leaked secret from one summary cannot be used to forge another.
- **Firestore rules:** `tokens/` collection is fully denied to all clients (read+write: false). `summaries/` and `quota/` are read-only to the allowlisted uid; writes only via Admin SDK.
- **No email/name/phone in any Firestore path or log:** The data model handles videoIds, playlist IDs, status enums, counts, and markdown content — no personal contact information.
- **Removed PII from old logs:** Prior auth code logged `googleAccount.email` and `displayName` — both deleted in this diff.
- **Maestro fixtures use synthetic IDs only:** `VID_CACHED`, `PL_TEST_1`, `VID_NO_SUMMARY`, `VID_ANY` — no real user data baked into test fixtures.
- **`webhookSecret` in `write-cached-summary.js` is literal string `"fixture-secret"`** — not a real secret, appropriate for an emulator-only fixture.

---

## 6) Data Retention Assessment

No explicit retention policy is implemented for:
- `summaries/{videoId}` — kept indefinitely (single-operator tool; acceptable)
- `quota/openrouter` — single doc, overwritten daily; no retention concern
- `locks/summaryDispatcher` — single doc, overwritten; no retention concern

**Assessment:** Acceptable for a single-tenant personal tool. Would need a deletion UI and policy before multi-tenant expansion.

---

## 7) Third-Party Data Sharing Summary

| Third Party | What is sent | Mechanism | Identity data? |
|-------------|-------------|-----------|----------------|
| OpenRouter (+ downstream LLM providers) | YouTube video URL + transcript/description content | HTTPS POST from summarizer daemon | No — video content only |
| Firebase (Google) | Firebase Auth session, Firestore read/write ops | Firebase SDK (TLS) | Operator uid for Auth only; no PII in Firestore payloads |
| YouTube (for sync) | YouTube OAuth token (server-side only; not from Android device) | backend `innertube-sync.ts` | OAuth token (server-side) |

---

## 8) Compliance

This is a single-operator personal tool; no formal GDPR/CCPA obligations to end-users. The operator is the sole data subject and system owner. Privacy good-practice assessment only.

---

## 9) Recommendations by Priority

### Medium Priority (address before shipping)
1. **PRIV-02:** Decide on webhookSecret storage. At minimum, document as accepted risk in architecture doc. Preferred: move to `webhook_secrets/` subcollection inaccessible to Android client.

### Low Priority (good hygiene)
2. **PRIV-01:** Gate `Log.d(uid)` on `BuildConfig.DEBUG` or replace with truncated hash.
3. **PRIV-03:** Pass `SUMMARIZE_TOKEN` via environment variable rather than CLI `--token` flag.
4. **PRIV-04:** Document OpenRouter data handling policy in architecture doc.

### NIT (operational)
5. **PRIV-05:** Add CI sentinel-check assertion to deployment script.

---

*Review completed: 2026-05-21*
*Scope: git diff main...HEAD (232 files, +16,714 / -1,212 lines)*

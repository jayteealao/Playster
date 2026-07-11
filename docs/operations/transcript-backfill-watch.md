# Transcript backfill watch

The `transcriptBackfillCron` function runs hourly and processes up to 50 videos
per tick, fetching captions from YouTube and storing them in GCS. It uses two
fetch paths: a primary path and an ANDROID-client timedtext fallback. Each
outcome is recorded in a Firestore pointer doc under `transcripts/{videoId}` and
a structured log line under Cloud Logging.

This guide covers the operator actions that are most frequently needed:
forcing a run, reading the logs, interpreting error classes, and responding to
authentication degradation.

All `gcloud` and `gsutil` commands assume you are authenticated against
`playster-406121`. All `firebase` commands run from the `backend/` directory.

---

## 1. Force a backfill run

The cron runs on an hourly schedule. To trigger it immediately without waiting
for the schedule:

```bash
gcloud scheduler jobs run \
  firebase-schedule-transcriptBackfillCron-us-central1 \
  --location us-central1 \
  --project playster-406121
```

Wait approximately 2 minutes for the batch to complete before reading logs.

---

## 2. Read transcriptBackfillCron logs

### All activity for the most recent run

```bash
gcloud logging read \
  'resource.type="cloud_function"
   logName="projects/playster-406121/logs/cloudfunctions.googleapis.com%2Fcloud-functions"
   jsonPayload.message=~"fetchTranscript"' \
  --project playster-406121 \
  --limit 100 \
  --format json
```

### Successful completions only

```bash
gcloud logging read \
  'resource.type="cloud_function"
   jsonPayload.message="fetchTranscript: complete"' \
  --project playster-406121 \
  --limit 50 \
  --format json
```

Each `fetchTranscript: complete` line carries a `source` field:
- `"youtubei"` — primary path succeeded.
- `"android-timedtext"` — primary path was blocked; ANDROID-client fallback
  produced the transcript.

### Failed fetches only

```bash
gcloud logging read \
  'resource.type="cloud_function"
   jsonPayload.message="fetchTranscript: fetch failed"' \
  --project playster-406121 \
  --limit 50 \
  --format json
```

Failed lines carry `errorClass`, `fallbackEngaged`, and (for HTTP errors)
`httpStatus`. See the table in Section 3 for interpretation.

### 24-hour recurring-400 watch query (AC7 threshold check)

Run this query to check for the INNERTUBE_4XX pattern over a time window. Pass
`--freshness` or adjust the timestamp filter to the window you want:

```bash
gcloud logging read \
  'resource.type="cloud_function"
   logName="projects/playster-406121/logs/cloudfunctions.googleapis.com%2Fcloud-functions"
   jsonPayload.message="fetchTranscript: fetch failed"
   jsonPayload.errorClass="INNERTUBE_4XX"' \
  --project playster-406121 \
  --limit 200 \
  --format json
```

**Threshold:** if this query returns entries for **≥ 3 distinct `videoId` values**
across the 24-hour window with `fallbackEngaged: true`, the ANDROID fallback is
not absorbing the errors and the PoToken escalation decision is triggered (see
Section 5). Zero occurrences is the pass condition for the sustained-health
watch.

Record the count and distinct videoId count in any verdict report — absence of
400s is only meaningful if the sampling mechanism ran throughout the window.

---

## 3. Error class interpretation

Every failed fetch writes one of the following `errorClass` values to both the
log line and the Firestore pointer doc (`transcripts/{videoId}.errorClass`).

| errorClass | Meaning | Pointer doc status | Operator action |
|---|---|---|---|
| `INNERTUBE_4XX` | YouTube's primary API returned a 4xx HTTP error (typically 400). The primary path is blocked; the ANDROID-client fallback was attempted. | `transient` (will be retried) | Check `fallbackEngaged`. If `false`, fallback was not attempted (unusual). If `true` and still failing, see Section 4. |
| `PANEL_NOT_FOUND` | No caption panel was found on either path. Increments a counter; flips to `unavailable` after 3 consecutive hits. | `transient` → `unavailable` (at count 3) | Expected for videos with no captions. Monitor `panelNotFoundCount` field; counter resets to 0 on any other error class. |
| `LOGIN_REQUIRED` | YouTube returned a sign-in or private-video error. Implicates the `tokens/innertube` credentials. | `unavailable` | See Section 4 — cookies refresh procedure. |
| `EMPTY_TIMEDTEXT` | The ANDROID-client timedtext endpoint returned HTTP 200 with an empty body. YouTube may be enforcing PoToken policy for empty-200 responses. | `transient` | Treat as PoToken enforcement if widespread. See Section 4 (PoToken path). |
| `PARSE_FAILURE` | The response body could not be parsed (JSON syntax error). | `transient` | Typically a transient malformed response; will be retried. If persistent, check the Cloud Logging `error` field for the raw message. |
| `UNKNOWN` | Error did not match any known pattern. | `transient` | Read the `error` field on the log line for the raw error message. File an issue if recurring. |

**`fallbackEngaged` field:**
- `false` — the primary path failed; the ANDROID fallback was not attempted
  (error class was not fallback-eligible, e.g., `PANEL_NOT_FOUND`).
- `true` — the primary path failed and the ANDROID fallback was attempted. The
  `errorClass` on the same log line reflects the *fallback's* outcome, not the
  primary path's.

---

## 4. Distinguish cookies vs PoToken — and what to do

### Login-required errors → cookies stale

**Signal:** `errorClass: "LOGIN_REQUIRED"` appearing on multiple videos.

This means the `tokens/innertube` credentials stored in Firestore are expired or
revoked. The fix is to re-run the TV OAuth setup flow, which refreshes the
cached session.

```bash
# From backend/functions/
node scripts/setup-tv-oauth.mjs
```

Follow the device-code prompt (TV login). After completion, the new tokens are
written to Firestore and the next cron tick uses them. Force a run (Section 1)
to confirm the fix.

### INNERTUBE_4XX with fallbackEngaged: true → PoToken enforcement on fallback

**Signal:** `errorClass: "INNERTUBE_4XX"` with `fallbackEngaged: true` on
≥ 3 distinct videoIds over 24h.

This means YouTube is enforcing the same PoToken policy against ANDROID-client
requests from GCP datacenter IPs. The ANDROID fallback is not absorbing the
blocked primary-path requests.

**Immediate check:**

1. Read the pointer docs for the affected videoIds:
   ```bash
   firebase firestore:get transcripts/<videoId> --project playster-406121
   ```
   Confirm `status: "transient"` and `errorClass: "INNERTUBE_4XX"`.

2. Check for empty-200 PoToken marker as well:
   ```bash
   gcloud logging read \
     'resource.type="cloud_function"
      jsonPayload.errorClass="EMPTY_TIMEDTEXT"' \
     --project playster-406121 \
     --limit 20 \
     --format json
   ```
   An `EMPTY_TIMEDTEXT` pattern alongside `INNERTUBE_4XX` strengthens the
   PoToken enforcement diagnosis.

3. If PoToken enforcement is confirmed, record the verdict and proceed to the
   escalation path (Section 5).

### INNERTUBE_4XX with fallbackEngaged: false → fallback not attempted

This is unusual. Check whether the primary-path error message (the `error` field
on the log line) is actually a 4xx. If the error pattern classification
misfired, file an issue with the raw error text.

---

## 5. PoToken escalation path

If the 24-hour watch confirms that the ANDROID fallback is also blocked from GCP
datacenter IPs (≥ 3 distinct INNERTUBE_4XX with `fallbackEngaged: true`), the
recorded next step is a PoToken sidecar.

**The sidecar approach** involves running a BgUtils service on Cloud Run that
generates valid PoTokens for each request, bypassing the datacenter-IP
enforcement. This work is tracked as a separate issue; it is out of scope for
the current transcript-fetch fix.

To file or track the escalation, open an issue in the project's GitHub issue
tracker with:
- The final verdict from the 24-hour watch (count, distinct videoIds, timestamp
  window).
- Sample log entries (redact any non-public identifiers).
- Label: `transcript-potoken-escalation`.

The PoToken sidecar is the only known remediation path for datacenter-IP
enforcement. Do not mark the transcript feature as "broken" before completing the
watch — the ANDROID fallback may be sufficient for the majority of the backlog.

---

## 6. Confirm a successful run (GCS + Firestore)

After a forced run (Section 1) or after the fix is deployed:

**Check for new GCS blobs:**
```bash
gsutil ls -l gs://playster-406121-transcripts | tail -10
```

A new blob with a recent timestamp at `transcripts/<videoId>.txt` confirms the
blob write succeeded.

**Read a pointer doc:**
```bash
firebase firestore:get transcripts/<videoId> --project playster-406121
```

A passing pointer doc has:
- `status: "available"`
- `source: "youtubei"` or `"android-timedtext"`
- non-empty `signedUrl`

**Check for INNERTUBE_4XX in the batch (zero expected on a clean run):**
```bash
gcloud logging read \
  'resource.type="cloud_function"
   jsonPayload.errorClass="INNERTUBE_4XX"' \
  --project playster-406121 \
  --limit 10 \
  --format json
```

Zero results is the pass condition for the forced-run proof. Any result here
warrants reading `fallbackEngaged` and following Section 4.

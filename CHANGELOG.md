# Changelog

All notable breaking changes to externally-callable interfaces are recorded here.

---

## [Unreleased]

---

## [0.2.1] - 2026-07-14

### Non-breaking — release pipeline: post-publish health gate works under Workload Identity Federation

The release workflow's post-publish verification was repaired and hardened. The
previous authenticated `/health` probe could never pass under Workload Identity
Federation (the summarizer runs `--no-allow-unauthenticated` and WIF cannot mint
an id-token for it); it is replaced by a `gcloud run services describe`
revision-readiness assertion, and the summarizer deploy now carries a `/health`
startup probe so a revision reaches Ready only after `/health` returns 200. Three
post-publish checks were added (Firestore composite-index readiness,
`summaryDispatcher` health, and transcript signed-URL generation — the last a
non-failing stub pending a concrete probe).

**No breaking changes:** this release touches only the CI/release pipeline
(`.github/workflows/release.yml`). No externally-callable interface, Firestore
field contract, or GCS blob format changed, and no IAM grant was added (the
summarizer stays `--no-allow-unauthenticated`).

---

## [0.2.0] - 2026-07-11

### Non-breaking — transcript backfill: dual-path fetch with error classification

`fetchTranscript` now attempts a primary caption fetch and, when that is blocked
by a YouTube 4xx response, automatically retries via an ANDROID-client timedtext
fallback. The GCS blob format is unchanged; the only new field on an `available`
pointer doc is `source` (`"youtubei"` or `"android-timedtext"`), which is
strictly additive.

A declarative error classification table assigns every failure a stable
`errorClass` code (`INNERTUBE_4XX`, `PANEL_NOT_FOUND`, `LOGIN_REQUIRED`,
`EMPTY_TIMEDTEXT`, `PARSE_FAILURE`, `UNKNOWN`). This field is written to both
the Cloud Logging structured log line and the Firestore pointer doc and is used
by the production-watch log queries.

A consecutive PANEL_NOT_FOUND counter guards against infinite retry on videos
that genuinely lack captions: after 3 consecutive hits the pointer doc flips
from `transient` to terminal `unavailable` and the backfill cron skips it on
subsequent ticks.

The InnerTube TV-OAuth client library was modernized to align with the current
API; the Watch Later paged-walk behavior is unchanged.

**No breaking changes:** the Firestore pointer-doc field contract and the GCS
blob format are backward-compatible. New fields (`source`, `errorClass`,
`panelNotFoundCount`) are additive; any consumer that previously ignored unknown
fields will continue to work without modification.

Operator runbook: `docs/operations/transcript-backfill-watch.md`.

---

### Breaking — backend sync endpoints converted from onRequest to onCall

`syncAllPlaylists`, `syncPlaylist`, and `syncWatchLater` were HTTP `onRequest`
functions and are now Firebase callable (`onCall`) functions wrapped in the
`allowlistedCall` allowlist gate.

**Impact:** Any prior admin `curl` scripts that POST to the function URLs no
longer work. The new endpoints expect a Firebase Functions client-SDK call (ID
token in the `Authorization` header, JSON body `{"data": {...}}`).

**Migration:** Use the Firebase Functions client SDK from Android/web, or run
locally via `firebase functions:shell` (`syncAllPlaylists({})`).

---

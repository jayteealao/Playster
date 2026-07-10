# Summarizer deploy artifacts

This directory holds the deploy- and verification-time artifacts for the
unified summarizer container — the Cloud Run-bound multi-stage Dockerfile,
the Node entrypoint, the docker-compose harness, and a `mock-backend`
webhook receiver used by the harness.

## Files

| Path | Purpose |
|---|---|
| `Dockerfile` | Three-stage build (daemon, api, runtime). Used by `gcloud builds submit` and by `docker compose build`. |
| `entrypoint.js` | Cloud Run entrypoint. Boots the daemon, waits for `/health`, calls `/v1/refresh-free`, then imports the gateway in-process. |
| `docker-compose.yml` | Local harness stack: `summarizer` (built from `Dockerfile`) + `mock-backend` (webhook receiver) on a `harness` bridge network. |
| `mock-backend/server.js` | Tiny Node service that verifies signatures and persists captured payloads under `verify-artifacts/`. |
| `fixtures/captioned.json` | YouTube fixture with captions. Drives AC-6 (signature) evidence. |
| `fixtures/no-caption.json` | YouTube fixture without captions. Drives AC-14 (shortDescription fallback) evidence. |
| `run-harness.mjs` | One-shot driver: build, up, dispatch both fixtures, verify signatures + replay rejection, tear down. |
| `CLOUD-RUN.md` | Cloud Run deploy + rollback runbook. |
| `summarize-daemon.service`, `summarize-api.service`, `lxc-deploy.sh`, `summarize.env` | Legacy systemd / LXC artifacts kept for reference. Not used in the Cloud Run path. |

## Local harness

The harness exercises the same image Cloud Run will run, plus a signature-
verifying mock receiver. It verifies:

- **AC-6** — webhook delivered with `X-Summarizer-Signature: t=<unix>,v1=<hex>` that verifies against the test secret.
- **AC-14** — no-caption fixture completes with a non-empty `shortDescription`-derived summary.
- **AC-16** — both processes reach `/health` 200 within 60 s of `up`.
- **Slice-local replay AC** — a replay (timestamp shifted past 300 s) is rejected with 401.
- **Slice-local yt-dlp AC** — `yt-dlp --version` inside the image is ≥ 2026.02.21.

From the repo root:

```sh
node summarizer/deploy/run-harness.mjs           # full run + tear-down
node summarizer/deploy/run-harness.mjs --keep    # leave the stack up for inspection
```

The driver writes assertions and outcomes to `summarizer/deploy/harness.log`
and stores captured webhook payloads in `summarizer/deploy/verify-artifacts/`.

## Subtree refresh

The daemon source lives at `summarizer/summarize-daemon/`, vendored via
`git subtree`. The pin SHA is recorded in
`summarizer/summarize-daemon/SUBTREE_PIN.md`. To refresh, see the procedure
in that file — then re-run the harness before merging.

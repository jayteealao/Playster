# Cloud Run deploy runbook — summarizer

This is the deploy procedure for the unified summarizer container
(`summarizer/deploy/Dockerfile`). The container runs both the
`summarize-api` Fastify gateway and the vendored `summarize-daemon` as one
Cloud Run service.

## Prerequisites

- GCP project provisioned. Operator has `roles/run.admin`,
  `roles/artifactregistry.admin`, and `roles/secretmanager.admin`.
- Artifact Registry Docker repo at
  `<REGION>-docker.pkg.dev/<PROJECT>/playster/`.
- Secret Manager entries populated:
  - `SUMMARIZER_API_KEY` — 32-byte hex; the gateway's `X-API-Key`. The
    backend's `summarizer/dispatch.ts` reads this via secret-binding.
  - `SUMMARIZE_TOKEN` — 32-byte hex; bearer the gateway uses to call the
    daemon inside the container.
  - `OPENROUTER_API_KEY` — operator's OpenRouter key, after the one-time
    $10 credit purchase. Powers the daemon's `model: "free"` resolution
    via OpenRouter's free-tier model rotation.
  - `GROQ_API_KEY` — required for the daemon's no-caption YouTube
    fallback. The daemon's `tryWebTranscript` (HTML `captionTracks`
    scrape) handles captioned videos without any ASR; videos without
    captions fall through to `yt-dlp` + an ASR provider, and Groq is
    the fastest free-tier ASR (Whisper Large). The daemon also accepts
    `ASSEMBLYAI_API_KEY`, `GEMINI_API_KEY`, `OPENAI_API_KEY`, FAL keys,
    or local whisper-cpp as alternative providers.
- `docker` and `gcloud` CLIs available locally. `gcloud auth login` and
  `gcloud config set project <PROJECT>` completed.

## Build

Buildpacks cannot install ffmpeg, tesseract, or yt-dlp, so we use a
custom `Dockerfile` via Cloud Build. Local `docker push` is documented
below as a secondary path.

### Primary: Cloud Build

From the repo root:

```sh
gcloud builds submit \
  --tag <REGION>-docker.pkg.dev/<PROJECT>/playster/summarizer:<TAG> \
  -f summarizer/deploy/Dockerfile \
  .
```

Replace `<TAG>` with a release identifier (e.g. `v2026.05.18`,
`<git-sha>`).

### Secondary: local docker push

```sh
docker build -f summarizer/deploy/Dockerfile \
  -t <REGION>-docker.pkg.dev/<PROJECT>/playster/summarizer:<TAG> .
gcloud auth configure-docker <REGION>-docker.pkg.dev
docker push <REGION>-docker.pkg.dev/<PROJECT>/playster/summarizer:<TAG>
```

## Deploy

```sh
gcloud run deploy summarizer \
  --image <REGION>-docker.pkg.dev/<PROJECT>/playster/summarizer:<TAG> \
  --region <REGION> \
  --no-allow-unauthenticated \
  --set-secrets \
      API_KEYS=SUMMARIZER_API_KEY:latest,\
      SUMMARIZE_TOKEN=SUMMARIZE_TOKEN:latest,\
      OPENROUTER_API_KEY=OPENROUTER_API_KEY:latest,\
      GROQ_API_KEY=GROQ_API_KEY:latest \
  --memory 2Gi \
  --cpu 2 \
  --timeout 600 \
  --min-instances 0 \
  --max-instances 2 \
  --execution-environment gen2
```

Notes:

- `--no-allow-unauthenticated` is required — the backend's
  `summarizer/dispatch.ts` calls this URL with the `X-API-Key` gateway
  header, but Cloud Run still wants IAM-level invoker permission. Grant
  the backend's runtime service account `roles/run.invoker` on this
  service. Cloud Scheduler triggers use their own service account.
- `--memory 2Gi` is required for ffmpeg + tesseract + yt-dlp + the
  daemon and gateway processes.
- `--min-instances 0` accepts the cold-start cost (~3 s daemon boot +
  5–10 s `/v1/refresh-free`). Flip to `1` via
  `gcloud run services update summarizer --min-instances 1 --region <REGION>`
  if cold-start latency annoys.
- `--execution-environment gen2` is required (the gen1 sandbox restricts
  process-spawn semantics we depend on).

## Verify

```sh
SERVICE_URL=$(gcloud run services describe summarizer \
  --region <REGION> --format='value(status.url)')

# Health (uses the gateway's /health route; unauthenticated route
# permission is denied at the Cloud Run edge, so use the IAM-aware
# identity-token form).
curl -H "Authorization: Bearer $(gcloud auth print-identity-token)" \
     "${SERVICE_URL}/health"
```

Within a few seconds of the first request the Cloud Run logs should
show the daemon `/health 200`, `/v1/refresh-free dispatched` (if
OPENROUTER_API_KEY is set), and `summarize-api gateway listening`.

## Roll back

```sh
gcloud run revisions list --service summarizer --region <REGION>
gcloud run services update-traffic summarizer \
  --to-revisions=<PREV-REVISION>=100 \
  --region <REGION>
```

## Subtree refresh + redeploy

When `summarizer/summarize-daemon/SUBTREE_PIN.md` is bumped, run the
local docker-compose harness before tagging a new build:

```sh
node summarizer/deploy/run-harness.mjs
```

Only proceed to `gcloud builds submit` once the harness logs
`harness passed`.

## Cost notes

- `--min-instances 0` is $0 idle.
- `--min-instances 1` runs ~$15/mo on 2 vCPU / 2 GiB.
- Cloud Build uses 1000 free build-minutes/month; well under for a
  single-tenant rebuild cadence.
- Artifact Registry storage is $0.10/GB/month — keep ≤ 5 tags around
  for rollback (each ~1 GB compressed).

## Operational hazards

- **First deploy after a fresh project will fail `/v1/refresh-free`**
  because the daemon has no cached free-model list yet. The entrypoint
  tolerates this and continues, but the first summary request may not
  resolve `model="free"` to a concrete model. Warm by re-invoking with
  the same `X-API-Key` after first deploy.
- **YouTube may throttle yt-dlp from Cloud Run egress IPs.** Mitigation
  in v1: the daemon's youtubei-first cascade catches most cases;
  failures fall to `shortDescription`. v1.1 candidate: Cloud NAT +
  static IP.
- **`SUMMARIZE_TOKEN` rotation** rotates the daemon's bearer. After
  rotating the secret, redeploy so the new revision picks up the new
  value — the daemon synthesizes its in-memory config from this token
  at startup.

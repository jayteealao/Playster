# Playster

A single-tenant personal YouTube library with LLM-generated video summaries.

The owner signs in once on Android, and the app renders their YouTube playlists and videos live from Firestore — no YouTube API calls from the device. Tapping a video opens a Summary tab that shows a markdown summary produced by a self-hosted LLM gateway. All API keys are held server-side. Steady-state LLM cost is zero (the system uses OpenRouter free models); a one-time $10 OpenRouter credit purchase is the only direct spend, which unlocks a 1000-summary-per-day quota.

## Who it is for

One person: the repo owner. Access is locked to a single allowlisted Firebase Auth uid throughout — in Firestore security rules, in every Cloud Function, and in the Cloud Run service. No other users can authenticate.

## Components

| Directory | What it is | What it does |
|-----------|-----------|--------------|
| `android/` | Jetpack Compose app | Signs the owner in via Google/Firebase Auth; displays playlists and videos from Firestore live listeners; triggers summary requests; renders returned markdown. |
| `backend/` | Firebase Functions + Firestore rules | Enforces auth and uid allowlist; syncs YouTube playlists/videos to Firestore on a 6-hour schedule or on demand; orchestrates summary dispatch, quota tracking, and a webhook receiver; runs recovery crons (dispatcher, sweeper, retry). |
| `summarizer/` | Cloud Run Fastify gateway + vendored summarize daemon | Receives a summary job from the backend, runs the daemon's YouTube transcript cascade, calls OpenRouter for an LLM summary, and delivers the result back via a signed webhook. |

## How the pieces connect

```
Android ──(Firebase Auth)──▶ backend/functions
Android ◀──(Firestore listeners)── backend/functions
backend/functions ──(HTTPS job dispatch)──▶ summarizer (Cloud Run)
summarizer ──(signed webhook POST)──▶ backend/functions
backend/functions ──(Firestore write)──▶ Android (live listener picks it up)
```

1. The Android app reads Firestore only — it never calls the YouTube Data API directly.
2. The backend syncs YouTube via the server-side YouTube API and writes to Firestore.
3. When a summary is requested (manually or automatically after sync), the backend dispatches a job to the Cloud Run summarizer with an HMAC secret.
4. The summarizer daemon fetches the transcript, calls OpenRouter, then POSTs a Stripe-style signed webhook back to the backend.
5. The backend verifies the signature and writes the markdown to `summaries/{videoId}`. The Android Firestore listener delivers it to the screen without any polling.

## Running tests

Each workspace package has its own test command. To run backend unit and rules tests:

```bash
pnpm --filter functions run test
pnpm --filter functions run test:rules
```

To run summarizer tests:

```bash
pnpm summarizer:test
```

## Documentation

- **Architecture** — data flow, Firestore document shapes, webhook signature scheme, cron timing: [`docs/architecture/summarize-pipeline.md`](docs/architecture/summarize-pipeline.md)
- **Deploy and bootstrap** — prerequisite checklist, secrets provisioning, uid capture, Cloud Run deploy, verification: [`docs/operations/deploy-and-bootstrap.md`](docs/operations/deploy-and-bootstrap.md)

## Status

Active personal project. Single operator, single Google account, single Cloud Run region. Not intended for multi-user or public deployment.

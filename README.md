# Playster

A single-tenant Editorial Reader for a personal YouTube library, with real playback, live-synced transcripts, and LLM-generated video summaries.

The owner signs in once on Android and reads their YouTube playlists as a print-styled publication: a six-screen IA — Home, Playlist, Player, Transcript, Search, Settings — built on a custom editorial design-token system with four selectable paper palettes (Cream, Vellum, Newsprint, and Night as the dark theme). Video plays through YouTube's official embedded IFrame player (no background play, no downloads — YouTube's terms don't allow either for a third-party embed); the Transcript screen renders timestamped prose whose active line follows live playback, with tap-to-seek, per-line highlighting, and timestamped notes. Progress, notes, and highlights are all synced per-user to Firestore, so nothing is lost across devices or reinstalls. Search matches playlist/video titles instantly on-device and finds transcript text via a backend function. Tapping a video's Summary tab shows a markdown summary produced by a self-hosted LLM gateway. All API keys are held server-side. Steady-state LLM cost is zero (the system uses OpenRouter free models); a one-time $10 OpenRouter credit purchase is the only direct spend, which unlocks a 1000-summary-per-day quota.

Five variable-weight font families ship bundled in the APK — Inter Tight, Source Serif 4, EB Garamond, Cormorant Garamond, and Fraunces — all OFL-licensed; their full license texts are readable in-app under Settings.

## Who it is for

One person: the repo owner. Access is locked to a single allowlisted Firebase Auth uid throughout — in Firestore security rules, in every Cloud Function, and in the Cloud Run service. No other users can authenticate.

## Components

| Directory | What it is | What it does |
|-----------|-----------|--------------|
| `android/` | Jetpack Compose app | The Editorial Reader — signs the owner in via Google/Firebase Auth; renders Home/Playlist/Player/Transcript/Search/Settings against live Firestore data; plays video through YouTube's official embed; keeps reading progress, notes, and highlights synced per-user; searches playlist/video titles on-device and transcript text via the backend; requests and renders LLM summaries. |
| `backend/` | Firebase Functions + Firestore rules | Enforces auth and uid allowlist; syncs YouTube playlists/videos to Firestore on a 6-hour schedule or on demand; orchestrates summary dispatch, quota tracking, and a webhook receiver; runs recovery crons (dispatcher, sweeper, retry); serves the transcript-search callable behind Search. |
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
6. In parallel, the backend captures each video's caption transcript (via `youtubei.js`), stores it as a Cloud Storage blob with a Firestore pointer at `transcripts/{videoId}`, and the app renders it as the Transcript screen's timestamped reading surface, synced live to playback. Cached transcripts also let the backend re-summarize a video without re-fetching. See [`docs/architecture/summarize-pipeline.md#10-transcript-pipeline`](docs/architecture/summarize-pipeline.md#10-transcript-pipeline).
7. The app also writes its own per-user reading data — playback progress, notes, and highlights — directly to Firestore, secured by rules to the signed-in owner's own paths; the backend never touches these collections. See [`docs/architecture/user-collections-and-search.md`](docs/architecture/user-collections-and-search.md).

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

- **Architecture (summarize pipeline)** — data flow, Firestore document shapes, webhook signature scheme, cron timing: [`docs/architecture/summarize-pipeline.md`](docs/architecture/summarize-pipeline.md)
- **Architecture (reading data + search)** — the `progress`/`notes`/`highlights` collections, the transcript-search callable, summary chapters: [`docs/architecture/user-collections-and-search.md`](docs/architecture/user-collections-and-search.md)
- **Architecture (editorial design system)** — the design-token layer and the YouTube-embed compliance constraints behind the Player/Transcript: [`docs/architecture/editorial-design-system.md`](docs/architecture/editorial-design-system.md)
- **Deploy and bootstrap** — prerequisite checklist, secrets provisioning, uid capture, Cloud Run deploy, verification: [`docs/operations/deploy-and-bootstrap.md`](docs/operations/deploy-and-bootstrap.md)

## Status

Active personal project. Single operator, single Google account, single Cloud Run region. Not intended for multi-user or public deployment.

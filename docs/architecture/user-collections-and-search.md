# User collections, transcript search, and summary chapters

Reference for the per-user Firestore collections (`progress`, `notes`,
`highlights`), the `searchTranscripts` callable, and the `chapters` field on
summary documents. TypeScript source of truth for every shape:
`backend/functions/src/models/index.ts`.

## Per-user collections

All three live under `users/{uid}/…` so ownership is a path fact — security
rules check `request.auth.uid == userId` without reading document data, and
every query is collection-scoped to one user.

### `users/{uid}/progress/{docId}`

One collection holds two data kinds behind a `kind` discriminator:

| kind | doc id | fields | consumer |
|------|--------|--------|----------|
| `video` | the videoId | `videoId`, `playlistId`, `positionSeconds`, `durationSeconds`, `updatedAt` | Continue-watching headliner; per-video resume |
| `playlist` | the playlistId | `playlistId`, `lastOpenedAt`, `updatedAt` | Home shelf ordering (last-opened desc) |

Deterministic doc ids make progress writes idempotent upserts: two devices
writing position for the same video converge on one doc, last write wins.

### `users/{uid}/notes/{autoId}`

Timestamped note anchored to a video moment: `videoId`, `playlistId`,
`t` (seconds), `text` (≤ 5000 chars, rules-enforced), `createdAt`, `updatedAt`.
Queried by `videoId` ordered by `t` asc (player/transcript margins) and by
`playlistId` ordered by `createdAt` desc (playlist Notes tab).

### `users/{uid}/highlights/{autoId}`

Saved transcript highlight: `videoId`, `segmentStart` (seconds — the
transcript segment's start time), `text` (denormalized excerpt), `createdAt`.
Queried by `videoId` ordered by `segmentStart` asc so the transcript view
merges highlights into its paragraph stream in one pass.

## Security rules posture

`backend/firestore.rules` grants `read, write` on all three collections only
when **both** hold: the caller is the allowlisted operator (single-tenant
gate, same as every other collection) **and** the path's `{userId}` equals
the caller's uid (owner gate). Writes additionally validate: required fields
present and correctly typed, numeric fields ≥ 0, note text a string of at
most 5000 chars, and server-timestamp fields present on create. Deletes are
allowed (notes/highlights removal; progress account-reset). Every other
collection keeps its existing read-only-for-clients posture.

## Documented query set and indexes

The queries the app runs, and the composite index that serves each
(`backend/firestore.indexes.json`). The static coverage test
(`backend/functions/tests/query-index-coverage.test.ts`) asserts this table
against the index file — the Firestore **emulator does not enforce composite
indexes**, so emulator-green is not index-proof; the test is.

| # | Consumer | Query | Composite index |
|---|----------|-------|-----------------|
| Q1 | Home shelf | `progress` where `kind == "playlist"` orderBy `lastOpenedAt` desc | progress(kind ASC, lastOpenedAt DESC) |
| Q2 | Continue headliner | `progress` where `kind == "video"` orderBy `updatedAt` desc limit 1 | progress(kind ASC, updatedAt DESC) |
| Q3 | Player/Transcript notes | `notes` where `videoId == X` orderBy `t` asc | notes(videoId ASC, t ASC) |
| Q4 | Playlist Notes tab | `notes` where `playlistId == X` orderBy `createdAt` desc | notes(playlistId ASC, createdAt DESC) |
| Q5 | Transcript highlights | `highlights` where `videoId == X` orderBy `segmentStart` asc | highlights(videoId ASC, segmentStart ASC) |
| Q6 | Settings stats | `progress` where `updatedAt >= t0`; `highlights` where `createdAt >= t0` | single-field (automatic) |

All five composite indexes are COLLECTION scope (subcollection layout — no
collection-group queries). Production index build is asserted post-deploy by
the release workflow's composite-index check.

## `searchTranscripts` callable

Full-text search over transcript content. Callable function, operator
allowlist enforced (same gate as every other callable).

**Request** — `{ query: string, limit?: number }`. `query` is trimmed and
must be 2–200 chars, else `invalid-argument`. `limit` caps total results
(default and max 20).

**Response:**

```jsonc
{
  "results": [
    // ranked, best first
    { "videoId": "…", "start": 123.4, "snippet": "…", "score": 7.5 }
  ],
  "scannedVideos": 42,   // transcripts scanned (status == "available" with segments)
  "truncated": false     // true when caps cut the result list
}
```

- `start` is the matching transcript segment's start time in seconds — the
  jump-to-timestamp anchor.
- `snippet` is a ~200-char window centered on the best-matching segment,
  ellipsized at the edges.
- Ranking: all-query-terms-present boost, term frequency, exact-phrase boost.
  Deterministic tiebreak: score desc, then videoId asc, then start asc.
- Caps: at most 3 hits per video, at most 20 (or `limit`) total.
- Errors: `unauthenticated` (no auth), `permission-denied` (not the
  operator), `invalid-argument` (query too short/long/not a string).
  These codes are the client's error contract — the Android search
  repository logs failures by `code`.

**Mechanism and cost model.** Firestore has no native full-text search; at
this app's personal-corpus scale the callable scans the transcript segments
stored inline on `transcripts/{videoId}` pointer docs (`status ==
"available"`). A cold search reads every available transcript doc — N reads,
seconds of latency at low-hundreds scale. A per-instance corpus cache
(5-minute TTL) makes warm searches ~0 reads. The 1 MiB Firestore doc limit
bounds the per-doc worst case; the function runs with 512 MiB memory to hold
a cached corpus in the tens of MB. If the corpus outgrows this mechanism,
the upgrade is a real search index (e.g. an external search service) — a
scope change, not a tweak. The callable logs
`searchTranscripts: completed {tokens, scannedVideos, results, ms}` — never
the query text.

## Summary chapters

`summaries/{videoId}` documents gain an optional `chapters` array:

```jsonc
{ "chapters": [ { "t": 134, "label": "Why libraries stop scaling", "dur": 248 } ] }
```

- `t` — chapter start, **seconds** (clients format `mm:ss` display strings).
- `label` — chapter title.
- `dur` — seconds until the next chapter; for the final chapter, bounded by
  the transcript's last segment when available, else `null`.

**Provenance.** The summarizer daemon (with timestamps enabled on dispatch)
guarantees a `### Key moments` section of `- [m:ss] label` lines on its
final output — model-produced, sanitized against the transcript's real time
bounds, or fallback-generated from the timed transcript. The webhook parses
that section into `chapters` and stores `content` with the section stripped
(the structured field carries the same information). A summary without a
parseable section simply has no `chapters` field — every consumer must treat
it as optional. Summaries produced before this feature never gain chapters
retroactively; the existing re-summarize callable is the on-demand backfill
lever.

## Seed fixtures

`android/maestro/helpers/seed-editorial-corpus.sh` seeds the Firestore
emulator with a mock-shaped corpus (playlists, videos, progress spread,
notes, highlights, transcripts including a 2,000-paragraph stressor, a
chapters-bearing summary, a description-chapters video, a no-transcript
video). Fixture ids and kinds are recorded in
`android/maestro/fixtures/editorial-fixtures.json`.
`android/maestro/helpers/create-fixture-user.js` creates the Auth-emulator
fixture user with the uid pinned to the rules allowlist literal (read from
`firestore.rules` at run time), so seeded flows exercise the production
rules byte-for-byte.

Two emulator facts the seed scripts encode (both probed empirically):

1. **Project namespaces differ by SDK path.** The Android Firestore SDK
   addresses documents under the app's own project id
   (`playster-406121`) even against the emulator, while key-based auth
   requests resolve to the emulator suite's *default* project (the
   `--project` it was started with). Seeds therefore write Firestore data
   under the app's project id and the fixture account under the suite
   default.
2. **Port contention needs a config, not an env var.** When the default
   Firestore port 8080 is taken, `FIRESTORE_EMULATOR_HOST` redirects
   *clients* (the test suites and seed scripts honor it), but only an
   alternate firebase config can re-port the emulator itself —
   `backend/firebase-alt-8081.json` exists for exactly that:
   `firebase emulators:exec --config firebase-alt-8081.json …`.

# Backfill Watch Later video metadata

The Watch Later sync pulls its videos from the InnerTube TV `/browse` endpoint,
whose items are `lockupViewModel` renderers. An earlier version read each
video's title/channel/thumbnail from the same node that carried the `videoId`;
on the TV response those fields live in a separate subtree, so every Watch Later
video was stored with a correct `videoId` but an empty `title`, `channelTitle`,
and `thumbnailUrl` — the list rendered as blank cards.

The extraction is now renderer-aware (see
`backend/functions/src/youtube/innertube-sync.ts`). New syncs store full
metadata automatically. The **already-stored** documents still hold the empty
metadata until a fresh walk re-writes them — that one-time backfill is the
procedure below.

All commands run from `backend/functions/` unless noted. Replace
`<FIREBASE_PROJECT>` with the project id.

---

## 1. (Recommended) Refresh the regression fixture

The renderer extraction is pinned by a redacted real `/browse` Watch Later page
at `tests/fixtures/innertube-browse-wl-lockup.json`. If that file is absent the
production-shape test is skipped; capturing it activates the guard so a future
YouTube renderer change is caught by CI instead of in the app.

```bash
# Needs the cached TV-OAuth session; if it has expired, re-run:
#   node scripts/setup-tv-oauth.mjs
DUMP_PATH=/tmp/wl-raw.json node scripts/test-tv-oauth-raw.mjs
```

Then **redact and trim** `/tmp/wl-raw.json` by hand before committing:

- Keep ~3–5 `lockupViewModel` items plus the continuation node.
- Replace real video titles, channel names, and thumbnail URLs with neutral
  placeholders. **Keep every structural key name verbatim** — the test pins the
  field paths, not the values.
- Save as `backend/functions/tests/fixtures/innertube-browse-wl-lockup.json`.

```bash
pnpm test          # the previously-skipped lockup guard now runs and must pass
```

Never commit the raw dump — it contains real Watch Later titles.

## 2. Deploy the fix

```bash
pnpm run build
firebase deploy --only functions --project <FIREBASE_PROJECT>
```

## 3. Trigger a full re-walk (backfill)

A reset walk re-reads Watch Later from the first page and re-writes every video
document (`merge: true`), repopulating the previously-blank metadata.

The reset is exposed as the operator-only `syncWatchLater` callable, invoked
with `{ "reset": true }`. Because the callable authorizes against the operator
allowlist, invoke it **as the signed-in operator** — the simplest path is to
clear the saved cursor and let the next sync start fresh:

1. In the Firestore console, **delete** the document
   `sync_state/innertube-watch-later` (or set `next_continuation_token` to
   `null` and `complete` to `false`). With no saved cursor, the next run starts
   a fresh walk from the first page.
2. Trigger a sync — pull-to-refresh in the app, or run the scheduled
   `scheduledSync` function once from the Cloud console. The 6-hour schedule
   will otherwise pick it up on its own.

If you have an authenticated operator context available, calling
`syncWatchLater({ reset: true })` directly is equivalent and skips the manual
cursor edit.

## 4. Watch progress

The walk is rate-limited by Cloud Run egress and is **resumable** — a single run
may checkpoint partway through ~4,700 videos and defer the rest; the next
scheduled run continues from the cursor.

```bash
firebase functions:log --project <FIREBASE_PROJECT> | grep innertube-sync
```

- `[innertube-sync] this run: +N videos … complete=true` — the walk finished.
  `complete=false` means it checkpointed; let the schedule continue or trigger
  again.
- `sync_state/innertube-watch-later.complete == true` in Firestore — done.
- **`[innertube-sync] … videos captured with EMPTY title …`** — a drift
  tripwire. A non-trivial count means a renderer variant slipped past the
  extractor (a `lockupViewModel` change or a `youtubei.js` bump). Re-capture a
  page (step 1) and extend the field paths; do **not** bump `youtubei.js` as a
  quick fix.

## 5. Verify

- **Firestore:** documents under `playlists/WL/videos/*` now carry non-empty
  `title` and `thumbnailUrl`.
- **App:** open Watch Later — rows show real titles and thumbnails (no blank
  cards, no stuck "Loading…"); opening a video shows its title and channel on
  the detail screen. Capture before/after screenshots for the QA record.

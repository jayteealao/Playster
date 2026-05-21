# Changelog

All notable breaking changes to externally-callable interfaces are recorded here.

---

## [Unreleased]

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

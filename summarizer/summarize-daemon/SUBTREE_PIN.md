# Subtree pin

Upstream: https://github.com/steipete/summarize
Branch: main
Commit (upstream): 0ec12acc15c480fd4fc91f9d1ee4538c3adeb1de
Squash (local): 8fcef862
Pulled: 2026-05-18
Last verified: 2026-05-18 (slice: summarizer-container)

## Refresh procedure

From repo root, on a clean working tree:

```
git subtree pull --prefix=summarizer/summarize-daemon \
  https://github.com/steipete/summarize <SHA-or-main> --squash \
  -m "chore(summarizer): pull summarize-daemon subtree to <SHA>"
```

After the pull, run the docker-compose harness at `summarizer/deploy/` to
re-verify the daemon contract against both fixtures before merging.

## Refresh cadence

Pin-on-incident. Pull from upstream only when one of:

- A CVE lands in `@steipete/summarize` or its tree (whisper, ytdl, etc.).
- A feature we want lands upstream (e.g. a new daemon route or option).
- Six months have elapsed since the last pull, as hygiene.

## Verification checklist before merging a pull

- [ ] `src/daemon/server.ts` Bearer auth path unchanged
      (`Authorization: Bearer <token>` against `daemonConfigTokens(config)`).
- [ ] `src/daemon/cli.ts` `daemon run --token <T>` foreground form unchanged
      (line ~610 `tokenOverride`).
- [ ] `src/daemon/constants.ts` keeps `DAEMON_HOST = "127.0.0.1"` and
      `DAEMON_PORT_DEFAULT = 8787`.
- [ ] `src/daemon/server-summarize-request.ts` accepts the gateway-forwarded
      keys: `youtube`, `videoMode`, `timestamps`, `forceSummary`, `noCache`,
      `extractOnly`, `prompt`, `maxCharacters`.
- [ ] Docker-compose harness at `summarizer/deploy/` passes both fixture
      flows (captioned + no-caption) and the replay-rejection check.

## Notes

The `0ec12ac` line includes upstream PRs #226 and #227 which add timing-safe
bearer comparison and failed-auth rate-limiting on `/v1/*` routes. Confirmed
present in `summarizer/summarize-daemon/src/daemon/server.ts` after pull.

Do NOT pull from a tag or release branch — upstream development happens on
`main` and the squashed subtree assumes that history.

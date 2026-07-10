<!-- Added by wf-meta build-pipeline — plan v1, 2026-06-20 -->
# Contributing to Playster

Playster is a monorepo with three shippable surfaces — the Android app (`android/`),
the Firebase Functions backend (`backend/`), and the Cloud Run summarizer
(`summarizer/`). All three release together under one version.

## Get set up

```sh
pnpm install && pnpm exec lefthook install
```

This installs JS/TS dependencies for the workspace packages and wires the local git
hooks. Toolchain versions are pinned in `.nvmrc` and `.tool-versions` (Node 22,
pnpm 10.33.2, Java/Temurin 21). For Android work you also need the Android SDK
(platform 36, build-tools 36.0.0).

## Run the gates locally

| Gate | Command |
|------|---------|
| Format | `pnpm -r exec prettier --check .` and `cd android && ./gradlew ktlintCheck` |
| Lint | `pnpm -r run lint` and `cd android && ./gradlew detekt` |
| Type-check | `pnpm -r exec tsc --noEmit` |
| Test | `pnpm -r run test` |

The git hooks run a fast subset of these on `pre-commit` / `commit-msg` / `pre-push`.

## Commits and PRs

- Commit messages **and** PR titles follow [Conventional Commits](https://www.conventionalcommits.org/)
  (`feat:`, `fix:`, `chore:`, `docs:`, …). The changelog is generated from them.
- Open PRs against `main`. CI runs the full quality + security suite; all required
  checks must pass. History on `main` is linear — use **Rebase and merge**.

## Releasing

A release is a `v*` tag on `main`. Pushing the tag builds and publishes the Android
APK/AAB to a GitHub Release, deploys the backend, and rolls out the summarizer.
See `docs/operations/deploy-and-bootstrap.md` for first-time setup and
`docs/runbooks/` for failure recovery.

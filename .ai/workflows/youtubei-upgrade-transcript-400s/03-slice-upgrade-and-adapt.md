---
schema: sdlc/v1
type: slice
slug: youtubei-upgrade-transcript-400s
slice-slug: upgrade-and-adapt
status: complete
stage-number: 3
created-at: "2026-07-11T08:11:59Z"
updated-at: "2026-07-11T08:39:44Z"
complexity: m
depends-on: []
tags: [backend, dependencies, youtubei.js]
refs:
  index: 00-index.md
  slice-index: 03-slice.md
  siblings: [03-slice-fallback-and-error-taxonomy.md, 03-slice-production-watch.md]
  plan: 04-plan-upgrade-and-adapt.md
  implement: 05-implement-upgrade-and-adapt.md
---

# Slice: Upgrade & Adapt

## The Slice

This slice moves youtubei.js from 13.4.0 to an exact 17.2.0 pin and adapts everything the four-major jump breaks — nothing more. That restraint is deliberate: shape research showed the upgrade alone will *not* cure the production 400s (they're PoToken enforcement, not client staleness), so this slice's job is to establish a current, compiling, fully-tested baseline that the real cure — the fallback strategy in the next slice — builds on. The v15+ `{client}` options signature the fallback needs literally doesn't exist until this lands.

The adaptation surface is known from research rather than guessed: `getInfo()`'s client argument moved into an options object at v15, the player evaluator went async at v16, and v17 removed `getTrending` and reshuffled search filters — none of which name the transcript API, so `fetch.ts`'s primary path should adapt mechanically. The one piece with real behavioral risk is `tv-oauth.ts`, which rides along because the upgrade forces it open: its session-context patch and `as any` cast are workarounds for old-version internals, and modernizing them touches the Watch Later sync path. The existing unit tests are the guard here; the production half of that proof (AC8's sync observation) belongs to the production-watch slice.

The feared ESM wall turned out to be a non-issue — `backend/functions` already compiles `module: nodenext` against an ESM-only 13.4.0 — so the residual risk is ordinary API drift in corners the release notes didn't advertise, plus the ops scripts under `scripts/*.mjs` that import UniversalCache and client types outside the test suite's sight.

## Goal

`backend/functions` depends on youtubei.js pinned exactly at `17.2.0`; build, lint, and the full emulator test suite are green; every call site the upgrade breaks is adapted; `tv-oauth.ts` is modernized to v17 idioms; the existing transcript blob/pointer contract is preserved bit-for-bit.

## Why This Slice Exists

The fetch-strategy change (the actual cure for the 400s) requires v15+ API surfaces (`getBasicInfo(videoId, {client})`) and current client strings. Landing the upgrade separately keeps a noisy, mechanical diff (version bump + call-site adaptation) reviewable on its own, so the next slice's *logic* diff is clean. It also gets the repo off an unsupported major with three years of upstream fixes.

## Scope

- **In:**
  - `backend/functions/package.json` — `"youtubei.js": "17.2.0"` (exact, no caret); workspace `pnpm-lock.yaml` regenerated to match.
  - `src/transcript/fetch.ts` — adapt the existing `getTranscript()` primary path to v17 API (signatures only; no strategy change).
  - `src/auth/innertube.ts` — verify/adapt the `Innertube.create` surface.
  - `src/auth/tv-oauth.ts` — modernize to v17 idioms: drop the session-context patch and `as any` cast, align the local ICache implementation.
  - `src/youtube/innertube-sync.ts` — compile/behavior check (`actions.execute("/browse")` is its only youtubei.js surface).
  - `scripts/*.mjs` — compile-check and adapt ops scripts referencing UniversalCache / client types.
  - Existing tests (`tests/transcript-fetch.test.ts`, `tests/transcript-automation.test.ts`, tv-oauth units) adapted to still pass — adapted, not extended.
- **Out (handled by other slices):**
  - ANDROID/timedtext fallback path, error→classification mapping table, N=3 guard, errorClass log detail → `fallback-and-error-taxonomy`.
  - Deploy, forced scheduler run, 24h watch, ops runbook, CHANGELOG → `production-watch`.
  - Any dependency updates beyond what the youtubei.js bump forces.

## Acceptance Criteria

- **AC1** — Given the upgraded dependency tree, when `pnpm --prefix backend/functions run build` and the full `run test` execute with the Firestore emulator, then both are green and `package.json` shows `"youtubei.js": "17.2.0"` (exact) with a matching lockfile.
  <!-- observable: true — the operator experiences this as command output on the dev host; the observing tool is the shell running the build/test commands. -->
  verify: { method: shell — pnpm build + full vitest run with Firestore emulator, env: Windows dev host with pnpm/Java/firebase CLI (all present; nothing to install), fixture: repo tree + emulator (fileParallelism false respected), rung: backend-1 (local emulator suite) }
- **AC2** — Given a mocked primary path returning a transcript, when `fetchTranscript` runs, then blob content, pointer-doc fields, and signed URL match the pre-upgrade contract (existing tests adapted, still passing).
  <!-- observable: false — fully provable by the existing adapted vitest assertions on blob text, pointer fields, and signed URL; no live runtime adds information here. The live-integration proof of the same contract is deliberately owned by production-watch AC6. -->
- **AC8a (unit portion)** — Given the tv-oauth modernization, when the existing tv-oauth unit tests run, then they pass with no behavioral change to the paged Watch Later walk.
  <!-- observable: false — the walk's only external surface (`actions.execute("/browse")`) is covered by existing unit assertions; the production-side observation (AC8b) is pre-registered in the production-watch slice. -->

## Dependencies on Other Slices

- None. This is the foundation slice.

## Risks

- **Unadvertised API drift** — release notes cover the headline breaks (v15 `{client}` options, v16 async evaluator, v17 removals), but four majors of unlisted type/shape changes may surface at compile time in fetch.ts or tv-oauth.ts. Mitigation: tsc + full suite is the gate; appetite stays small because the transcript API itself is unchanged across 13→17.
- **tv-oauth session internals** — v17's `client_type` typing and session internals differ; the modernization must not regress the paged Watch Later walk. Unit tests guard it here; production observation lands in the watch slice.
- **Ops scripts are outside the test suite's sight** — `scripts/*.mjs` breakage wouldn't fail vitest; they need an explicit compile/smoke check in the plan.
- **Expected non-fix** — this slice will NOT stop the production 400s (PoToken enforcement is server-side). That is by design, not a failure of the slice; declaring victory here would be the mistake.

---
schema: sdlc/v1
type: verify
slug: wire-android-backend-summarizer
slice-slug: auth-and-android-firebase
status: complete
stage-number: 6
created-at: "2026-05-18T16:43:28Z"
updated-at: "2026-05-18T16:43:28Z"
result: partial
metric-checks-run: 7
metric-checks-passed: 7
metric-acceptance-met: 4
metric-acceptance-total: 6
metric-acceptance-user-observable: 2
metric-acceptance-code-only: 4
metric-interactive-checks-run: 2
metric-interactive-checks-passed: 1
metric-issues-found: 1
metric-issues-found-initial: 3
metric-issues-found-final: 1
fix-rounds-run: 1
convergence: converged
verify-owned-fix-commit: "f4cf94224efef84efbc6b73502cb27a2a5d49a37"
interactive-verification: deferred
interactive-verification-defer-reason: "Bootstrap state — operator has not run the two-pass deploy yet. firestore.rules + ALLOWED_UID still carry the __BOOTSTRAP_UID__ sentinel, and no scheduledSync invocation has populated Firestore. AC-3's negative sub-claim (zero youtube.googleapis.com calls) IS verified live (APK dex inspection + runtime logcat capture); AC-3's positive sub-claim (PlaylistScreen renders Firestore data) and all of AC-4 (pull-to-refresh advances lastSyncedAt) require post-deploy data. Clear via /wf-quick probe after operator completes the runbook at docs/operations/bootstrap-allowlisted-uid.md."
adapters-used: [service, android]
bootstrap-failures: []
evidence-dir: ".ai/workflows/wire-android-backend-summarizer/verify-evidence/auth-and-android-firebase/"
tags: [android, firebase-auth, firestore, backend, onCall]
refs:
  index: 00-index.md
  verify-index: 06-verify.md
  slice-def: 03-slice-auth-and-android-firebase.md
  plan: 04-plan-auth-and-android-firebase.md
  implement: 05-implement-auth-and-android-firebase.md
  review: 07-review-auth-and-android-firebase.md
  adapters: ${CLAUDE_PLUGIN_ROOT}/skills/wf/reference/runtime-adapters.md
next-command: wf-review
next-invocation: "/wf review wire-android-backend-summarizer auth-and-android-firebase"
---

# Verify: Auth + Android Firebase view (atomic flip)

## Verification Summary

Backend code-only AC (AC-1, AC-2, AC-15, slice-local-build) verified via
`firebase emulators:exec` running vitest — 10/10 specs pass (6 rules + 4
callable). Backend `pnpm run lint` + `pnpm run build` + `tsc -p tsconfig.test.json`
all green; the one lint warning (`innertube-sync.ts:394` line length 178)
is pre-existing in code not touched by this slice.

Android user-observable AC partitioned into two sub-claims:

- **AC-3 negative sub-claim** ("zero youtube.googleapis.com network calls"):
  fully verified — APK dex inspection shows **zero** references to
  `com/google/api/services/youtube/*` or the string `youtube.googleapis.com`
  across all 14 dex files; runtime logcat capture (12 s, 1,927 lines)
  during app launch shows the same zero match count. The device-side
  YouTube Data API code path is structurally gone, confirmed at compile
  and at runtime.

- **AC-3 positive sub-claim** ("PlaylistScreen renders Firestore data"):
  structurally verified (APK contains 16,062 `com/google/firebase/firestore`
  references and 109 `FirestoreRepository` references — the listener wiring
  is present) but visually deferred. Pre-sign-in, the user lands on the
  AuthScreen (`Sign in with Google` button visible — confirmed via
  uiautomator dump). Post-sign-in the screen subscribes to Firestore, but
  rules currently deny all reads because `firestore.rules` still carries
  the `__BOOTSTRAP_UID__` sentinel; no data renders until the operator
  completes the two-pass deploy.

- **AC-4** ("pull-to-refresh triggers syncAllPlaylists, lastSyncedAt
  advances"): fully deferred. The callable is wired correctly (string
  constant `syncAllPlaylists` present in `classes9.dex`, `getHttpsCallable`
  call in `PlaylistViewModel.kt:43`) but a real invocation against the live
  backend requires the second-pass deploy (`ALLOWED_UID` set to the real
  uid, real uid in `firestore.rules`). Cannot be observed in bootstrap state.

One fix-loop round ran during verify: BUILD-1 (initial Gradle
`kspDebugKotlin` failure on `firebase-auth` Kotlin metadata mismatch) was
patched by pinning `firebase-bom` from `34.0.0` → `33.4.0` (the last BOM
whose firebase-auth/firestore/functions all carry Kotlin metadata
version ≤ 1.9.0, matching the project's Kotlin 1.9.22 toolchain). The
first attempt at the fix (`33.16.0`) re-failed with the same metadata
error; a research sub-agent then probed published `.aar` metadata on
`dl.google.com` to find the exact 33.5.0 → 33.4.0 boundary. Re-built APK
succeeds; the verify-time commit is `f4cf9422`.

## Automated Checks Run

| check | result | summary |
|---|---|---|
| `pnpm run lint` (backend) | pass | 0 errors, 1 pre-existing warning in `innertube-sync.ts:394` (line 178 chars, unrelated to slice) |
| `pnpm run build` (tsc on src) | pass | clean emit to `lib/`, no errors |
| `tsc -p tsconfig.test.json` | pass | tests type-check |
| `firebase emulators:exec vitest run` | pass | 10/10 (6 `rules.test.ts` + 4 `callable.test.ts`); duration 2.75 s; evidence: `verify-evidence/.../vitest-full.log` |
| `gradle :app:assembleDebug` (initial) | **fail** | kspDebugKotlin / firebase-auth-24.0.0 metadata 2.1.0 vs expected 1.9.0; evidence: `verify-evidence/.../gradle-build.log` (overwritten by re-run after fix) |
| `gradle :app:assembleDebug` (post-fix, firebase-bom=33.4.0) | pass | BUILD SUCCESSFUL in 29 s; APK at `app/build/outputs/apk/debug/app-debug.apk` (~73 MB) |
| `adb install` on `emulator-5554` + launch | pass | "Performing Streamed Install / Success"; foreground activity = `com.github.jayteealao.playster/.MainActivity`; no fatals in logcat |

## Interactive Verification Results

| criterion | adapter | evidence | observation | result |
|---|---|---|---|---|
| AC-3 (negative — no youtube.googleapis.com) | android (apk-dex + logcat) | `apk-dex-grep.txt`, `logcat.log` | dex grep: 0 hits on `com/google/api/services/youtube` and `youtube.googleapis.com` across 14 dex files; logcat (12 s capture during launch + AuthScreen idle, 1,927 lines): 0 hits on either pattern | **pass** |
| AC-3 (positive — renders Firestore data) | android (uiautomator + screencap) | `auth-screen.png`, `ui-text.txt`, `window_dump.xml` (partial — see Gaps) | foreground = MainActivity → AuthScreen; visible text: "Playster / Your YouTube, organized / Sign in with Google / Privacy / Terms"; PlaylistScreen not reachable without sign-in completion, which requires Firebase Auth user creation; even after sign-in, rules deny reads in bootstrap state | **partial** (structurally wired; visual proof deferred) |
| AC-4 (pull-to-refresh + lastSyncedAt) | android (Maestro) | none — flow not executed | bootstrap state cannot satisfy callable's allowlist check (`ALLOWED_UID == __BOOTSTRAP_UID__`) nor produce a populated `playlists/` collection; running Maestro would observe `permission-denied` rather than the success path | **deferred** |

## Acceptance Criteria Status

| criterion | kind | status | verification method | evidence |
|---|---|---|---|---|
| **AC-1** Unauthenticated callable returns `unauthenticated` | code-only | met | automated (vitest + firebase-functions-test) | `callable.test.ts > rejects unauthenticated calls` in `vitest-full.log` |
| **AC-2** Non-allowlisted uid returns `permission-denied` | code-only | met | automated (vitest + firebase-functions-test) | `callable.test.ts > rejects non-allowlisted uid` in `vitest-full.log` |
| **AC-3** Playlist screen renders from Firestore with zero `youtube.googleapis.com` calls | user-observable | **partially met** | runtime adapter (negative sub-claim) + structural (positive sub-claim) | dex grep + logcat for negative; APK class introspection + UI dump for structural; positive visual proof deferred |
| **AC-4** Pull-to-refresh triggers `syncAllPlaylists` and `lastSyncedAt` advances | user-observable | **deferred** (runtime-evidence-missing) | none — `interactive-verification: deferred` | callable is wired (`PlaylistViewModel.kt:43`, string constant present in dex) but cannot be exercised against bootstrap-state backend |
| **AC-15** (partial — covers `playlists/`, `videos/`, `tokens/`, `sync_state/`) Firestore rules deny stranger uids and allow allowlisted | code-only | met | automated (`@firebase/rules-unit-testing` under `firebase emulators:exec`) | 6 specs in `rules.test.ts`; all pass; `vitest-full.log` |
| **Slice-local AC** `pnpm --filter functions run build` succeeds; existing `scheduledSync` cron still triggers | code-only | met | automated (build) + code review | `pnpm run build` clean; `scheduledSync` left on `onSchedule` (no source change in `src/index.ts`) — manual emulator cron-trigger deferred since the trigger surface is unchanged |

## Issues Found

- **medium** AC-4 lacks runtime evidence — environment-blocked, not code-blocked. Cleared by post-deploy probe (see Recommended Next Stage Option F / G).
- **low** AC-3 positive sub-claim ("renders Firestore data") lacks visual runtime proof — same root cause as AC-4. Negative sub-claim ("no YouTube API") is fully verified.

(BUILD-1 was the only substantive issue surfaced; it was patched in the verify-owned fix loop. See `## Verify-Owned Fixes` below.)

## Verify-Owned Fixes

| ID | Type | Triage | Sub-agent outcome | Re-check result |
|----|------|--------|-------------------|-----------------|
| BUILD-1 | check-failure (kspDebugKotlin metadata mismatch) | Fix | Patched — `firebase-bom` 34.0.0 → 33.16.0 (first attempt, still failed) → 33.4.0 (second attempt, success). The retry inside the single round was driven by a research sub-agent that probed published .aar metadata on dl.google.com to pinpoint the 33.5.0 → 33.4.0 boundary. | Pass — `:app:assembleDebug` BUILD SUCCESSFUL |

Commit: `f4cf94224efef84efbc6b73502cb27a2a5d49a37` — `build(android): pin firebase-bom to 33.4.0 for Kotlin 1.9 metadata compat`.

## Gaps / Unverified Areas

- **Two-pass deploy not executed**, so:
  - PlaylistScreen rendering with real Firestore data (AC-3 positive sub-claim)
  - syncAllPlaylists callable success path + `lastSyncedAt` advancement (AC-4)
  - End-to-end Google Sign-In → Firebase Auth → playlist read flow
- **Maestro flows not run**: the slice's plan named Maestro as the harness for AC-3/AC-4; bootstrap state makes them ineffective. Maestro is set up locally (`maestro` on `PATH`) but no flow files exist yet under `android/maestro/`.
- **`uiautomator dump --remote`** required by Git Bash hit a path-expansion quirk (`/sdcard/...` interpreted as Windows path); resolved via `exec-out uiautomator dump /dev/tty`; raw XML not preserved (only the extracted `text="…"` fragments saved). Sufficient for the AuthScreen identification we needed.
- **Pre-existing lint warning** (`innertube-sync.ts:394` 178 chars) was not addressed — it lives in code introduced by the prior `7dad00cd` commit on `main`, not by this slice.

## Augmentation Verification

Not applicable — `00-index.md` `augmentations:` list is empty and `02c-craft.md` does not exist.

## Freshness Research

Performed during the fix loop, not during initial check phase:

- **Firebase Android BOM Kotlin metadata boundary**: a research sub-agent
  pulled `firebase-bom-*.pom` files from `dl.google.com/dl/android/maven2`
  and inspected `Lkotlin/Metadata;` `mv` fields on each `firebase-auth`
  artifact's `AuthKt.class`. Result: BOM **33.4.0** is the last release
  whose `firebase-auth` (23.0.0), `firebase-firestore` (25.1.0), and
  `firebase-functions` (21.0.0) all carry Kotlin metadata version ≤ 1.9.0.
  BOM 33.5.0 first ships `firebase-auth 23.1.0` with metadata 2.0.0,
  which fails KSP on Kotlin 1.9.22. BOM 34.x ships `firebase-auth 24.x`
  with metadata 2.1.0. Sources: Firebase Android SDK release notes;
  BOM POMs at `dl.google.com/dl/android/maven2/com/google/firebase/firebase-bom/<v>/`;
  `firebase-auth` AAR contents inspected via `javap -v` on `AuthKt.class`.
- This research was the load-bearing input for converting BUILD-1 from
  "Fix sub-agent could not fix (33.16.0 still fails)" → "Fix sub-agent
  patched successfully (33.4.0)" within a single verify round.

## Recommendation

- **Slice 1 verification result: `partial`** — every code-only AC met;
  the negative sub-claim of AC-3 fully verified; the positive sub-claim
  of AC-3 and all of AC-4 deferred via `interactive-verification: deferred`
  with reason captured in frontmatter.
- **Convergence: `converged`** — the one fix-loop round addressed the
  only substantive failure (BUILD-1). The remaining gaps are
  environment-blocked (operator hasn't run the two-pass deploy yet),
  not code-blocked.
- **Deferral is the correct posture, not a fail.** The slice itself is
  code-correct; what's missing is a runtime probe against a deployed
  backend with a real allowlisted uid. The bootstrap runbook covers
  exactly that workflow.
- **`/wf ship` will refuse to start** while this deferral is open. The
  natural clear path is `/wf-quick probe` after the operator finishes
  the two-pass deploy and `scheduledSync` has run at least once.

## Recommended Next Stage

- **Option A (default):** `/wf review wire-android-backend-summarizer auth-and-android-firebase` — verify converged with `result: partial`; the slice is ready for code review. The deferral is acknowledged in frontmatter and propagated to `00-index.md` `runtime-evidence-deferrals` so review/handoff have visibility into the unproven sub-claims.
- **Option G:** `/wf-quick probe wire-android-backend-summarizer` — clear the deferral by executing the slug-wide runtime probe AFTER the operator runs the two-pass deploy (`docs/operations/bootstrap-allowlisted-uid.md`). Probe should observe (a) Firestore listener attaches and receives playlist documents post-sign-in, (b) pull-to-refresh causes `lastSyncedAt` to advance on at least one playlist doc.
- **Option D:** `/wf handoff wire-android-backend-summarizer` (when `review-scope: slug-wide` allows skipping per-slice review) — only after slice 2/3/4 are also `partial`/`pass`. Handoff will warn-not-block on the deferral; ship will hard-block until probe clears it.
- *(Options B/C/E not applicable — fix loop converged; no escalation needed.)*

---
schema: sdlc/v1
type: index
slug: wire-android-backend-summarizer
title: "Wire Android ↔ Backend ↔ Summarizer (single-tenant, OpenRouter free)"
status: active
current-stage: review
stage-number: 7
created-at: "2026-05-17T15:00:08Z"
updated-at: "2026-05-22T21:24:23Z"
selected-slice: "failure-recovery-cron"
branch-strategy: dedicated
branch: "feat/wire-android-backend-summarizer"
base-branch: "main"
review-scope: slug-wide
pr-url: ""
pr-number: 0
open-questions: []
runtime-evidence-deferrals:
  - slice: auth-and-android-firebase
    reason: "Bootstrap state — operator has not run the two-pass deploy yet (ALLOWED_UID + firestore.rules still carry __BOOTSTRAP_UID__ sentinel; no scheduledSync data in Firestore). AC-3 positive sub-claim (PlaylistScreen renders Firestore data) + AC-4 (pull-to-refresh advances lastSyncedAt) cannot be exercised. AC-3 negative sub-claim (zero youtube.googleapis.com calls) IS verified via APK dex + runtime logcat."
    deferred-at: "2026-05-18T16:43:28Z"
    cleared-by: null
  - slice: summary-ui
    reason: "Two compounding blockers prevent end-to-end Maestro runs of AC-5 (in-progress within 500ms), AC-10 (quota banner + disabled CTA), the cached-summary no-redispatch AC, and the Retry 500ms transition timing: (1) the app has no connectFirestoreEmulator/useEmulator wiring in android/app/src/main, so the installed APK targets the production Firebase project; (2) production ALLOWED_UID is still __BOOTSTRAP_UID__ per slice 1's deferral. Live evidence captured for the other AC: 4-state Compose UI tests 4/0/0 on Medium_Phone_API_36.0, APK launch clean (2054ms cold, no fatals), AuthScreen renders, app-global QuotaBanner correctly hides in Healthy state."
    deferred-at: "2026-05-20T15:14:19Z"
    cleared-by: null
tags: [android, firebase, cloud-run, summarizer, openrouter, single-tenant, multi-component]
stack:
  detected-at: "2026-05-17T15:00:08Z"
  platforms: [android, service]
  languages: [kotlin, typescript]
  ui: [compose]
  build: [gradle, tsc, docker]
  package-managers: [gradle, pnpm]
  testing: [junit, espresso, vitest, maestro]
  observability: [lazylogcat]
  integrations:
    - hilt
    - firebase-auth
    - firestore
    - firebase-functions-v2
    - firebase-admin
    - youtubei.js
    - youtube-data-api
    - openrouter-planned
    - cloud-run-planned
    - steipete-summarize-subtree
  available-skills:
    - {name: Plan, hint: "Software architect agent for designing implementation plans"}
    - {name: Explore, hint: "Fast read-only search agent for locating code"}
    - {name: general-purpose, hint: "General-purpose research/multi-step agent"}
    - {name: "codex:codex-rescue", hint: "Hand a substantial coding task to Codex for second opinion or stuck states"}
    - {name: code-simplifier, hint: "Refine recent code for clarity/maintainability"}
  available-clis:
    - {name: android, hint: "Android SDK / project orchestration CLI"}
    - {name: gcloud, hint: "Google Cloud CLI — Cloud Run deploy, Secret Manager, IAM"}
    - {name: firebase, hint: "Firebase CLI — Functions deploy, Firestore rules, emulators"}
    - {name: lazylogcat, hint: "Non-interactive logcat capture/filter for Android debugging"}
    - {name: maestro, hint: "Mobile end-to-end UI test runner — eligible for Android acceptance flows"}
  available-mcp: []
  user-confirmed: true
next-command: wf-verify
next-invocation: "/wf verify wire-android-backend-summarizer failure-recovery-cron"
parallel-branches:
  - slice: failure-recovery-cron
    branch: "feat/failure-recovery-cron"
    base: "feat/wire-android-backend-summarizer"
    implement-commit-sha: "3c9a464a"
    rebase-target-on-v1-ship: "main"
    extension-round: 1
    source: from-review
workflow-files:
  - 00-index.md
  - 01-intake.md
  - 02-shape.md
  - 03-slice.md
  - 03-slice-auth-and-android-firebase.md
  - 03-slice-summarizer-container.md
  - 03-slice-summary-orchestration.md
  - 03-slice-summary-ui.md
  - 03-slice-failure-recovery-cron.md
  - 04-plan.md
  - 04-plan-auth-and-android-firebase.md
  - 04-plan-summarizer-container.md
  - 04-plan-summary-orchestration.md
  - 04-plan-summary-ui.md
  - 04-plan-failure-recovery-cron.md
  - 05-implement.md
  - 05-implement-auth-and-android-firebase.md
  - 05-implement-summarizer-container.md
  - 05-implement-summary-orchestration.md
  - 05-implement-summary-ui.md
  - 05-implement-failure-recovery-cron.md
  - 06-verify.md
  - 06-verify-auth-and-android-firebase.md
  - 06-verify-summarizer-container.md
  - 06-verify-summary-orchestration.md
  - 06-verify-summary-ui.md
  - 07-review.md
  - 07-review-correctness.md
  - 07-review-security.md
  - 07-review-code-simplification.md
  - 07-review-testing.md
  - 07-review-maintainability.md
  - 07-review-reliability.md
  - 07-review-backend-concurrency.md
  - 07-review-architecture.md
  - 07-review-performance.md
  - 07-review-data-integrity.md
  - 07-review-migrations.md
  - 07-review-privacy.md
  - 07-review-api-contracts.md
  - 07-review-supply-chain.md
  - 07-review-infra-security.md
  - po-answers.md
progress:
  intake: complete
  shape: complete
  slice: complete
  plan: complete
  implement: complete
  verify: complete
  review: complete
  handoff: not-started
  ship: not-started
  retro: not-started
extension-round-1:
  slice: failure-recovery-cron
  branch: "feat/failure-recovery-cron"
  intake: n/a
  shape: complete
  slice: complete
  plan: complete
  implement: complete
  verify: not-started
  review: not-started
  handoff: not-started
  ship: not-started
---

---
schema: sdlc/v1
type: index
slug: wire-android-backend-summarizer
title: "Wire Android ↔ Backend ↔ Summarizer (single-tenant, OpenRouter free)"
status: active
current-stage: implement
stage-number: 5
created-at: "2026-05-17T15:00:08Z"
updated-at: "2026-05-19T22:55:42Z"
selected-slice: "summary-ui"
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
next-invocation: "/wf verify wire-android-backend-summarizer summary-ui"
workflow-files:
  - 00-index.md
  - 01-intake.md
  - 02-shape.md
  - 03-slice.md
  - 03-slice-auth-and-android-firebase.md
  - 03-slice-summarizer-container.md
  - 03-slice-summary-orchestration.md
  - 03-slice-summary-ui.md
  - 04-plan.md
  - 04-plan-auth-and-android-firebase.md
  - 04-plan-summarizer-container.md
  - 04-plan-summary-orchestration.md
  - 04-plan-summary-ui.md
  - 05-implement.md
  - 05-implement-auth-and-android-firebase.md
  - 05-implement-summarizer-container.md
  - 05-implement-summary-orchestration.md
  - 05-implement-summary-ui.md
  - 06-verify.md
  - 06-verify-auth-and-android-firebase.md
  - 06-verify-summarizer-container.md
  - 06-verify-summary-orchestration.md
  - po-answers.md
progress:
  intake: complete
  shape: complete
  slice: complete
  plan: complete
  implement: complete
  verify: in-progress
  review: not-started
  handoff: not-started
  ship: not-started
  retro: not-started
---

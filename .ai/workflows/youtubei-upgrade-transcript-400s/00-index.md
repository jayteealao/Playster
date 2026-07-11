---
schema: sdlc/v1
type: index
slug: youtubei-upgrade-transcript-400s
title: "Upgrade youtubei.js to fix systematic transcript-fetch 400s"
status: active
current-stage: verify
stage-number: 6
created-at: "2026-07-11T00:58:22Z"
updated-at: "2026-07-11T09:37:52Z"
selected-slice: "production-watch"
branch-strategy: dedicated
branch: "fix/youtubei-upgrade-transcript-400s"
base-branch: "main"
review-scope: slug-wide
pr-url: ""
pr-number: 0
open-questions: []
resolved-questions:
  - "Ops how-to target location resolved at plan (production-watch): docs/operations/transcript-backfill-watch.md — matches existing repo convention (deploy-and-bootstrap.md, backfill-watch-later.md in docs/operations/)."
tags: [backend, firebase-functions, dependencies, transcripts, production-incident]
stack:
  detected-at: "2026-07-11T00:58:22Z"
  platforms: [service, android]
  languages: [typescript, kotlin]
  ui: []
  build: [tsc, gradle]
  package-managers: [pnpm, gradle]
  testing: [vitest]
  observability: [cloud-logging]
  integrations: [firebase-functions, firebase-admin, google-cloud-storage, youtubei.js]
  available-skills:
    - {name: tech-research-enforcer, hint: "Forces doc/version research before dependency answers"}
    - {name: error-analysis, hint: "Root-cause analysis of errors and logs"}
    - {name: consult, hint: "External-model second opinions on plans/diagnoses"}
    - {name: verify, hint: "End-to-end verification of changes"}
  available-mcp:
    - {name: web-search/web-reader, hint: "Freshness research on youtubei.js releases"}
  user-confirmed: true
next-command: wf-verify
next-invocation: "/wf verify youtubei-upgrade-transcript-400s production-watch"
workflow-files:
  - 00-index.md
  - 01-intake.md
  - 02-shape.md
  - 03-slice.md
  - 03-slice-upgrade-and-adapt.md
  - 03-slice-fallback-and-error-taxonomy.md
  - 03-slice-production-watch.md
  - po-answers.md
  - 04-plan.md
  - 04-plan-upgrade-and-adapt.md
  - 04-plan-upgrade-and-adapt.yaml
  - 04-plan-upgrade-and-adapt.html.fragment
  - 05-implement.md
  - 05-implement-upgrade-and-adapt.md
  - 06-verify.md
  - 06-verify-upgrade-and-adapt.md
  - 04-plan-fallback-and-error-taxonomy.md
  - 04-plan-fallback-and-error-taxonomy.yaml
  - 04-plan-fallback-and-error-taxonomy.html.fragment
  - 04b-instrument.md
  - 04b-instrument.yaml
  - 04b-instrument.html.fragment
  - 05-implement-fallback-and-error-taxonomy.md
  - 06-verify-fallback-and-error-taxonomy.md
  - 04-plan-production-watch.md
  - 04-plan-production-watch.yaml
  - 04-plan-production-watch.html.fragment
  - 05-implement-production-watch.md
augmentations:
  - type: instrument
    artifact: 04b-instrument.md
    status: complete
    created-at: "2026-07-11T08:53:21Z"
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
verify-slice-results:
  upgrade-and-adapt: {result: pass, convergence: not-needed}
  fallback-and-error-taxonomy: {result: pass, convergence: not-needed}
runtime-evidence-deferrals: []
---

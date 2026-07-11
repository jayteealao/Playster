---
schema: sdlc/v1
type: index
slug: youtubei-upgrade-transcript-400s
title: "Upgrade youtubei.js to fix systematic transcript-fetch 400s"
status: active
current-stage: implement
stage-number: 5
created-at: "2026-07-11T00:58:22Z"
updated-at: "2026-07-11T08:39:44Z"
selected-slice: "upgrade-and-adapt"
branch-strategy: dedicated
branch: "fix/youtubei-upgrade-transcript-400s"
base-branch: "main"
review-scope: slug-wide
pr-url: ""
pr-number: 0
open-questions:
  - "Ops how-to target location: backend/README.md section vs docs/ — decide at plan (production-watch slice)."
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
next-invocation: "/wf verify youtubei-upgrade-transcript-400s upgrade-and-adapt"
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
progress:
  intake: complete
  shape: complete
  slice: complete
  plan: complete
  implement: complete
  verify: not-started
  review: not-started
  handoff: not-started
  ship: not-started
  retro: not-started
---

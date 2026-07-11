---
schema: sdlc/v1
type: index
slug: youtubei-upgrade-transcript-400s
title: "Upgrade youtubei.js to fix systematic transcript-fetch 400s"
status: active
current-stage: handoff
stage-number: 8
created-at: "2026-07-11T00:58:22Z"
updated-at: "2026-07-11T10:38:00Z"
selected-slice: "production-watch"
branch-strategy: dedicated
branch: "fix/youtubei-upgrade-transcript-400s"
base-branch: "main"
review-scope: slug-wide
pr-url: "https://github.com/jayteealao/Playster/pull/26"
pr-number: 26
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
next-command: wf-ship
next-invocation: "/wf ship youtubei-upgrade-transcript-400s"
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
  - 06-verify-production-watch.md
  - 07-review.md
  - 07-review.yaml
  - 07-review.html.fragment
  - 07-review-correctness.md
  - 07-review-correctness.yaml
  - 07-review-security.md
  - 07-review-security.yaml
  - 07-review-supply-chain.md
  - 07-review-supply-chain.yaml
  - 07-review-logging.md
  - 07-review-logging.yaml
  - 07-review-observability.md
  - 07-review-observability.yaml
  - 07-review-refactor-safety.md
  - 07-review-refactor-safety.yaml
  - 07-review-reliability.md
  - 07-review-reliability.yaml
  - 07-review-reliability.html.fragment
  - 07-review-testing.md
  - 07-review-testing.yaml
  - 07-review-testing.html.fragment
  - 07-review-backend-concurrency.md
  - 07-review-backend-concurrency.yaml
  - 07-review-backend-concurrency.html.fragment
  - 07-review-data-integrity.md
  - 07-review-data-integrity.yaml
  - 07-review-data-integrity.html.fragment
  - 07-review-docs.md
  - 07-review-docs.yaml
  - 07-review-docs.html.fragment
  - 07-review-code-simplification.md
  - 07-review-code-simplification.yaml
  - 07-review-code-simplification.html.fragment
  - 07-review-maintainability.md
  - 07-review-maintainability.yaml
  - 07-review-maintainability.html.fragment
  - 08-handoff.md
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
  verify: complete
  review: complete
  handoff: complete
  ship: not-started
  retro: not-started
verify-slice-results:
  upgrade-and-adapt: {result: pass, convergence: not-needed}
  fallback-and-error-taxonomy: {result: pass, convergence: not-needed}
  production-watch: {result: partial, convergence: not-needed}
runtime-evidence-deferrals:
  - slice: production-watch
    reason: >
      AC6 (forced scheduler run): gcloud CLI authenticated and Cloud Logging query
      confirmed working (probe exit 0); constraint is pre-deploy ordering — merge +
      `npx firebase deploy --only functions --project playster-406121` must run first.
      Rung 1+2 covered by code-slice test suite (115/115). Rung 3 not applicable (no
      emulator scheduler chain). Rung 4 blocked by pre-deploy ordering. Clears at
      ship-stage forced scheduler run with ≥1 fetchTranscript: complete and zero
      unexplained INNERTUBE_4XX.
    deferred-at: "2026-07-11T09:45:42Z"
    cleared-by: null
    ship-override-authorization:
      by: jayteealao
      at: "2026-07-11T11:30:19Z"
      reason: >
        Deploy-time-circular post-deploy check — the clearing event requires merge +
        firebase deploy to have run first (forced scheduler run / 24h watch window).
        Pre-deploy tooling confirmed via probe (exit 0). PO (jayteealao) accepts the
        risk and ships now; post-deploy verification tracked as a blocking follow-up
        in the ship run artifact and its Recommended Next Stage.
  - slice: production-watch
    reason: >
      AC7 (24h watch): shares tooling confirmation with AC6 deferral. Clearing event:
      24h watch window closes with INNERTUBE_4XX count = 0 (pass) or explicit
      fallback-also-enforced verdict (triggers PoToken escalation decision). Sample
      count must be recorded.
    deferred-at: "2026-07-11T09:45:42Z"
    cleared-by: null
    ship-override-authorization:
      by: jayteealao
      at: "2026-07-11T11:30:19Z"
      reason: >
        Deploy-time-circular post-deploy check — the clearing event requires merge +
        firebase deploy to have run first (forced scheduler run / 24h watch window).
        Pre-deploy tooling confirmed via probe (exit 0). PO (jayteealao) accepts the
        risk and ships now; post-deploy verification tracked as a blocking follow-up
        in the ship run artifact and its Recommended Next Stage.
  - slice: production-watch
    reason: >
      AC8b (syncWatchLater regression): shares tooling and window with AC7 deferral.
      Clearing event: same 24h window confirms no scheduledSync/syncWatchLater error
      entries.
    deferred-at: "2026-07-11T09:45:42Z"
    cleared-by: null
    ship-override-authorization:
      by: jayteealao
      at: "2026-07-11T11:30:19Z"
      reason: >
        Deploy-time-circular post-deploy check — the clearing event requires merge +
        firebase deploy to have run first (forced scheduler run / 24h watch window).
        Pre-deploy tooling confirmed via probe (exit 0). PO (jayteealao) accepts the
        risk and ships now; post-deploy verification tracked as a blocking follow-up
        in the ship run artifact and its Recommended Next Stage.
---

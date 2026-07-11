---
schema: sdlc/v1
type: implement-index
slug: youtubei-upgrade-transcript-400s
status: in-progress
stage-number: 5
created-at: "2026-07-11T08:39:44Z"
updated-at: "2026-07-11T09:15:26Z"
slices-implemented: 2
slices-total: 3
metric-total-files-changed: 8
metric-total-lines-added: 610
metric-total-lines-removed: 97
tags: [backend, dependencies, youtubei.js, typescript, error-handling, instrument]
refs:
  index: 00-index.md
  plan-index: 04-plan.md
next-command: wf-verify
next-invocation: "/wf verify youtubei-upgrade-transcript-400s fallback-and-error-taxonomy"
---

# Implement Index

## Cross-Slice Integration Notes

- `upgrade-and-adapt`: establishes youtubei.js 17.2.0 as the baseline. Its `getBasicInfo(videoId, {client:'ANDROID'})` signature (v15+) is the prerequisite for the fallback path in `fallback-and-error-taxonomy`. No shared files between the two slices.
- `fallback-and-error-taxonomy`: builds on the v17 baseline to add the dual-path fetch flow, error classification, counter guard, and 11 new tests (115 total). Modifies `src/transcript/fetch.ts`, `src/models/index.ts`, `src/transcript/constants.ts`, `tests/transcript-fetch.test.ts`, and adds `tests/fixtures/timedtext-json3-sample.json`. None of these files were touched by `upgrade-and-adapt`.
- `production-watch`: the remaining slice — deploys the combined artifact, runs the 24h live proof, authors the runbook and CHANGELOG entry. Depends on both implemented slices.

## Recommended Next Stage

- **Option A (default):** Verify `fallback-and-error-taxonomy` — test suite already green (115/115), verify is a gate confirmation pass.
- **Option B:** Implement `production-watch` — the third and final slice; requires both implemented slices to be verified first.

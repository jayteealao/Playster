---
schema: sdlc/v1
type: implement-index
slug: youtubei-upgrade-transcript-400s
status: complete
stage-number: 5
created-at: "2026-07-11T08:39:44Z"
updated-at: "2026-07-11T09:37:52Z"
slices-implemented: 3
slices-total: 3
metric-total-files-changed: 10
metric-total-lines-added: 885
metric-total-lines-removed: 97
tags: [backend, dependencies, youtubei.js, typescript, error-handling, instrument, docs, ops]
refs:
  index: 00-index.md
  plan-index: 04-plan.md
next-command: wf-verify
next-invocation: "/wf verify youtubei-upgrade-transcript-400s production-watch"
---

# Implement Index

## Cross-Slice Integration Notes

- `upgrade-and-adapt`: establishes youtubei.js 17.2.0 as the baseline. Its `getBasicInfo(videoId, {client:'ANDROID'})` signature (v15+) is the prerequisite for the fallback path in `fallback-and-error-taxonomy`. No shared files between the two slices.
- `fallback-and-error-taxonomy`: builds on the v17 baseline to add the dual-path fetch flow, error classification, counter guard, and 11 new tests (115 total). Modifies `src/transcript/fetch.ts`, `src/models/index.ts`, `src/transcript/constants.ts`, `tests/transcript-fetch.test.ts`, and adds `tests/fixtures/timedtext-json3-sample.json`. None of these files were touched by `upgrade-and-adapt`.
- `production-watch`: documentation-only slice. Adds `docs/operations/transcript-backfill-watch.md` (ops runbook) and updates `CHANGELOG.md` with the non-breaking additive entry. Pre-registered AC6/AC7/AC8b deferrals are documented in the runbook; they clear at ship.

## Recommended Next Stage

- **Option A (default):** Verify `production-watch` — AC-docs is a static content review; AC6/AC7/AC8b are pre-registered deferrals that close at ship.
- **Option B:** Proceed to review all slices — all three slices are now implemented and verified (code slices) or ready for static review (production-watch).

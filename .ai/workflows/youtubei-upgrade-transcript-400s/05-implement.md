---
schema: sdlc/v1
type: implement-index
slug: youtubei-upgrade-transcript-400s
status: in-progress
stage-number: 5
created-at: "2026-07-11T08:39:44Z"
updated-at: "2026-07-11T08:39:44Z"
slices-implemented: 1
slices-total: 3
metric-total-files-changed: 3
metric-total-lines-added: 28
metric-total-lines-removed: 48
tags: [backend, dependencies, youtubei.js, typescript]
refs:
  index: 00-index.md
  plan-index: 04-plan.md
next-command: wf-verify
next-invocation: "/wf verify youtubei-upgrade-transcript-400s upgrade-and-adapt"
---

# Implement Index

## Cross-Slice Integration Notes

- The `upgrade-and-adapt` slice establishes youtubei.js 17.2.0 as the baseline. The `fallback-and-error-taxonomy` slice depends on the v15+ `getBasicInfo(videoId, { client: 'ANDROID' })` signature that is now available. That slice may now proceed.
- No shared files were modified between slices in this implementation pass.

## Recommended Next Stage

- **Option A (default):** Verify `upgrade-and-adapt` — `/wf verify youtubei-upgrade-transcript-400s upgrade-and-adapt`
- **Option B:** Begin `fallback-and-error-taxonomy` slice (it now has the v17 baseline it depends on) — `/wf plan youtubei-upgrade-transcript-400s fallback-and-error-taxonomy`

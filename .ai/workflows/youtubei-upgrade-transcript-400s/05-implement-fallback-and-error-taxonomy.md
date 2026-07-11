---
schema: sdlc/v1
type: implement
slug: youtubei-upgrade-transcript-400s
slice-slug: fallback-and-error-taxonomy
status: complete
stage-number: 5
created-at: "2026-07-11T09:15:26Z"
updated-at: "2026-07-11T09:15:26Z"
metric-files-changed: 5
metric-lines-added: 582
metric-lines-removed: 49
metric-deviations-from-plan: 1
metric-review-fixes-applied: 0
commit-sha: "ff9d7bb2"
tags: [backend, transcripts, error-handling, instrument]
refs:
  index: 00-index.md
  implement-index: 05-implement.md
  slice-def: 03-slice-fallback-and-error-taxonomy.md
  plan: 04-plan-fallback-and-error-taxonomy.md
  siblings: [05-implement-upgrade-and-adapt.md]
  verify: 06-verify-fallback-and-error-taxonomy.md
next-command: wf-verify
next-invocation: "/wf verify youtubei-upgrade-transcript-400s fallback-and-error-taxonomy"
---

# Implement: Fallback & Error Taxonomy

## The Implementation

`fetchTranscript` now has two paths to a working transcript. The primary path is unchanged: `getInfo()` + `getTranscript()`. When that path throws and the error classifies as `INNERTUBE_4XX` (the PoToken-blocked 400s), a second call to `getInnertubeClient()` produces an ANDROID-client instance, `getBasicInfo(videoId, {client:'ANDROID'})` fetches the caption track list, and a direct timedtext GET with `&fmt=json3` returns the segments. The GCS blob is byte-identical either way — `segmentsToText` runs on the parsed segments regardless of which path produced them; the only new observable difference is `source: "android-timedtext"` on the pointer doc.

The classification table is a 6-entry `const` array scanned with `.find()`. Each entry carries a `test` predicate, `status`, `errorClass`, `fallbackEligible`, and an optional `extractHttpStatus` for the INNERTUBE_4XX case. The table is the complete authoritative list — no implicit fallthrough, no string-prefix accumulation. The last entry always matches (UNKNOWN), so `find()` is never undefined. `classifyError` is exported from `fetch.ts` and the AC4 tests call it directly, one test per table entry.

The panel-not-found counter rides the existing `pointerRef.get()` / `pointerRef.set()` cycle. `existing.data()?.panelNotFoundCount` is read at the top of the error path (treating absent as 0), incremented, and written back. At N=3 the status flips to `unavailable`. Any non-panel-not-found error resets the counter to 0 explicitly. The AC5 Firestore-emulator tests pre-seed the pointer doc and assert the exact counter value after each call.

All three signal additions from the instrument augmentation landed on existing log call sites: `errorClass` + `httpStatus` + `fallbackEngaged` on the transient/unavailable warn, `panelNotFoundCount` + `terminal` on the PANEL_NOT_FOUND branch of the same warn, and `source` on the complete info. No new `logger.*()` calls were introduced. The 115-test suite (up from 104) ran green against the Firestore emulator in 51 seconds.

## Summary of Changes

- `backend/functions/src/models/index.ts`: added `TranscriptErrorClass` type; extended `source` union to include `"android-timedtext"`; added `errorClass?` and `panelNotFoundCount?` fields to `TranscriptDocument`.
- `backend/functions/src/transcript/constants.ts`: added `PANEL_NOT_FOUND_TERMINAL_COUNT = 3`.
- `backend/functions/src/transcript/fetch.ts`: major rewrite — sentinel classes (`PanelNotFoundError`, `EmptyTimedtextError`); `CLASSIFICATION_TABLE` + exported `classifyError`; `parseJson3Segments`; `selectCaptionTrackUrl`; `fetchViaAndroidTimedtext`; restructured `fetchTranscript` with dual-path flow, counter guard, and updated log fields.
- `backend/functions/tests/transcript-fetch.test.ts`: 11 new tests — 5 AC3/AC5 in the emulator-backed describe, 6 AC4 classification-table unit tests in a new top-level describe.
- `backend/functions/tests/fixtures/timedtext-json3-sample.json`: new json3 fixture with 2 caption events byte-equivalent to the primary-path segment fixture.

## Files Changed

- `backend/functions/src/models/index.ts` — additive: new type + extended union + 2 optional fields
- `backend/functions/src/transcript/constants.ts` — additive: 1 new constant
- `backend/functions/src/transcript/fetch.ts` — major: ~300 lines added, ~50 lines removed (old single-catch block replaced by dual-path structure)
- `backend/functions/tests/transcript-fetch.test.ts` — extended: 11 new tests, 2 new helpers, 1 fixture body constant
- `backend/functions/tests/fixtures/timedtext-json3-sample.json` — new file: minimal json3 fixture

## Shared Files (also touched by sibling slices)

None — no file in this change set overlaps with the `upgrade-and-adapt` sibling's changed files (`package.json`, `pnpm-lock.yaml`, `tv-oauth.ts`).

## Notes on Design Choices

- **`EmptyTimedtextError` exported (deviation from plan Assumption 4).** The plan's Assumption 4 said both sentinel classes are unexported. However, the AC4 test for `EMPTY_TIMEDTEXT` requires `new EmptyTimedtextError()` to be constructable in the test file. Exporting `EmptyTimedtextError` is the minimum seam; it does not change runtime behavior. `PanelNotFoundError` remains unexported because the AC4 test uses `new Error("Transcript panel not found")` (a plain Error with the matching message) — the sentinel only exists internally to guarantee the message format.

- **Single logger.warn call site for all error paths.** The instrument augmentation required one `logger.warn("fetchTranscript: fetch failed", {...})` reachable from both primary-only failures and fallback failures. Achieved by funneling both paths through the `if (classification !== null)` block. The `fallbackEngaged` boolean distinguishes the contexts without splitting the call site.

- **`panelNotFoundCount: 0` on non-panel-not-found errors.** Written explicitly rather than omitted to signal "counter was reset" vs. "field never existed." Reading code treats absent as 0, so the runtime behavior is identical, but the explicit 0 makes the reset visible in Firestore Console and the production-watch watch query.

- **ANDROID-client language selection with 4-level priority chain.** Exact-match → prefix-match → first auto-generated → first track. When the primary path threw before setting `language`, `preferredLang` is `"unknown"` — no track will exact- or prefix-match, so the fallback selects the first auto-generated or the first available track. This is the correct behavior for a video where the desired language is unknown.

## Verification Seams Built

- **AC4** → `classifyError` exported from `backend/functions/src/transcript/fetch.ts` at the module level. The 6 AC4 tests call `classifyError(err)` directly, one per table entry. `EmptyTimedtextError` is also exported so the test can construct an instance.
- **AC3** → `fetchViaAndroidTimedtext` is not exported (it is an internal implementation detail), but the test reaches it via `fetchTranscript` with a two-call `getInnertubeClient` mock pattern. `vi.stubGlobal("fetch", ...)` intercepts the timedtext GET inside `fetchViaAndroidTimedtext`. `timedtext-json3-sample.json` is the recorded fixture used for byte-equivalence assertions.
- **AC5** → Counter reads via `existing.data()?.panelNotFoundCount` and writes via `pointerRef.set(errorDoc)` use the real Firestore emulator path (no mocking). `clearFirestore()` in `beforeEach` ensures clean state; `admin.firestore().doc(...).set(...)` in each AC5 test pre-seeds the counter value.

## Deviations from Plan

1. **`EmptyTimedtextError` exported.** Plan Assumption 4 said both sentinel classes are unexported. `EmptyTimedtextError` is exported to satisfy the AC4 EMPTY_TIMEDTEXT test seam. This is the minimum scope expansion; it does not affect runtime behavior or the classification logic. Recorded as the single deviation.

## Anything Deferred

- **Retry/backoff mechanics.** No retry logic added. The `transient` status is the signal for the backfill cron to retry; how and when it retries is outside this slice's scope. Pre-existing from shape.
- **PoToken sidecar (BgUtils on Cloud Run).** Recorded escalation: if the ANDROID fallback also degrades under datacenter-IP enforcement, the next remediation is a PoToken sidecar. Out of scope for this workflow. Pre-existing from slice definition.
- **Language-selection fidelity on multi-language videos.** The AC3 test fixture uses a single "en" track. A bilingual video where the primary path would select "fr" will get the fallback's first track (not necessarily "fr"). The 4-level priority chain mitigates this but cannot guarantee equivalence without a real ANDROID response. This is the known ceiling; the production-watch slice is the live proof.

## Known Risks / Caveats

- **Datacenter-IP enforcement on the fallback.** YouTube may enforce the same PoToken policy against ANDROID-client requests from GCP IPs. This is unprovable pre-deploy. `source: "android-timedtext"` on the pointer doc and `errorClass: "INNERTUBE_4XX"` on continued transient failures are the signals. Production-watch owns the live proof (AC6/AC7).
- **Empty-200 PoToken marker.** `EmptyTimedtextError` sentinel + `EMPTY_TIMEDTEXT` class cover the case where YouTube returns HTTP 200 with an empty body. The AC3 test case (empty body) confirms this path.
- **`panelNotFoundCount: 0` written on GCS/sign-URL errors.** The error doc is written only in the `if (classification !== null)` block. GCS write failures and signed-URL failures write their own `transientDoc` without `panelNotFoundCount`. This means GCS errors do not reset the panel-not-found counter. Low risk: GCS errors are infrastructure failures, not YouTube-side caption behavior.

## Freshness Research

- Source: LuanRT/YouTube.js CHANGELOG (carried from plan-stage research, 2026-07-11; confirmed at v17.2.0 still current).
  Takeaway: `getBasicInfo(videoId, {client:'ANDROID'})` confirmed available; `captions.caption_tracks` shape confirmed. No breaking changes to this surface in v15–v17.

- Source: Node.js global `fetch` + vitest `vi.stubGlobal` (carried from plan-stage research).
  Takeaway: global `fetch` available unflagged in Node 18 (Firebase Gen 2 runtime). `vi.stubGlobal('fetch', ...)` is the standard vitest mock approach; `vi.unstubAllGlobals()` in `afterEach` restores the original.

## Assumptions

The following decisions were made autonomously per the implement-stage autonomous-override policy:

1. **`EmptyTimedtextError` exported** (deviation from plan Assumption 4) — see Deviations from Plan.
2. **`PanelNotFoundError` message set to "Transcript panel not found" in its constructor.** This ensures `classifyError` sees PANEL_NOT_FOUND via the string-match branch, not UNKNOWN. The test only constructs `new Error("Transcript panel not found")` for AC4 (not `new PanelNotFoundError()`), so the class's message must match the classification table's pattern.
3. **`vi.unstubAllGlobals()` added to `afterEach`.** The test file's existing `vi.restoreAllMocks()` does not unstub globals set via `vi.stubGlobal`. Added to prevent the mocked `fetch` from leaking across test cases. The vitest config does not set `unstubGlobals: true`, so explicit cleanup is required.
4. **EXPECTED_BLOB_TEXT constant inlined in tests** rather than reading the fixture file. Avoids filesystem path resolution issues in the test environment and makes the byte-equivalence assertion self-documenting.

## Recommended Next Stage

- **Option A (default):** `/wf verify youtubei-upgrade-transcript-400s fallback-and-error-taxonomy` — all three ACs are covered by the automated test suite (emulator + unit tests); verify is a gate confirmation pass. The build and test run already executed as part of this slice: zero compiler errors, 115 tests green.
- **Option B:** `/wf review youtubei-upgrade-transcript-400s fallback-and-error-taxonomy` — skip verify if AC coverage from the build run is considered sufficient.

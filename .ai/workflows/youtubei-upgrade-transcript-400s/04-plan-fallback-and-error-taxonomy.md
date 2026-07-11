---
schema: sdlc/v1
type: plan
slug: youtubei-upgrade-transcript-400s
slice-slug: fallback-and-error-taxonomy
status: complete
stage-number: 4
created-at: "2026-07-11T08:53:21Z"
updated-at: "2026-07-11T08:53:21Z"
metric-files-to-touch: 5
metric-step-count: 10
has-blockers: false
revision-count: 0
revisions: []
tags: [backend, transcripts, error-handling, instrument]
stack-source: confirmed
refs:
  index: 00-index.md
  plan-index: 04-plan.md
  slice-def: 03-slice-fallback-and-error-taxonomy.md
  siblings: [04-plan-upgrade-and-adapt.md, 04-plan-production-watch.md]
  implement: 05-implement-fallback-and-error-taxonomy.md
next-command: wf-verify
next-invocation: "/wf verify youtubei-upgrade-transcript-400s fallback-and-error-taxonomy"
---

# Plan: Fallback & Error Taxonomy

## The Plan

Five files move, but the logic they carry is dense. `fetch.ts` grows three new pure functions — the error classifier, the json3 segment parser, and the timedtext track selector — and its main body gets a dual-path structure: primary `getTranscript()` attempt first, then ANDROID-client fallback when the classifier says the error is fallback-eligible (a 400 from InnerTube), then counter guard logic for the panel-not-found case that the fallback doesn't cover. The GCS blob format and pointer-doc contract are byte-identical regardless of which path ran; the only new observable difference is the `source` field on the pointer doc (`"android-timedtext"` vs `"youtubei"`) and an `errorClass` field on all error outcomes.

The two mechanisms — fallback and counter guard — are kept independent by design. Panel-not-found is handled exclusively by the N=3 counter (YouTube flakiness on a video that may genuinely have captions; the ANDROID basic-info path would return the same absent panel). INNERTUBE_4XX is handled exclusively by the fallback (the PoToken-blocked 400; the ANDROID path bypasses that enforcement). This independence means each mechanism can be tested in isolation, which the per-entry AC4 table tests and the counter guard AC5 tests do exactly.

The instrument augmentation (`04b-instrument.md`, authored alongside this plan) designs the signal additions: `errorClass` on the existing warn log line, `source` on the complete log line, and the Cloud Logging query the 24h watch will run. Every new field is additive to existing log lines — no new `logger.*()` call sites are introduced.

The highest risk is the one that cannot be tested here: YouTube enforcing the same policy against ANDROID-client requests from GCP datacenter IPs. That is a live proof, pre-registered as the production-watch slice's job with a named clearing event. Mocked tests prove the dual-path logic is correct; they make no claim about what YouTube does with the requests in production.

## Current State

- `src/transcript/fetch.ts` (203 LOC): one `try/catch` block covering all errors from `getInfo()`+`getTranscript()`. Every thrown error produces `status: "transient"` with a raw `.message` slice (≤200 chars) as `errorCode`. No classification, no fallback. The `source` field is hardcoded to `"youtubei"` on the available doc.
- `src/transcript/constants.ts` (35 LOC): batch-size and lock-path constants; nothing about error classification or counter limits.
- `src/models/index.ts` (114 LOC): `TranscriptDocument.source` typed as `"youtubei" | "shortDescription"` — does not include the fallback path's `"android-timedtext"`. No `errorClass` or `panelNotFoundCount` fields. No `TranscriptErrorClass` type.
- `tests/transcript-fetch.test.ts` (255 LOC): covers the available/unavailable/transient/idempotency cases against a mocked InnerTube client. No mock for `getBasicInfo`, no json3 fixture, no counter guard tests.
- `tests/fixtures/`: only InnerTube browse fixtures for Watch Later sync tests. No timedtext json3 fixture.
- `.ai/solutions/INDEX.md`: does not exist.

## Simplicity Ladder

- **Error classification logic** → rung 3 (reuse: declarative table in-file) — no external library needed; a 6-entry `const` array with a `.find()` scan is the correct implementation for a mapping table this small. Rung 4 would apply only if the classification logic were complex enough to warrant a dedicated utility module; it isn't.
- **json3 response parsing** → rung 2 (native platform: `JSON.parse` + `Array.prototype.filter/map`) — no parsing library needed; the json3 schema is a flat event array with a fixed shape.
- **Timedtext HTTP GET** → rung 2 (native platform: Node 18+ global `fetch`) — available unflagged in Firebase Gen 2 runtime (Node 18). No new dependency.
- **ANDROID-client caption track lookup** → rung 3 (reuse: `innertube.getBasicInfo(videoId, {client:'ANDROID'})` added at v15) — the v15 API surface installed by the prerequisite slice provides this call. No wrapper needed beyond language-selection logic.
- **Counter persistence** → rung 3 (reuse: existing Firestore pointer-doc read-modify-write pattern already in `fetchTranscript`) — the counter rides the same `pointerRef.get()` / `pointerRef.set()` cycle already present; no new Firestore pattern.
- **Language track selection** → rung 4 (new code) — json3 caption track metadata does not map 1:1 to the primary path's `selectedLanguage` semantics; a small helper is needed. Reason: the captionTracks array exposes `language_code` strings, but the primary path's `selectedLanguage` field is the InnerTube response's language label; an exact-match → prefix-match → first-auto-generated → first-available priority chain covers the edge cases.

## Applied Learnings

No applicable learnings found — `.ai/solutions/INDEX.md` does not exist in this repository.

No prior `runtime-evidence-deferrals` in `00-index.md` at plan time for this slice. The existing `upgrade-and-adapt` slice's verify result (`result: pass, convergence: not-needed`) carries no deferral that fuzzy-matches this slice's environment dependencies.

## Likely Files / Areas to Touch

- `backend/functions/src/models/index.ts`: add `TranscriptErrorClass` type, extend `source` union, add `errorClass` and `panelNotFoundCount` fields to `TranscriptDocument`
- `backend/functions/src/transcript/constants.ts`: add `PANEL_NOT_FOUND_TERMINAL_COUNT`
- `backend/functions/src/transcript/fetch.ts`: major — classifier, json3 parser, track selector, ANDROID fallback, counter guard, log-line field additions
- `backend/functions/tests/transcript-fetch.test.ts`: extend with AC3/AC4/AC5 tests
- `backend/functions/tests/fixtures/timedtext-json3-sample.json`: new — recorded json3 fixture matching the primary-path fixture segments

## Proposed Change Strategy

Add types first, then pure functions in `fetch.ts`, then wire them into the `fetchTranscript` main flow, then extend the test suite. Each step compiles independently: after step 1 the types exist; after steps 3–6 the helper functions exist but are not yet wired into the main flow; step 7 wires them. The test extensions in steps 9–10 target the exported `classifyError` function directly (no mocking needed for unit classification tests) and the full `fetchTranscript` flow for the integration tests.

The prerequisite slice (`upgrade-and-adapt`) must be on the branch before this slice begins, because the `getBasicInfo(videoId, {client:'ANDROID'})` call signature only exists at v15+.

## Step-by-Step Plan

1. **Extend `src/models/index.ts` with new types and fields.**
   a. Add `export type TranscriptErrorClass = "PANEL_NOT_FOUND" | "LOGIN_REQUIRED" | "INNERTUBE_4XX" | "EMPTY_TIMEDTEXT" | "PARSE_FAILURE" | "UNKNOWN"`.
   b. Extend `TranscriptDocument.source` union: `"youtubei" | "shortDescription" | "android-timedtext"`.
   c. Add to `TranscriptDocument`:
      - `errorClass?: TranscriptErrorClass` — the stable classification code written to transient/unavailable docs.
      - `panelNotFoundCount?: number` — incremented on consecutive PANEL_NOT_FOUND outcomes; written to pointer doc to survive across cron ticks.
   
   These are additive-only: no existing field names change; existing consumers (Android app, summarizer) tolerate unknown fields by structural typing.

2. **Add `PANEL_NOT_FOUND_TERMINAL_COUNT` to `src/transcript/constants.ts`.**
   Add: `export const PANEL_NOT_FOUND_TERMINAL_COUNT = 3;`
   This is the N in "flip to terminal unavailable at N consecutive panel-not-found hits."

3. **Implement and export `classifyError(err: unknown): TranscriptClassification` in `src/transcript/fetch.ts`.**
   
   Define a local `interface TranscriptClassification { status: "transient" | "unavailable"; errorClass: TranscriptErrorClass; fallbackEligible: boolean; httpStatus?: number }`.
   
   Implement a declarative 6-entry classification table (a `const` array of `{test: (err) => boolean, status, errorClass, fallbackEligible, httpStatus?}` entries scanned with `.find()`):
   
   | Entry | Match condition | status | errorClass | fallbackEligible |
   |-------|----------------|--------|------------|------------------|
   | panel-not-found | message includes "Transcript panel not found" or "No transcript panel" | transient | PANEL_NOT_FOUND | false |
   | login-required | message includes "sign in" / "login" / "LOGIN_REQUIRED" / "This video is private" | unavailable | LOGIN_REQUIRED | false |
   | innertube-4xx | message includes "status 400" / "status 401" / "status 403" / "4xx" | transient | INNERTUBE_4XX | true |
   | empty-timedtext | instanceof EmptyTimedtextError (custom sentinel) | transient | EMPTY_TIMEDTEXT | false |
   | parse-failure | instanceof SyntaxError | transient | PARSE_FAILURE | false |
   | unknown (default) | always (last entry) | transient | UNKNOWN | false |
   
   Export `classifyError` for direct unit testing of each table entry (the AC4 test seam).
   
   Note: `EmptyTimedtextError` is a local sentinel class defined in `fetch.ts` (one line: `class EmptyTimedtextError extends Error {}`); it is thrown by the timedtext fetch when the response body is empty after trimming.

4. **Implement `parseJson3Segments(body: string): TranscriptSegment[]` in `src/transcript/fetch.ts`.**
   
   Parse `JSON.parse(body)` and extract events with `segs[]`:
   - Filter events: must have `tStartMs: number` and `segs: Array<{utf8: string}>`.
   - Map to `{start: tStartMs / 1000, text: segs.map(s => s.utf8).join('').trim()}`.
   - Filter out empty-text segments (auto-generated noise).
   
   This is a pure function with no side effects; it does not call `logger` or touch Firestore. Keep it unexported — it is an implementation detail of the fallback path.

5. **Implement `selectCaptionTrackUrl(tracks: CaptionTrack[], preferredLang: string): string | null` in `src/transcript/fetch.ts`.**
   
   Where `CaptionTrack` is inferred from the youtubei.js `getBasicInfo` return type's `captions.caption_tracks` array (duck-typed if the type is not directly importable). Selection priority:
   1. Exact match on `track.language_code === preferredLang`.
   2. Prefix match: `track.language_code.startsWith(preferredLang)` (covers "en-US" when preferred is "en").
   3. First auto-generated track (if any track's `is_auto_generated` is true).
   4. First track in the array (any language, as a last resort).
   5. If the array is empty: return `null`.
   
   Returns the track's `base_url` string (or `null` if no tracks). The caller treats `null` as PANEL_NOT_FOUND (the ANDROID path also sees no caption panel).

6. **Implement `fetchViaAndroidTimedtext(innertube, videoId, preferredLang)` in `src/transcript/fetch.ts`.**
   
   ```
   const info = await innertube.getBasicInfo(videoId, { client: 'ANDROID' });
   const tracks = info.captions?.caption_tracks ?? [];
   const trackUrl = selectCaptionTrackUrl(tracks, preferredLang);
   if (!trackUrl) throw new PanelNotFoundError();  // reuse the panel-not-found path
   
   const url = `${trackUrl}&fmt=json3`;
   const resp = await fetch(url);
   if (!resp.ok) throw new Error(`timedtext GET failed with status ${resp.status}`);
   const body = await resp.text();
   if (!body.trim()) throw new EmptyTimedtextError();
   
   return parseJson3Segments(body);
   ```
   
   Returns `TranscriptSegment[]`. On success, the caller writes a GCS blob via the existing `segmentsToText()` + `file.save()` chain — byte-identical to the primary path's output.
   
   Note: `PanelNotFoundError` is another one-line sentinel class; thrown here to ensure `classifyError` sees PANEL_NOT_FOUND (not a raw `new Error("no tracks")` that might fall to UNKNOWN).

7. **Restructure `fetchTranscript()` to use the classifier and dual-path flow.**
   
   Replace the single catch block with:
   
   ```
   // -- Primary path --
   let primarySegments: TranscriptSegment[] | null = null;
   let selectedLanguage = "unknown";
   let classification: TranscriptClassification | null = null;
   
   try {
     const innertube = await getInnertubeClient();
     const videoInfo = await innertube.getInfo(videoId);
     const transcriptInfoRaw = await videoInfo.getTranscript();
     // ... existing segment extraction into primarySegments ...
     selectedLanguage = transcriptInfoRaw.selectedLanguage ?? "unknown";
   } catch (fetchErr) {
     classification = classifyError(fetchErr);
   }
   
   // -- Fallback path (only when primary fails with fallback-eligible error) --
   if (classification?.fallbackEligible) {
     try {
       const innertube = await getInnertubeClient();
       primarySegments = await fetchViaAndroidTimedtext(innertube, videoId, selectedLanguage);
       // fallback succeeded — clear classification, record source
       classification = null;
       source = "android-timedtext";
     } catch (fallbackErr) {
       // fallback also failed — classify the fallback error (overrides primary)
       classification = classifyError(fallbackErr);
     }
   }
   
   // -- Handle failed primary + failed/absent fallback --
   if (classification !== null) {
     // ... counter guard for PANEL_NOT_FOUND ...
     // ... write transient/unavailable pointer doc with errorClass ...
     return;
   }
   
   // -- Happy path (segments are non-null) --
   // ... existing GCS write + signed URL + available pointer doc ...
   ```
   
   Counter guard logic (inside the `classification !== null` branch, before the pointer write):
   - Read current `panelNotFoundCount` from existing pointer doc (treat missing as 0).
   - If `classification.errorClass === "PANEL_NOT_FOUND"`:
     - `newCount = (existingCount ?? 0) + 1`
     - If `newCount >= PANEL_NOT_FOUND_TERMINAL_COUNT`: write `{status: "unavailable", errorClass: "PANEL_NOT_FOUND", panelNotFoundCount: newCount}` (terminal).
     - Else: write `{status: "transient", errorClass: "PANEL_NOT_FOUND", panelNotFoundCount: newCount}`.
   - For any other error: write `{status, errorClass, panelNotFoundCount: 0}` (reset counter).
   
   `panelNotFoundCount: 0` on non-panel-not-found errors is the explicit reset signal; missing counter field on pre-existing docs is treated as 0 at read time.

8. **Add observability fields to existing log call sites in `fetchTranscript()`.**
   
   Per the instrument augmentation (`04b-instrument.md`), extend existing log lines — no new `logger.*()` calls:
   - `fetchTranscript: fetch failed (transient)` warn: add `{ errorClass, httpStatus: classification.httpStatus }`.
   - `fetchTranscript: complete` info: add `{ source }`.
   - The counter flip to `unavailable` uses the existing warn pattern with `{ errorClass: "PANEL_NOT_FOUND", panelNotFoundCount }`.
   
   `httpStatus` is populated only when the classifier identifies it (e.g., INNERTUBE_4XX extracts the status code from the error message via a regex match; undefined otherwise).

9. **Create `tests/fixtures/timedtext-json3-sample.json`.**
   
   A minimal json3 fixture with 3 caption events that produce segments byte-equivalent to the primary-path fixture used in the existing `transcript-fetch.test.ts`:
   ```json
   {
     "wireMagic": "pb3",
     "events": [
       { "tStartMs": 0, "dDurationMs": 2500, "segs": [{ "utf8": "Hello world" }] },
       { "tStartMs": 2500, "dDurationMs": 3000, "segs": [{ "utf8": "Next sentence." }] }
     ]
   }
   ```
   These events produce segments `[{start: 0, text: "Hello world"}, {start: 2.5, text: "Next sentence."}]` — matching the existing `seg("0", "Hello world")` / `seg("2500", "Next sentence.")` fixture values in `transcript-fetch.test.ts`. The `segmentsToText` output is then byte-identical for the primary-path mock vs. the json3-parsed fallback path.

10. **Extend `tests/transcript-fetch.test.ts` with AC3/AC4/AC5 tests, then run full suite.**
    
    **AC4 — classification table tests** (6 cases, each a direct call to `classifyError()`):
    - `new Error("Transcript panel not found")` → `{status:"transient", errorClass:"PANEL_NOT_FOUND", fallbackEligible:false}`
    - `new Error("This video requires sign in")` → `{status:"unavailable", errorClass:"LOGIN_REQUIRED", fallbackEligible:false}`
    - `new Error("Request failed with status 400")` → `{status:"transient", errorClass:"INNERTUBE_4XX", fallbackEligible:true}`
    - `new EmptyTimedtextError()` → `{status:"transient", errorClass:"EMPTY_TIMEDTEXT", fallbackEligible:false}`
    - `new SyntaxError("Unexpected token")` → `{status:"transient", errorClass:"PARSE_FAILURE", fallbackEligible:false}`
    - `new Error("network timeout")` → `{status:"transient", errorClass:"UNKNOWN", fallbackEligible:false}`
    
    **AC3 — fallback engagement and blob byte-equivalence** (2 cases):
    - Primary mock throws `new Error("status 400")` (INNERTUBE_4XX, fallback-eligible); `getBasicInfo` mock returns a captionTracks array with one track whose `base_url` is mocked; global `fetch` mock returns the timedtext-json3-sample.json fixture content. Assert: GCS save called once, `savedText` equals primary-path blob text for same segments, pointer doc at `{status:"available", source:"android-timedtext"}`.
    - Both paths fail (primary 400, fallback returns empty body). Assert: pointer doc at `{status:"transient", errorClass:"EMPTY_TIMEDTEXT"}`.
    
    **AC5 — counter guard** (3 cases, all using Firestore emulator):
    - Counter increment: primary throws PANEL_NOT_FOUND, existing pointer is absent (count treated as 0). After fetch: pointer doc at `{status:"transient", errorClass:"PANEL_NOT_FOUND", panelNotFoundCount:1}`.
    - Counter at 2, third hit: pre-seed pointer with `{panelNotFoundCount: 2}`. After fetch with PANEL_NOT_FOUND: pointer doc at `{status:"unavailable", errorClass:"PANEL_NOT_FOUND", panelNotFoundCount:3}`.
    - Counter reset: pre-seed pointer with `{panelNotFoundCount: 2}`. After fetch that SUCCEEDS (primary path): pointer doc at `{status:"available"}` — counter field absent or irrelevant (available path overwrites the doc).
    
    After all test additions: run `pnpm --prefix backend/functions run test`. Gate: full suite green.

## Verification Strategy

| AC | Tool / method + ladder rung | Environment need — satisfiable in target env? | What must be built | Fallback chain |
|----|------------------------------|-----------------------------------------------|------------------------------------------|----------------|
| AC3: fallback engagement + blob byte-equivalence + source field | vitest + mock `getBasicInfo` + global `fetch` mock + Firestore emulator (backend-1: local emulator suite) | Windows dev host, pnpm, Java, firebase CLI — all present. `getBasicInfo` mock follows same pattern as existing `getInfo` mock | `timedtext-json3-sample.json` fixture; mock for `innertube.getBasicInfo`; mock for global `fetch` returning fixture content (via `vi.stubGlobal('fetch', ...)`) | None — fully automated on dev host |
| AC4: per-entry classification table (6 entries) | vitest unit tests calling exported `classifyError()` directly (backend-1, no emulator needed) | Windows dev host, pnpm only — no Firestore or network | `classifyError` must be exported from `fetch.ts`; `EmptyTimedtextError` must be exported for the test's `instanceof` assertion | None |
| AC5: counter increment / reset / terminal flip | vitest + Firestore emulator (backend-1: real Firestore path, not mocked — counter read/write uses real `pointerRef.get()`) | Windows dev host with Firestore emulator — present | Pre-seeded pointer docs with specific `panelNotFoundCount` values; existing `clearFirestore()` + `seedTranscriptPointer()` helpers reused | None |

**Constraint resolutions:**

- AC3: `constraint-resolution: po-accepted: Fully automated. The mocked tests prove the dual-path logic and blob equivalence. The live proof that YouTube accepts ANDROID-client requests from GCP IPs is deliberately owned by production-watch (AC6/AC7). No runtime dependency blocks this AC in the test environment.`
- AC4: `constraint-resolution: po-accepted: Pure unit test against an exported pure function. No runtime, no emulator, no credentials.`
- AC5: `constraint-resolution: po-accepted: Firestore emulator provides the real Firestore read/write path. The counter logic runs entirely within the emulator-backed test context; no live Firestore access needed.`

## Test / Verification Plan

### Automated checks

- **lint/typecheck:** `pnpm --prefix backend/functions run build` (tsc). Run after every step that adds or modifies TypeScript. Especially after step 1 (new types) and after step 7 (wiring).
- **unit tests:** `pnpm --prefix backend/functions run test` — full vitest suite including the new AC3/AC4/AC5 cases. All tests must pass. The existing AC1/AC2 primary-path tests must remain green (the restructured `fetchTranscript` primary path is functionally unchanged when no error is thrown).
- **coverage note:** classifyError has 6 entries — 6 unit tests, one per entry, is complete proof for a pure mapping function.

### Interactive verification (human-in-the-loop)

Automated only — this is a backend service with no UI surface. The `stack.platforms` is `[service, android]`; no browser or device interaction is needed for AC3/AC4/AC5. Production interaction (AC6/AC7) is owned by the production-watch slice.

## Risks / Watchouts

- **Datacenter-IP enforcement on the fallback** — the top risk; unprovable in tests. See Verification Strategy / constraint-resolution for AC3.
- **Empty-200 PoToken body misclassified** — body check must precede `JSON.parse`. The `EmptyTimedtextError` sentinel ensures `classifyError` sees a distinct type, not a raw SyntaxError. A test case in AC4 covers this.
- **Language-selection fidelity** — if the ANDROID caption track's `language_code` doesn't match the primary path's `selectedLanguage` value format, the fallback may select a different track on multi-language videos. The AC3 test fixture uses a single-language track; the language-mismatch risk remains a production-only observation.
- **Misclassification is terminal** — per-entry tests (AC4) and off-by-one tests (AC5) are the full defense. No production-only coverage needed: a classification table is a pure function.
- **`getBasicInfo` return type for captions** — the duck-typed `captions?.caption_tracks ?? []` guard handles the case where `getBasicInfo` returns an object without a `captions` property (e.g., non-public video). This falls to `null` from `selectCaptionTrackUrl` → treated as PANEL_NOT_FOUND.

## Dependencies on Other Slices

- **Prerequisite:** `upgrade-and-adapt` must be on the branch. The `getBasicInfo(videoId, {client:'ANDROID'})` options signature only exists at v15+; the fallback implementation calls this API.
- **Downstream:** `production-watch` deploys this slice's combined artifact. It reads the `source` and `errorClass` fields designed here in its 24h watch query.

## Assumptions

The following decisions were made autonomously per the plan-stage autonomous-override policy. Each is recorded for auditability.

1. **Only INNERTUBE_4XX triggers the fallback; panel-not-found does not.** Panel-not-found is handled exclusively by the N=3 counter guard. Rationale: the fallback targets PoToken-blocked requests (400s from InnerTube). A panel-not-found error on the primary path would produce the same response on the ANDROID path if the video truly has no caption track; triggering a fallback on panel-not-found adds load without adding coverage.

2. **Mixed dual-path failure: last error's classification wins.** When both the primary path and the fallback fail, the fallback's `errorClass` and `status` are written to the pointer doc. Rationale: the fallback error is the most recent and reflects the final state of both paths; the production-watch watch query reads `errorClass` to identify patterns, and the fallback's class is more specific (e.g., `EMPTY_TIMEDTEXT` from the fallback is more actionable than the primary's `INNERTUBE_4XX`).

3. **`classifyError` exported from `fetch.ts` for test seam.** AC4 requires direct unit testing of the classification table without running `fetchTranscript` end-to-end. Exporting `classifyError` is the minimum seam. Rationale: a test that exercises the table via the full fetch function would require mocking the full fetch flow for each of the 6 entries; direct export tests the table in isolation with zero overhead.

4. **`EmptyTimedtextError` and `PanelNotFoundError` are sentinel classes defined in `fetch.ts`.** These are one-line `extends Error` classes, not exported, used only as `instanceof` checks in `classifyError`. Rationale: a raw string message check for empty-timedtext would collide with other "empty body" patterns; a sentinel class makes the classification deterministic and the test explicit.

5. **`panelNotFoundCount: 0` written on non-panel-not-found errors (explicit reset).** When any error other than PANEL_NOT_FOUND is classified, the pointer doc is written with `panelNotFoundCount: 0`. This is the explicit reset signal. A missing counter field on a pre-existing pointer doc is treated as 0 on read. Rationale: writing `0` rather than omitting the field prevents ambiguity between "never got panel-not-found" and "counter was reset"; it also ensures the counter does not accumulate across unrelated error types.

6. **Fallback-path language selection uses a 4-level priority chain.** The exact chain (exact-match → prefix-match → first-auto-generated → first-available) was chosen to maximize the probability of fetching the correct language on multi-language videos while guaranteeing at least one track is selected if any exist. This is a judgment call; the slice definition says "mirroring the primary path's selectedLanguage semantics as closely as json3 metadata allows" without specifying the exact chain.

7. **No new `logger.*()` call sites; only new fields on existing call sites.** The shape's constraint is "no new log lines." Interpreted strictly: no new `logger.warn()`, `logger.info()`, or `logger.error()` call expressions. Existing call sites receive additional structured fields (`errorClass`, `httpStatus`, `source`). The counter flip to unavailable uses the existing transient/unavailable write path's logger call with updated context. Rationale: this satisfies the "no new log lines" constraint while adding the observability signals the instrument augmentation requires.

8. **`timedtext-json3-sample.json` segments match the primary-path fixture.** The json3 fixture is constructed to produce the same two segments (`{start:0, text:"Hello world"}` and `{start:2.5, text:"Next sentence."}`) as the existing `seg("0", "Hello world")` / `seg("2500", "Next sentence.")` fixture values in `transcript-fetch.test.ts`. This makes the byte-equivalence assertion a straightforward string comparison rather than a separate expected-value definition.

9. **Docs target location not decided here.** The ops how-to and CHANGELOG entry are owned by the production-watch slice (per the open question in `00-index.md`). No new documentation is authored in this slice.

## Blockers

None. All acceptance criteria are satisfiable on the existing dev host with the existing toolchain. No new dependencies. `getBasicInfo(videoId, {client:'ANDROID'})` is available at v17.2.0 (introduced at v15 per the CHANGELOG research carried from shape stage).

## Freshness Research

- Source: LuanRT/YouTube.js CHANGELOG + codebase scan — carried from shape-stage research (same session, 2026-07-11). No new releases since shape.
  Why it matters: confirms `getBasicInfo(videoId, {client:'ANDROID'})` call signature and `captions.caption_tracks` shape in v17.
  Takeaway: v15 added `{client}` options to `getBasicInfo`; v17.2.0 is the current pin. No breaking changes to the `getBasicInfo` or captions surface in v15–v17.

- Source: Node.js global `fetch` stability — Node 18 (unflagged since 18.0.0); Firebase Gen 2 runtime runs Node 18+.
  Why it matters: the timedtext GET uses global `fetch` — rung 2 (native platform), no new dependency.
  Takeaway: available without import; no polyfill needed. `vi.stubGlobal('fetch', ...)` is the standard vitest mock approach.

- Source: YouTube timedtext json3 format — community documentation (yt-dlp source, youtube-transcript-api source) and shape-stage research.
  Why it matters: `parseJson3Segments` must correctly parse real json3 responses.
  Takeaway: format is `{events: [{tStartMs, dDurationMs, segs: [{utf8}]}]}`. Events without `segs` (auto-formatting markers) are filtered out by the `Array.isArray(segs)` check. The fixture covers the minimum valid shape.

## Recommended Next Stage

- **Option A (default):** implement this slice — 10 steps, fully autonomous on the dev host, compile-gated. The prerequisite slice (`upgrade-and-adapt`) is already complete and verified. Once this slice's suite is green, production-watch can deploy the combined artifact.
- **Option B:** revisit the slice definition if the ANDROID `getBasicInfo` API surface at v17 reveals an unadvertised shape mismatch (e.g., `captions.caption_tracks` renamed). Unlikely based on research but possible across four majors.

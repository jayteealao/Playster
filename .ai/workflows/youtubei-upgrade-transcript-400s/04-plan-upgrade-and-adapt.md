---
schema: sdlc/v1
type: plan
slug: youtubei-upgrade-transcript-400s
slice-slug: upgrade-and-adapt
status: complete
stage-number: 4
created-at: "2026-07-11T08:24:44Z"
updated-at: "2026-07-11T08:24:44Z"
metric-files-to-touch: 12
metric-step-count: 9
has-blockers: false
revision-count: 0
revisions: []
tags: [backend, dependencies, youtubei.js, typescript]
stack-source: confirmed
refs:
  index: 00-index.md
  plan-index: 04-plan.md
  slice-def: 03-slice-upgrade-and-adapt.md
  siblings: [04-plan-fallback-and-error-taxonomy.md, 04-plan-production-watch.md]
  implement: 05-implement-upgrade-and-adapt.md
next-command: wf-verify
next-invocation: "/wf verify youtubei-upgrade-transcript-400s upgrade-and-adapt"
---

# Plan: Upgrade & Adapt

## The Plan

The job is mechanical but precise: change one version number, let the TypeScript compiler find everything it breaks, fix each break at the call site, and ship a clean green suite. The adaptation scope is known from four majors of release notes and the codebase scan: `getInfo()`'s client argument moved into an options object at v15, the player evaluator went async at v16, and v17 removed `getTrending`. The transcript API itself is untouched across all four. The more interesting work is `tv-oauth.ts`, which carried a post-create session-context patch and an `as any` cast — workarounds for v13 internals that v17 should make obsolete.

Twelve files move, but most moves are shallow. The version pin and lockfile regeneration are a one-liner. `fetch.ts` adapts one call-site signature. `tv-oauth.ts` sheds its workarounds and gets a cleaner type. The four ops scripts are outside the test suite and get an explicit compile-smoke step instead. Everything is compile-gated: the plan does not advance past step 3 until `tsc` exits 0.

The feared ESM wall is a non-issue confirmed by research — `module: nodenext` already compiled against the ESM-only 13.x. The real risk is drift that the release notes did not mention, which is why the compiler is the authority and every error is resolved before the test run. The second risk is that removing the tv-oauth context patch assumes v17 natively sends the correct wire names — if it doesn't, the Watch Later sync regresses silently until the production-watch slice catches it. That is by design: this slice's responsibility ends at the unit-test gate; the production half lives in the next-next slice.

This slice will deliberately not fix the production 400s. That is the expected outcome.

## Current State

- `backend/functions/package.json`: `"youtubei.js": "^13.0.0"` — pinned with caret; actual installed version is 13.x.
- `src/auth/innertube.ts` (35 LOC): `Innertube.create({ cookie })` — straightforward, low adaptation risk.
- `src/auth/tv-oauth.ts` (124 LOC): two v13 workarounds: (1) `client_type: "TV" as any` — the TypeScript type in v13 did not accept `"TV"` without a cast; (2) post-create context patch setting `clientName = "TVHTML5"` and `clientVersion = "7.20250812.07.00"` — needed because v13 sent the enum tag ("TV") as the wire name rather than the correct InnerTube wire name ("TVHTML5"). The `ICache` interface is a local copy (inlined because v13 does not export it publicly).
- `src/transcript/fetch.ts` (203 LOC): calls `innertube.getInfo(videoId)` then `videoInfo.getTranscript()`. The duck-typed segment parsing (`start_ms: string`, `snippet.text: string`) is implementation-detail agnostic.
- `src/youtube/innertube-sync.ts` (766 LOC): only youtubei.js surface is `innertube.actions.execute("/browse", {...})` — raw API call, type-safe only at the call boundary, the rest is untyped JSON traversal. Low breakage risk.
- `scripts/*.mjs` (4 files): all import `{ Innertube, UniversalCache }` from `youtubei.js`. `test-tv-oauth.mjs` and the raw/pagination scripts also use `client_type` string arguments and apply the same context patch that tv-oauth.ts carries.
- `tests/transcript-fetch.test.ts` (255 LOC): `getInnertubeClient` fully mocked via `vi.mock`; the mock returns a hand-crafted object with `getInfo()` → `getTranscript()`. The test is insulated from youtubei.js types at runtime.
- `tests/transcript-automation.test.ts` (197 LOC): `fetchTranscript` fully mocked; zero youtubei.js surface visible.
- No explicit `tv-oauth.test.ts` file exists. The AC8a "existing tv-oauth unit tests" refers to the overall suite remaining green, and the observable: false annotation on AC8a confirms the verification is compile + suite-green, not a dedicated unit assertion.

## Simplicity Ladder

- **Dependency bump** → rung 3 (reuse already-installed dependency): bump the pin; no new dependency.
- **v17 API adaptation** → rung 3 (reuse existing call sites): adapt in-place; no new wrapper layer needed. Rung 4 (new code) only if a removed API has no v17 replacement — research shows no such case for the surfaces this slice touches.
- **`client_type` type fix** → rung 2 (native platform feature): v17 likely exports a proper `ClientType` type/enum; use it rather than a string-literal cast. Import the type if available.
- **`ICache` alignment** → rung 3 (reuse): check if v17 exports `ICache`; if so, remove the local copy and import. If not, keep the local copy — it satisfies the interface by structural typing.
- **Context-patch removal** → rung 2 (native platform): v17 natively sends correct wire names per the CHANGELOG entry adding proper client string support. Implement by deletion, not replacement.
- **Script adaptation** → rung 3 (reuse existing scripts): minimal in-place edits to import paths and string values.

## Applied Learnings

No applicable learnings found — `.ai/solutions/INDEX.md` does not exist in this repository.

No prior runtime-evidence-deferrals in `00-index.md` (`plan: not-started` at plan time; this is the first planned slice).

## Likely Files / Areas to Touch

- `backend/functions/package.json`: version pin change — 1 line
- `pnpm-lock.yaml` (workspace root): regenerated; not hand-edited
- `backend/functions/src/auth/innertube.ts`: `Innertube.create` options check
- `backend/functions/src/auth/tv-oauth.ts`: v17 modernization (primary adaptation work in this file)
- `backend/functions/src/transcript/fetch.ts`: `getInfo()` call signature
- `backend/functions/src/youtube/innertube-sync.ts`: `actions.execute` compile check
- `backend/functions/scripts/setup-tv-oauth.mjs`: `UniversalCache`, `client_type`
- `backend/functions/scripts/test-tv-oauth.mjs`: `UniversalCache`, `client_type`, context patch
- `backend/functions/scripts/test-tv-oauth-raw.mjs`: `UniversalCache`, `client_type`
- `backend/functions/scripts/test-tv-oauth-pagination.mjs`: `UniversalCache`, `client_type`
- `backend/functions/tests/transcript-fetch.test.ts`: mock type adaptation if needed
- `backend/functions/tests/transcript-automation.test.ts`: likely no-op (fully mocked)

## Proposed Change Strategy

Compile-driven, incremental:

1. Bump the pin. Regenerate the lockfile.
2. Run `tsc` to get the complete error catalogue. Fix errors in priority order: (a) `tv-oauth.ts` workarounds — remove the `as any` cast and the context patch; (b) `fetch.ts` call-site adaptations; (c) `innertube.ts` if any; (d) `innertube-sync.ts` if any.
3. Once `tsc` exits 0, run the full suite with the Firestore emulator.
4. Adapt tests only if they fail due to type changes in the mocked shapes — behavioral assertions are not changed.
5. Adapt the ops scripts last (outside the test-suite gate; use `node --check` as a substitute).

The v17 CHANGELOG confirms: no changes to the `getTranscript()` API, no changes to the `actions.execute()` API. The only transcript-path change required is if `getInfo()` gained a required options argument at v15. The research summary says v15 "moves getInfo client into an options object" — this affects passing a specific client type, not the default call. The existing `innertube.getInfo(videoId)` (no client override) should still work in v17; only adapt if tsc or tests say otherwise.

## Step-by-Step Plan

1. **Pin the dependency.** In `backend/functions/package.json`, change `"youtubei.js": "^13.0.0"` to `"youtubei.js": "17.2.0"`. This is the only hand edit to this file.

2. **Regenerate the lockfile.** From the workspace root, run `pnpm install`. This resolves youtubei.js 17.2.0 and updates `pnpm-lock.yaml`. If pnpm cannot satisfy peer dependencies, resolve them before proceeding.

3. **Run build and catalogue compiler errors.** Run `pnpm --prefix backend/functions run build` (which runs `tsc`). Capture all errors. This is the definitive breakage list. Do not proceed to step 4 until the list is captured.

4. **Modernize `src/auth/tv-oauth.ts`.** This is the highest-signal adaptation:
   a. Remove the `client_type: "TV" as any` cast. Replace with the v17-correct value: if youtubei.js v17 exports `ClientType` (or a similar enum/type), import and use `ClientType.TV`. If v17 accepts a plain string `"TV"`, use that. If tsc requires a different string (e.g., `"TVHTML5"`), use the compiler's suggestion. Use `pnpm why youtubei.js` or read the package's type declarations to determine the correct value.
   b. Remove the session-context patch lines — the two assignments to `innertube.session.context.client.clientName` and `innertube.session.context.client.clientVersion`. v17 handles these natively. If the compile or runtime reveals v17 still needs them (unexpected), restore them with a comment noting the v17 situation.
   c. Check if youtubei.js v17 now exports `ICache` publicly. If it does: remove the local `interface ICache` block and import the type. If not: keep the local copy; update any fields that tsc flags as mismatched.

5. **Adapt `src/transcript/fetch.ts`.** If step 3 revealed errors in this file, fix them. Expected: `getInfo(videoId)` may need `getInfo(videoId, {})` or similar if v17 made the options object non-optional. The `getTranscript()` call and the segment duck-typing are unchanged; do not touch them.

6. **Adapt `src/auth/innertube.ts`.** If step 3 revealed errors, fix them. The `Innertube.create({ cookie })` call is low risk; likely a no-op.

7. **Verify `src/youtube/innertube-sync.ts` compiles.** If step 3 revealed errors, adapt only what tsc flags. The `actions.execute("/browse", {...})` call should be unaffected; if the return type changed, update the `as any` cast comment.

8. **Adapt `scripts/*.mjs`.** For each of the four scripts:
   a. Check `UniversalCache` still exports from `'youtubei.js'` in v17. If the import path changed, update it.
   b. Check the `client_type` string values (`"TV"`, `"TV_SIMPLY"`, `"TV_EMBEDDED"`) — if v17 changed accepted values, update them to match what the v17 type declarations accept. The context-patching lines in the scripts (e.g., `innertube.session.context.client.clientName = "TVHTML5"`) are preserved as-is; they are harmless even if v17 fixes the underlying bug natively.
   c. After each edit, run `node --check scripts/<name>.mjs` from `backend/functions/` to verify the script syntax is valid. This is the substitute gate for files outside the test suite.

9. **Run the full test suite.** Start the Firestore emulator (`firebase emulators:start --only firestore` or the configured combined emulator command). Then run `pnpm --prefix backend/functions run test`. This runs vitest with `fileParallelism: false` (already configured in `vitest.config.ts`) against all `tests/**/*.test.ts` files. Gate: all tests must pass. If tests fail:
   a. If failures are type-only (mock shape mismatch), adapt the mock in the relevant test file. Do not change behavioral assertions (status values, blob text format, pointer-doc field names).
   b. If failures indicate a behavioral change in the youtubei.js library itself (unexpected for this slice's scope), surface the finding before proceeding — this would require revisiting the plan.

## Verification Strategy

| AC | Tool / method + ladder rung | Environment need — satisfiable? | What must be built | Fallback chain |
|----|------------------------------|---------------------------------|--------------------|----------------|
| AC1: build + test green, package.json shows 17.2.0 exact | `pnpm --prefix backend/functions run build` + `pnpm --prefix backend/functions run test` (backend-1: local emulator suite) | Windows dev host with pnpm, Java (Firestore emulator), firebase CLI — all present, nothing to install | No new seams needed; existing test infrastructure sufficient | None; dev host already satisfies all env needs |
| AC2: existing primary-path tests adapted, still passing | vitest assertions in `tests/transcript-fetch.test.ts` (backend-1: local emulator suite) | Same as AC1 | Existing mock shapes adapted if v17 changes visible types; behavioral assertions preserved | None |
| AC8a: tv-oauth unit tests pass, no behavioral change to Watch Later walk | Full vitest suite (backend-1: local emulator suite); no dedicated tv-oauth test file — AC observable: false, so suite-green is sufficient | Same as AC1 | No new test fixtures needed; coverage is compile-correctness + existing suite green | None |

**Constraint resolutions:**
- AC1: `constraint-resolution: po-accepted: All verification is local — Windows dev host with existing toolchain (pnpm, Java, firebase CLI, gcloud). Pre-verified in shape round 4.`
- AC2: `constraint-resolution: po-accepted: Fully automated; no live service dependency.`
- AC8a: `constraint-resolution: proxy+deferral: Proxy = suite green (observable: false); production regression check (AC8b) pre-registered as a post-deploy clearing event in the production-watch slice.`

## Test / Verification Plan

### Automated checks

- **lint/typecheck:** `pnpm --prefix backend/functions run build` (runs `tsc --noEmit` equivalent via tsconfig). Must exit 0. Run this after every edit cycle, not just at the end.
- **unit tests:** `pnpm --prefix backend/functions run test` — full vitest suite. All 17 test files must pass.
- **script smoke:** `node --check scripts/<name>.mjs` for each of the four ops scripts. Not a test suite substitute, but catches syntax and import errors.

### Interactive verification (human-in-the-loop)

None required for this slice. All ACs are `observable: false` or automated. The build commands are deterministic on the local Windows dev host. No live YouTube API calls, no browser, no device.

## Risks / Watchouts

- **Unlisted type changes** — the four-major jump means corners of the type definitions may have changed without a release note. The compiler is authoritative; every tsc error must be addressed before the suite runs.
- **`client_type` string values** — if v17 changed the accepted string literals (e.g., `"TV"` → `"TVHTML5"` at the `Innertube.create()` level), both `tv-oauth.ts` and the scripts need consistent updates. The ops scripts patch the context post-create so inconsistency there is harmless, but the `tv-oauth.ts` change affects production.
- **Session context patch removal risk** — removing the post-create `clientName`/`clientVersion` patch is the highest behavioral risk in this slice. The patch was authored because v13 sent `"TV"` on the wire instead of `"TVHTML5"`. If v17 did not fix this internally, Watch Later sync will silently fail. The implementer must check whether v17's `ClientType.TV` resolves to the correct wire name before removing the patch lines.
- **ESM import paths** — not a risk per research; `module: nodenext` already handles it. But if any test or script imports change path (e.g., from `youtubei.js` to `youtubei.js/dist/...`), update the import.
- **pnpm peer-dep conflicts** — bumping four majors may introduce new peer dependencies. Resolve any `pnpm install` warnings before building.

## Dependencies on Other Slices

This is the foundation slice. No upstream dependencies. The `fallback-and-error-taxonomy` slice depends on the v15+ `getBasicInfo(videoId, { client: 'ANDROID' })` signature that is only available post-upgrade; that slice cannot proceed until this one is on the branch.

## Assumptions

The following decisions were made autonomously (no user input taken) per the plan-stage autonomous-override policy. Each is recorded here for auditability.

1. **Test adaptation scope — behavioral assertions are immutable.** The existing tests' output expectations (blob text format `"<start> <text>\n"`, pointer-doc field names, status strings) are treated as fixed contracts. Only mock shapes change if v17's TypeScript types differ. Reason: this slice's goal is "existing tests adapted, still passing" — not "tests pass by loosening assertions."

2. **Context-patch removal is conditional on v17 evidence.** The plan removes the session-context patch assuming v17 sends correct wire names natively. The implementer is instructed to inspect v17's type declarations and, if the evidence is unclear, to keep the patch with a comment. Reason: the production regression (AC8b) is not observed until the production-watch slice; leaving the patch in place as a harmless guard is safer than a silent regression.

3. **Ops scripts keep their context-patching lines.** Even if v17 no longer needs the `clientName`/`clientVersion` override, the scripts' post-create assignments are preserved. They are harmless (a no-op if v17 already sends the correct name), and removing them from scripts that are not test-covered introduces unnecessary risk. Reason: minimum blast radius per the slice's "nothing more" constraint.

4. **`ICache` import preference over local copy.** If v17 exports `ICache` publicly from `youtubei.js`, the plan prefers importing over maintaining a local duplicate. If not exported, the local copy is kept. Reason: a local copy of an interface drifts silently; importing the type keeps the adapter contract in sync with the library.

5. **No tv-oauth dedicated unit test created.** AC8a is `observable: false` and scoped to "existing tv-oauth unit tests" — which is effectively the suite-green gate. No new test file is added in this slice; dedicated unit testing of the OAuth adapter would require mocking the Innertube session internals, which is out of scope for a mechanical adaptation. Reason: smallest scope that satisfies the AC as specified.

6. **Documentation target location deferred.** The docs how-to (ops runbook + CHANGELOG) lives in the production-watch slice per PO decision at slice stage. The open question in `00-index.md` (`backend/README.md` section vs `docs/`) remains open and will be resolved during planning of that slice.

7. **`getInfo(videoId)` default-client call assumed still valid.** The v15 change "moves getInfo client into an options object" added an optional `{client}` parameter, not a required one. The existing `innertube.getInfo(videoId)` call (no client override) is expected to still work in v17. The plan adapts this call only if tsc reports a type error. Reason: overengineering a call that is expected to compile would silently change the default client — only adapt what breaks.

## Blockers

None. All acceptance criteria are satisfiable on the existing dev host with no new tooling.

## Freshness Research

- Source: LuanRT/YouTube.js CHANGELOG (read during shape stage, carried forward — same day, no new releases)
  Why it matters: definitive breaking-change inventory 13→17.
  Takeaway: v15 drops CJS (non-issue), v15 adds `{client}` option to `getInfo` (optional, default still valid), v16 makes player-JS extraction async (internal; surfaces at Innertube.create() if the session evaluator is awaited separately), v17 removes `getTrending` and reshuffles search filters. No transcript API changes; `getTranscript()` surface is stable.

- Source: Codebase scan (this session)
  Why it matters: identifies the actual v13 workarounds to modernize.
  Takeaway: `tv-oauth.ts` has two explicit workarounds documented in comments: the `as any` cast on `client_type` and the context-patch lines. Both are labeled for removal at v13→v17. `innertube-sync.ts` is structurally insulated (raw JSON walk, `as any` on responses). `fetch.ts` is clean. Scripts follow the same patterns as `tv-oauth.ts`.

- Source: TypeScript 5.7, pnpm, vitest v2 — no version changes expected
  Why it matters: toolchain compatibility with v17 ESM.
  Takeaway: no action needed; the toolchain already handles ESM-only packages.

## Recommended Next Stage

- **Option A (default):** implement this slice — 9 steps, fully autonomous on the dev host, compile-gated. Once green, the fallback-and-error-taxonomy slice can begin against the v17 baseline.
- **Option B:** revisit the slice definition if step 3 reveals a v17 API surface that is genuinely incompatible (e.g., a removed method with no replacement). Unlikely based on research but the plan acknowledges the path.

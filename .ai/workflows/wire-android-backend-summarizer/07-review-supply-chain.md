---
review-command: supply-chain
slug: wire-android-backend-summarizer
date: 2026-05-21
scope: diff
target: git diff main...HEAD
paths: "package.json, pnpm-lock.yaml, *.toml, Dockerfile, *.gradle.kts"
related:
  shape: 02-shape.md
  index: 00-index.md
---

# Supply Chain Security Review — wire-android-backend-summarizer

**Reviewed:** branch diff / `git diff main...HEAD`
**Date:** 2026-05-21
**Reviewer:** Claude Code (supply-chain rubric)

---

## 0) Scope, Context, and Dependency Policy

**What was reviewed:**

- Scope: full branch diff vs `main`
- Dependency files touched: `backend/functions/package.json`, `pnpm-lock.yaml` (root +562 lines), `summarizer/summarize-daemon/pnpm-lock.yaml` (+3 lines), `summarizer/summarize-daemon/package.json`, `android/gradle/libs.versions.toml`, `android/app/build.gradle.kts`, `summarizer/deploy/Dockerfile` (new consolidated), `summarizer/summarize-api/Dockerfile` (deleted), `summarizer/deploy/daemon/Dockerfile` (deleted), `summarizer/deploy/daemon/entrypoint.sh` (deleted), `summarizer/deploy/mock-backend/Dockerfile` (new).
- Changed direct deps (Node/npm): +4 devDeps (`@firebase/rules-unit-testing ^5.0.0`, `@types/node ^22.0.0`, `firebase ^12.0.0`, `vitest ^2.1.0`) and 1 bump (`firebase-functions ^6.3.0` → `^7.2.5`), +1 to daemon (`undici 7.25.0`).
- Changed Android deps: Firebase BOM `33.4.0` + 3 Firebase artifacts replacing 4 YouTube Data API artifacts; `kotlinx-coroutines-play-services 1.7.3`; `compose-markdown 0.7.2`; Google Services plugin `4.4.2`.

**Deployment context:**

- **Environment:** Production (Cloud Run) + Android client
- **Trust model:** Public npm registry (pnpm); JitPack for Android (compose-markdown); GitHub binary release for yt-dlp
- **Signing policy:** None enforced beyond lockfile integrity hashes
- **Allowed registries:** npm default, Google Maven, MavenCentral, JitPack

**Dependency policy (inferred):**

- Version pinning: semver ranges (^ / exact) depending on package; exact pin on `undici 7.25.0` in daemon
- CVE threshold: not formally documented; review required before merge
- Subtree pinned to SHA `0ec12ac` per `SUBTREE_PIN.md` ✅

**Assumptions:**

- Single-tenant personal project; no enterprise supply-chain policy document exists.
- `firebase-functions` v7 bump was a deliberate peer-conflict resolution per shape spec (see §Dependency hygiene in `02-shape.md`).
- yt-dlp installed from GitHub binary release (not pip, not apt), which is per spec.

---

## 1) Executive Summary

**Merge Recommendation:** APPROVE_WITH_COMMENTS

**Rationale:**

No known critical CVEs, no malicious packages, and no typosquatting candidates were identified among the changed dependencies. The most significant supply-chain risks are: (1) all Docker base images are unpinned by digest—mutable tags that could silently pull in compromised layers; (2) GitHub Actions in CI use mutable `@v4`/`@v3` version tags rather than SHA pins; and (3) `compose-markdown` is sourced from JitPack with no integrity-hash mechanism available to Gradle, introducing a weaker trust anchor than Maven Central. These are real but not blocking for a single-operator personal project with no secrets stored in CI artifacts.

**Critical Supply Chain Risks (BLOCKER):** None.

**High-Risk Issues:**

1. **SUP-1**: Unpinned Docker base images (`node:24-slim`, `node:22-slim`) in production Dockerfile and mock-backend Dockerfile—mutable tags.
2. **SUP-2**: GitHub Actions use mutable version tags (`@v4`, `@v3`, `@v2`) rather than commit-SHA pins.

**Overall Supply Chain Posture:**

- Dependency Hygiene: Good (lockfiles committed, integrity hashes present, semver ranges reasonable)
- Vulnerability Management: Adequate (no known CVEs; no automated scanning in CI)
- Build Security: Needs Work (unpinned Docker images; CI actions unpinned)
- Provenance: Partial (npm/Maven integrity hashes; JitPack no Gradle checksum verification; yt-dlp GitHub binary verified via version check only)

**Scan Results:**

- CVEs found: 0 (manual assessment; no `npm audit` output available in diff)
- Malicious packages: 0
- Risky install scripts: 0 (pnpm `onlyBuiltDependencies` whitelists `better-sqlite3` and `esbuild` in `summarize-api`; no unexplained postinstall scripts in new deps)
- Unpinned Docker images: 4 (node:24-slim ×3 stages, node:22-slim ×1 mock-backend)
- Unpinned CI actions: 6 actions across 3 workflows

---

## 2) Dependency Changes

### Node/npm — Added or Bumped

| Package | Old Version | New Version | Type | Risk |
|---------|------------|-------------|------|------|
| `firebase-functions` | `^6.3.0` | `^7.2.5` | dep (backend) | Low — intentional peer-fix |
| `@firebase/rules-unit-testing` | — | `^5.0.0` | devDep (backend) | Low — Google-published testing lib |
| `@types/node` | — | `^22.0.0` | devDep (backend) | Low — DefinitelyTyped |
| `firebase` | — | `^12.0.0` | devDep (backend) | Low — Google SDK; used for emulator |
| `vitest` | — | `^2.1.0` | devDep (backend) | Low — well-maintained OSS test runner |
| `undici` | — | `7.25.0` (exact) | dep (daemon subtree) | Low — Node.js core fetch impl; exact-pinned |

**Lockfile pull-in:** `firebase ^12.0.0` brings in the full `@firebase/*` compat suite (~30 packages) as transitive deps. All are Google-published, integrity-hashed in the lockfile, no `postinstall` hooks.

### Android — Added

| Package | Version | Source | Risk |
|---------|---------|--------|------|
| `com.google.firebase:firebase-bom` | `33.4.0` | Google Maven | Low |
| `com.google.firebase:firebase-auth` | BOM-managed | Google Maven | Low |
| `com.google.firebase:firebase-firestore` | BOM-managed | Google Maven | Low |
| `com.google.firebase:firebase-functions` | BOM-managed | Google Maven | Low |
| `org.jetbrains.kotlinx:kotlinx-coroutines-play-services` | `1.7.3` | MavenCentral | Low |
| `com.github.jeziellago:compose-markdown` | `0.7.2` | JitPack | MED — see SUP-3 |
| `com.google.gms:google-services` (plugin) | `4.4.2` | Google Maven | Low |

### Android — Removed

| Package | Reason |
|---------|--------|
| `com.google.api-client:google-api-client-android` | Dropped per spec (no direct YouTube API from device) |
| `com.google.apis:google-api-services-youtube` | Dropped per spec |
| `com.google.api-client:google-api-client-gson` | Dropped with above |
| `com.google.http-client:google-http-client-android` | Dropped with above |

Removal is intentional and consistent with the shape-spec decision to eliminate direct YouTube Data API calls from the Android client. ✅

### Deleted Build Artifacts

| File | Status |
|------|--------|
| `summarizer/summarize-api/Dockerfile` | Deleted — replaced by consolidated `summarizer/deploy/Dockerfile` |
| `summarizer/deploy/daemon/Dockerfile` | Deleted — orphaned, no references found |
| `summarizer/deploy/daemon/entrypoint.sh` | Deleted — orphaned, no references found |

Verified: `docker-compose.yml` references only `summarizer/deploy/Dockerfile`; no remaining references to deleted files in `.yml`, `.sh`, `.json`, or `Makefile` found. ✅

---

## 3) Findings Table

| ID | Severity | Confidence | Category | Location | Issue |
|----|----------|------------|----------|----------|-------|
| SUP-1 | HIGH | High | Unpinned artifact | `summarizer/deploy/Dockerfile:17,31,44`; `summarizer/deploy/mock-backend/Dockerfile:1` | Docker base images use mutable tags (`node:24-slim`, `node:22-slim`) without SHA256 digest pins |
| SUP-2 | HIGH | High | Unpinned CI action | `.github/workflows/pr_check.yml`, `release.yml`, `manual-release.yml` | GitHub Actions referenced by mutable version tags (`@v4`, `@v3`, `@v2`) |
| SUP-3 | MED | Med | Package source trust | `android/gradle/libs.versions.toml:compose-markdown` | `compose-markdown 0.7.2` sourced from JitPack (no Gradle checksum enforcement); JitPack fetches from GitHub on demand |
| SUP-4 | MED | High | Version range | `backend/functions/package.json:firebase-functions` | `^7.2.5` allows any `7.x` minor/patch to install without lockfile regeneration in CI `pnpm install` (vs `pnpm ci --frozen-lockfile`) |
| SUP-5 | MED | High | Node engines mismatch | `package.json:packageManager` vs `summarizer/deploy/Dockerfile` | Root workspace declares `packageManager: pnpm@9.0.0`; Dockerfile installs `pnpm@10.33.2`; daemon declares `packageManager: pnpm@10.33.2`. pnpm@9 and pnpm@10 produced the lockfile; CI/local may see `ERR_PNPM_BAD_PM_VERSION` or silently use wrong version |
| SUP-6 | MED | Med | yt-dlp binary provenance | `summarizer/deploy/Dockerfile:55-58` | yt-dlp installed via `curl` from GitHub releases with no checksum verification; only `--version` tee used for evidence; no SHA256 or sigstore signature checked |
| SUP-7 | LOW | High | Unused dep | `backend/functions/package.json` | `firebase-functions-test ^3.4.0` (Jest-based) remains while `vitest ^2.1.0` was added; two test harnesses present; the `jest` peer dep is pulled in as `jest@30.4.2` transitively via `firebase-functions-test` |
| SUP-8 | LOW | Med | CI — no dependency audit | `.github/workflows/pr_check.yml` | CI runs Gradle build only; no `npm audit` or `pnpm audit` step added; no automated CVE scanning for backend or daemon |
| SUP-9 | NIT | High | `summarize-api` missing `engines` | `summarizer/summarize-api/package.json` | No `engines` field; runs on node:24 in production but not documented; minor inconsistency with daemon's `engines: {node: ">=24"}` |

**Findings Summary:**

- BLOCKER: 0
- HIGH: 2
- MED: 4
- LOW: 2
- NIT: 1

---

## 4) Findings (Detailed)

### SUP-1: Unpinned Docker Base Images [HIGH]

**Location:**
- `summarizer/deploy/Dockerfile` lines 17 (`FROM node:24-slim AS daemon-build`), 31 (`FROM node:24-slim AS api-build`), 44 (`FROM node:24-slim AS runtime`)
- `summarizer/deploy/mock-backend/Dockerfile` line 1 (`FROM node:22-slim`)

**Issue:** Docker tags are mutable. A compromised push to `node:24-slim` on Docker Hub would silently be picked up on the next `docker build` in CI or on Cloud Run deploy, without any alert.

**Evidence from harness logs:** The harness captured actual resolved digests during local test:
- `node:24-slim@sha256:24dc26ef1e3c3690f27ebc4136c9c186c3133b25563ae4d7f0692e4d1fe5db0e`
- `node:22-slim@sha256:689c11043dad91472750cd824c97dd5e2318e9dd6f954e492fe7af0135d33ceb`

These digests are available and should be pinned.

**Attack vector:** Registry tag poisoning; CI build cache poisoning via updated upstream layer.

**Remediation:**

```dockerfile
FROM node:24-slim@sha256:24dc26ef1e3c3690f27ebc4136c9c186c3133b25563ae4d7f0692e4d1fe5db0e AS daemon-build
FROM node:24-slim@sha256:24dc26ef1e3c3690f27ebc4136c9c186c3133b25563ae4d7f0692e4d1fe5db0e AS api-build
FROM node:24-slim@sha256:24dc26ef1e3c3690f27ebc4136c9c186c3133b25563ae4d7f0692e4d1fe5db0e AS runtime
```

And for mock-backend:
```dockerfile
FROM node:22-slim@sha256:689c11043dad91472750cd824c97dd5e2318e9dd6f954e492fe7af0135d33ceb
```

Use Dependabot or Renovate's Docker support to auto-update digests weekly.

**Note:** The mock-backend is only used in the local docker-compose harness; its risk surface is lower than the production image. Prioritize the production Dockerfile first.

**Severity:** HIGH | **Confidence:** High

---

### SUP-2: Unpinned GitHub Actions [HIGH]

**Location:** `.github/workflows/pr_check.yml`, `release.yml`, `manual-release.yml`

**Actions using mutable tags:**
```yaml
uses: actions/checkout@v4                    # mutable
uses: actions/setup-java@v4                  # mutable
uses: gradle/actions/setup-gradle@v3         # mutable
uses: android-actions/setup-android@v3       # mutable
uses: actions/github-script@v7              # mutable
uses: actions/upload-artifact@v4             # mutable
uses: orhun/git-cliff-action@v4             # mutable
uses: softprops/action-gh-release@v2        # mutable
```

**Risk:** A compromised action maintainer account or a tag-redirect attack could execute arbitrary code in CI with access to `secrets.GITHUB_TOKEN`, `SIGNING_STORE_FILE_B64`, `SIGNING_STORE_PASSWORD`, `SIGNING_KEY_ALIAS`, `SIGNING_KEY_PASSWORD`, `GOOGLE_SERVICES_JSON_B64`. These secrets would be exposed on every PR build.

**Remediation (sample for pr_check.yml):**
```yaml
# Get SHA via: git ls-remote https://github.com/actions/checkout refs/tags/v4
uses: actions/checkout@11bd71901bbe5b1630ceea73d27597364c9af683  # v4.2.2
uses: actions/setup-java@c5195efecf7bdfc987ee8bae7a71cb8b11521c15  # v4.5.0
uses: gradle/actions/setup-gradle@06832c7b30a0129d7fb559bcc6e43d26f6374244  # v3.5.0
uses: android-actions/setup-android@00854ea68c109d98c75d956347303bf7c45b0277  # v3.2.1
```

**Severity:** HIGH | **Confidence:** High

---

### SUP-3: compose-markdown Sourced from JitPack [MED]

**Location:** `android/gradle/libs.versions.toml` — `compose-markdown = { module = "com.github.jeziellago:compose-markdown", version.ref = "compose-markdown" }`; `android/settings.gradle.kts` — `maven { url = uri("https://jitpack.io") }`

**Issue:** JitPack builds artifacts from GitHub source on first request and caches them. Unlike MavenCentral/Google Maven, JitPack:
- Does not sign artifacts with a stable GPG key
- Does not provide Gradle module metadata with strict integrity hashes by default
- The cached artifact could change if JitPack's cache is cleared and the upstream commit's tag moves

`jeziellago/compose-markdown` is a legitimate, actively-maintained library (1.8k GitHub stars as of May 2026). The specific risk here is not malice but the weaker provenance guarantee compared to MavenCentral.

**Remediation options (in priority order):**
1. Check if the library is mirrored to MavenCentral (it is not, as of this review).
2. Add Gradle dependency verification (`gradle/verification-metadata.xml`) to capture and lock the artifact checksum.
3. Accept risk as LOW given single-tenant, personal project context, and document it.

**Severity:** MED | **Confidence:** Med

---

### SUP-4: firebase-functions Range Too Broad [MED]

**Location:** `backend/functions/package.json:21` — `"firebase-functions": "^7.2.5"`

**Issue:** The semver range `^7.2.5` allows any `7.x.y` version where `x >= 2` to be installed if `pnpm install` (not `--frozen-lockfile`) is run. The current lockfile pins `7.2.5` exactly, but any CI step that runs `pnpm install` without `--frozen-lockfile` would resolve a newer minor and introduce untested transitive deps.

**Context:** The bump from `^6.3.0` to `^7.2.5` was deliberate and spec-driven. The risk is not the current locked version but future drift if CI uses `pnpm install` rather than `pnpm install --frozen-lockfile`.

**Remediation:**
- Ensure CI runs `pnpm install --frozen-lockfile` (add `--frozen-lockfile` to the install step in workflows).
- Consider pinning to exact `"firebase-functions": "7.2.5"` for production deps to be explicit.

**Severity:** MED | **Confidence:** High

---

### SUP-5: pnpm Version Mismatch (root workspace vs Dockerfile) [MED]

**Location:**
- `package.json` root: `"packageManager": "pnpm@9.0.0"`
- `summarizer/deploy/Dockerfile` (api-build stage): `corepack prepare pnpm@10.33.2 --activate`
- `summarizer/summarize-daemon/package.json`: `"packageManager": "pnpm@10.33.2"`

**Issue:** The root workspace is declared as `pnpm@9.0.0` but the Dockerfile installs `pnpm@10.33.2` for the api-build stage (which uses the root `pnpm-lock.yaml`). The lockfile was generated by whatever pnpm version the developer used locally. pnpm 10 can typically read a pnpm 9 lockfile, but corepack may emit `ERR_PNPM_BAD_PM_VERSION` warnings or refuse to run depending on corepack version and `packageManager` enforcement.

**Remediation:**
- Either update root `packageManager` to `pnpm@10.33.2` and regenerate lockfile (preferred — makes Dockerfile and daemon consistent).
- Or downgrade Dockerfile api-build to `pnpm@9.0.0` to match root workspace (less consistent with daemon).

**Severity:** MED | **Confidence:** High

---

### SUP-6: yt-dlp Binary Installed Without Checksum Verification [MED]

**Location:** `summarizer/deploy/Dockerfile` lines 55–58

```dockerfile
ARG YT_DLP_VERSION=2026.02.21
RUN curl -fsSL "https://github.com/yt-dlp/yt-dlp/releases/download/${YT_DLP_VERSION}/yt-dlp" \
        -o /usr/local/bin/yt-dlp \
 && chmod a+rx /usr/local/bin/yt-dlp \
 && /usr/local/bin/yt-dlp --version | tee /etc/yt-dlp.version
```

**Issue:** The binary is downloaded from GitHub releases but not verified against a SHA256 checksum or signature. yt-dlp does publish SHA256 checksums (`SHA2-512SUMS` and `SHA2-256SUMS`) alongside each release. A MITM or a compromised GitHub release asset could substitute a malicious binary that would run with `a+rx` inside the container.

**Mitigating factors:** GitHub releases are served over TLS; `curl -fsSL` requires a valid cert. Risk is primarily from a supply-chain compromise of the yt-dlp GitHub release itself (low-probability, but not zero given yt-dlp's prominence).

**Remediation:**
```dockerfile
ARG YT_DLP_VERSION=2026.02.21
ARG YT_DLP_SHA256=<sha256-from-yt-dlp-SHA2-256SUMS-for-this-version>
RUN curl -fsSL "https://github.com/yt-dlp/yt-dlp/releases/download/${YT_DLP_VERSION}/yt-dlp" \
        -o /usr/local/bin/yt-dlp \
 && echo "${YT_DLP_SHA256}  /usr/local/bin/yt-dlp" | sha256sum -c - \
 && chmod a+rx /usr/local/bin/yt-dlp \
 && /usr/local/bin/yt-dlp --version | tee /etc/yt-dlp.version
```

Retrieve the SHA256 for `2026.02.21` from `https://github.com/yt-dlp/yt-dlp/releases/download/2026.02.21/SHA2-256SUMS` and bake it into the ARG.

**Note:** The shape spec explicitly mandates `pip install -U "yt-dlp>=2026.02.21"` — but the implementation correctly deviates to the static binary approach (which is actually safer because it is a fixed version, not `>=`). The spec language is now stale; binary approach with checksum is preferred.

**Severity:** MED | **Confidence:** Med

---

### SUP-7: Two Test Harnesses in Backend (jest + vitest) [LOW]

**Location:** `backend/functions/package.json` — `firebase-functions-test ^3.4.0` (pulls `jest@30.4.2` as peer) and `vitest ^2.1.0`

**Issue:** `firebase-functions-test` is a Jest-based testing framework; `vitest` is also present as a dedicated dependency. The lockfile shows `jest@30.4.2` being pulled in as a transitive peer of `firebase-functions-test`. Two test frameworks inflate the transitive dependency footprint (jest adds ~40 packages) unnecessarily.

**Risk:** Minimal for runtime; marginal supply-chain surface increase. Also a maintenance burden (two framework configs).

**Remediation:** If tests are all migrated to vitest (which appears to be the intent given the `vitest run` scripts), consider removing `firebase-functions-test` if its utilities have been replaced by `@firebase/rules-unit-testing` + vitest.

**Severity:** LOW | **Confidence:** High

---

### SUP-8: No Automated Dependency Audit in CI [LOW]

**Location:** `.github/workflows/pr_check.yml` — no `npm audit` or `pnpm audit` step

**Issue:** CI only builds the Android APK; there is no automated check for newly introduced CVEs in npm dependencies or Gradle dependencies.

**Remediation (minimal):**
```yaml
- name: Audit backend dependencies
  working-directory: backend/functions
  run: pnpm audit --audit-level=high
```

Or enable Dependabot security alerts on the repository (`Settings → Security → Dependabot alerts`).

**Severity:** LOW | **Confidence:** High

---

### SUP-9: summarize-api Missing `engines` Field [NIT]

**Location:** `summarizer/summarize-api/package.json` — no `engines` key

**Issue:** The daemon declares `"engines": {"node": ">=24"}` but the API gateway has no `engines` declaration. The production Dockerfile uses `node:24-slim` for both, so runtime is consistent, but a developer running `node` locally at v18 or v20 would get no warning.

**Remediation:**
```json
"engines": {
  "node": ">=24"
}
```

**Severity:** NIT | **Confidence:** High

---

## 5) Lockfile Health Assessment

### Root `pnpm-lock.yaml`

- **+562 lines** explained: ~30 new `@firebase/*` packages pulled in as transitive deps of `firebase ^12.0.0` (devDep for emulator testing); 6 other new package entries for `@firebase/rules-unit-testing`, `@types/node`, `vitest`, and their deps.
- All new entries have `resolution: {integrity: sha512-...}` hashes. ✅
- No commented-out fixtures, no `git+` or `tarball:` sources. ✅
- `undici` version split: `undici@5.29.0` (pre-existing, pulled in by older transitive dep) and `undici@7.25.0` (new from daemon and summarize-api). This is normal pnpm multi-version resolution, not a lockfile anomaly. ✅
- `lockfileVersion: '9.0'` matches pnpm 9 format (inconsistent with `packageManager: pnpm@9.0.0` in root but lockfile was likely regenerated with pnpm 10 — see SUP-5).

### `summarizer/summarize-daemon/pnpm-lock.yaml`

- +3 lines: adds `undici: 7.25.0` with exact pin. Integrity hash present. ✅
- Lockfile version `9.0` (pnpm@10 format). Consistent with daemon's `packageManager: pnpm@10.33.2`. ✅

---

## 6) Build Configuration Assessment

### Dockerfile (Production)

| Check | Result |
|-------|--------|
| Multi-stage build | ✅ 3 stages: daemon-build, api-build, runtime |
| Secrets in final image | ✅ None — no `.env` or secret files copied |
| Running as root | ⚠️ Yes — no `USER` directive; Cloud Run mitigates via gVisor sandbox |
| `--no-install-recommends` on apt | ✅ All apt steps use it |
| `rm -rf /var/lib/apt/lists/*` cleanup | ✅ Present in all apt layers |
| Base image pinned by digest | ❌ `node:24-slim` — see SUP-1 |
| `pnpm install --frozen-lockfile` | ✅ Both build stages use it |
| yt-dlp from pip (per spec mandate) | ⚠️ Binary download used instead — actually safer but deviates from spec wording (spec says `pip install`; implementation uses binary; binary is preferable and the shape spec note is stale) |

### CI/CD (GitHub Actions)

| Check | Result |
|-------|--------|
| Third-party actions pinned by SHA | ❌ All use mutable version tags — see SUP-2 |
| Secrets passed via env (not direct echo) | ✅ `GOOGLE_SERVICES_JSON_B64: ${{ secrets.GOOGLE_SERVICES_JSON_B64 }}` in `env:` block |
| `pnpm install --frozen-lockfile` in CI | ⚠️ Not present in any CI workflow (Android-only CI; no backend CI step exists yet) |

---

## 7) Recommendations by Priority

### High Priority (Address Soon)

1. **SUP-1: Pin Docker base images by SHA digest.**
   - Action: Add `@sha256:...` digests (available from harness log artifacts).
   - Effort: 5 minutes.
   - Risk: Supply chain compromise of production image.

2. **SUP-2: Pin GitHub Actions by commit SHA.**
   - Action: Run `git ls-remote` for each action to get SHA for the current tag, replace tag with SHA + comment.
   - Effort: 20 minutes.
   - Risk: CI compromise exposing signing secrets and `google-services.json`.

### Medium Priority (Address Before First Production Deploy)

3. **SUP-5: Reconcile pnpm version** — update root `packageManager` to `pnpm@10.33.2` and regenerate lockfile.
4. **SUP-6: Add SHA256 checksum to yt-dlp binary download** — fetch the SHA from GitHub releases and bake it into the `ARG`.
5. **SUP-3: Add Gradle dependency verification** for JitPack-sourced `compose-markdown`, or document accepted risk.
6. **SUP-4: Add `--frozen-lockfile` to any future backend CI install steps** to prevent range drift.

### Low Priority (Backlog)

7. **SUP-7**: Remove `firebase-functions-test` if tests have fully migrated to vitest.
8. **SUP-8**: Add `pnpm audit` step to CI once a backend CI job exists.
9. **SUP-9**: Add `"engines": {"node": ">=24"}` to `summarizer/summarize-api/package.json`.

---

## 8) Positive Observations

- Subtree is pinned to a specific commit SHA (`0ec12ac`) documented in `SUBTREE_PIN.md` with a full refresh procedure and verification checklist. Excellent practice. ✅
- `summarize-api/package.json` uses `pnpm.onlyBuiltDependencies` to whitelist only `better-sqlite3` and `esbuild` for native build scripts, reducing arbitrary postinstall execution risk. ✅
- All npm packages in the lockfile have `resolution: {integrity: sha512-...}` hashes. ✅
- Firebase SDK moved to BOM-managed versions (no manual version drift between `firebase-auth`, `firebase-firestore`, `firebase-functions`). ✅
- Removed `google-api-services-youtube` and related Google API Client Android libs as planned — reduces attack surface. ✅
- Deleted `summarizer/deploy/daemon/Dockerfile` (which cloned from GitHub `--depth 1` at build time — a significant supply-chain anti-pattern) and replaced with a subtree-vendored approach. Major security improvement. ✅

---

*Review completed: 2026-05-21*

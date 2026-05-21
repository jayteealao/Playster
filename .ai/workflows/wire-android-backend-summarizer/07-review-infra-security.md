---
review-command: infra-security
slug: wire-android-backend-summarizer
date: "2026-05-21"
scope: diff
target: git diff main...HEAD
id-prefix: IS-
files-reviewed:
  - summarizer/deploy/Dockerfile
  - summarizer/deploy/entrypoint.js
  - summarizer/deploy/docker-compose.yml
  - summarizer/deploy/mock-backend/Dockerfile
  - summarizer/deploy/mock-backend/server.js
  - summarizer/deploy/CLOUD-RUN.md
  - summarizer/deploy/README.md
  - summarizer/deploy/run-harness.mjs
  - backend/firebase.json
  - backend/firestore.rules
refs:
  index: 00-index.md
  shape: 02-shape.md
  implement-summarizer: 05-implement-summarizer-container.md
---

# Infrastructure Security Review — wire-android-backend-summarizer

**Scope:** `git diff main...HEAD` (232 files changed, +16 714 / −1 212 lines)
**Date:** 2026-05-21
**Reviewer:** Claude Code (infra-security rubric)
**Infrastructure:** GCP / Cloud Run (gen2), Firebase Auth + Firestore, Docker multi-stage build

---

## 0) Infrastructure Stack

- **Cloud provider:** GCP
- **Orchestration:** Cloud Run gen2 (single-container, entrypoint.js + tini)
- **IaC artefacts reviewed:** Dockerfile (multi-stage), docker-compose.yml, firebase.json, firestore.rules, CLOUD-RUN.md, entrypoint.js, mock-backend/{Dockerfile,server.js}
- **Environment:** Production path (Cloud Run) + dev harness (docker-compose)
- **Secrets transport:** GCP Secret Manager → Cloud Run `--set-secrets` env injection

---

## 1) Executive Summary

**Security Posture: MOSTLY_SECURE**

The core threat surfaces are handled well: no secrets hard-coded in the image layers, the daemon token is passed as a CLI argument (not an `ENV` directive), tini is PID 1, Cloud Run is deployed with `--no-allow-unauthenticated`, webhook signature verification is timing-safe, and Firestore rules default-deny everything that is not explicitly allowed. The main issues are: (1) both production and mock-backend containers run as root (`node:24-slim` and `node:22-slim` have no `USER` directive), (2) no Docker `HEALTHCHECK` directive means liveness/readiness is only known at the Cloud Run layer, (3) the base images are not pinned to SHA256 digests, (4) the `SUMMARIZE_TOKEN` appears as a visible command-line argument in the daemon's process table (readable by any process in the container via `/proc/*/cmdline`), and (5) the Firebase emulator UI is enabled with no host-binding restriction in `firebase.json`.

---

## 2) Findings Table

| ID | Severity | Confidence | Category | Resource | Misconfiguration |
|----|----------|------------|----------|----------|-----------------|
| IS-1 | HIGH | High | Container | `summarizer/deploy/Dockerfile` (runtime stage) | No `USER` directive — container runs as root |
| IS-2 | HIGH | High | Container | `summarizer/deploy/mock-backend/Dockerfile` | No `USER` directive — mock-backend runs as root |
| IS-3 | HIGH | High | Container | `summarizer/deploy/Dockerfile` (all stages) | Base image `node:24-slim` / `node:22-slim` not pinned to SHA256 digest |
| IS-4 | MED | High | Secrets | `summarizer/deploy/entrypoint.js:46-47` | `SUMMARIZE_TOKEN` passed as a CLI `--token` argument; visible in `/proc/*/cmdline` to any process in the container |
| IS-5 | MED | Med | Container | `summarizer/deploy/Dockerfile` | No `HEALTHCHECK` directive; Cloud Run liveness depends solely on port-listen signal, not a semantic health probe |
| IS-6 | MED | High | Network | `backend/firebase.json` (emulator config) | Emulator UI (`port: 4000`) and services have no `host` binding; default in Firebase CLI binds emulators to `0.0.0.0` on developer machines |
| IS-7 | MED | Med | IAM | `summarizer/deploy/CLOUD-RUN.md` | Cloud Run runbook grants operator `roles/owner` as an acceptable prerequisite; least-privilege IAM roles are not specified |
| IS-8 | LOW | High | Secrets | `summarizer/deploy/docker-compose.yml` | `mock-backend` port 9000 mapped to host `0.0.0.0:9000`; the webhook receiver (with real `WEBHOOK_SECRET`) is accessible from outside Docker bridge when run on a dev machine with a public IP |
| IS-9 | LOW | High | Container | `summarizer/deploy/Dockerfile` (runtime stage) | `curl` and `python3` remain in the final runtime image; not needed for production operation |
| IS-10 | LOW | Med | Infra docs | `backend/firestore.rules` | `__BOOTSTRAP_UID__` sentinel left in deployed rules is a latent misconfiguration risk if rules are deployed before the sentinel is replaced |
| IS-11 | NIT | High | Secrets | `summarizer/deploy/entrypoint.js:38` | `logInfo("starting daemon", { daemonUrl: DAEMON_URL })` — `DAEMON_URL` is a constant loopback, not sensitive, but logging the startup line before token-absence check at line 32-35 means the log ordering is safe; no actual leak |

**Findings Summary:**
- BLOCKER: 0
- HIGH: 3
- MED: 4
- LOW: 3
- NIT: 1

---

## 3) Findings (Detailed)

### IS-1: Runtime container runs as root [HIGH]

**Location:** `summarizer/deploy/Dockerfile` — runtime stage (all lines after `FROM node:24-slim AS runtime`)

**Category:** Container Security

**Misconfigured resource:**
```dockerfile
FROM node:24-slim AS runtime
# ... no USER directive ...
ENTRYPOINT ["/usr/bin/tini", "--"]
CMD ["node", "/opt/entrypoint.js"]
```

**Security issue:**
`node:24-slim` defaults to running as `root` (UID 0). The runtime image has no `USER` directive so the Node entrypoint, tini, and the daemon child process all run as root inside the container.

**Attack scenario:**
If an RCE vulnerability exists in Fastify, the daemon, or a dependency, the attacker achieves root inside the container. From root they can:
- Read all environment variables including `SUMMARIZE_TOKEN`, `API_KEYS`, `OPENROUTER_API_KEY`
- Attempt container-escape exploits (e.g., mount cgroup, kernel exploits) at elevated privilege
- Modify the filesystem at `/opt/daemon` or `/opt/entrypoint.js`

**Blast radius:** High — secrets and container-escape potential from any RCE.

**Severity:** HIGH | **Confidence:** High

**Fix:**
```dockerfile
# After COPY steps and before ENTRYPOINT in the runtime stage:
RUN groupadd --gid 1001 appuser \
 && useradd --uid 1001 --gid 1001 --no-create-home appuser \
 && chown -R appuser:appuser /app /opt/daemon /opt/entrypoint.js

USER appuser
```
The `tini` binary in `/usr/bin/tini` is owned by root but setuid-safe to call as non-root via `ENTRYPOINT ["/usr/bin/tini", "--"]`. Cloud Run does not require root. The daemon inside the container also runs as whatever user the entrypoint spawns it as, so this propagates correctly.

---

### IS-2: Mock-backend container runs as root [HIGH]

**Location:** `summarizer/deploy/mock-backend/Dockerfile`

**Category:** Container Security

**Misconfigured resource:**
```dockerfile
FROM node:22-slim
WORKDIR /app
COPY server.js ./
EXPOSE 9000
CMD ["node", "server.js"]
```

**Security issue:**
No `USER` directive. The mock-backend is a dev/test-only service but it handles cryptographic secret verification (`timingSafeEqual`) and writes files to a mounted volume (`/verify-artifacts`). Running as root creates unnecessary privilege — if the server were accidentally exposed or had an RCE bug, the attacker would have root on the Docker host via the volume mount.

**Blast radius:** Medium — test-only context, but mounted volume write access as root is a host-path escalation risk.

**Severity:** HIGH | **Confidence:** High

**Fix:**
```dockerfile
FROM node:22-slim
WORKDIR /app
COPY server.js ./
RUN chown node:node /app
USER node
EXPOSE 9000
CMD ["node", "server.js"]
```

---

### IS-3: Base images not pinned to SHA256 digests [HIGH]

**Location:** `summarizer/deploy/Dockerfile:17,36,61`; `summarizer/deploy/mock-backend/Dockerfile:1`

**Category:** Container Security / Supply Chain

**Misconfigured resource:**
```dockerfile
FROM node:24-slim AS daemon-build   # line 17
FROM node:24-slim AS api-build      # line 36
FROM node:24-slim AS runtime        # line 61
FROM node:22-slim                   # mock-backend/Dockerfile:1
```

**Security issue:**
Using mutable tags (`node:24-slim`, `node:22-slim`) means a Docker Hub push of a malicious or vulnerable image under the same tag will silently be pulled on the next `docker build` or `gcloud builds submit`. The rubric lists unpinned images as a BLOCKER in k8s Deployment manifests but MED/HIGH for Dockerfiles where the concern is build-time reproducibility rather than runtime rollback.

**Blast radius:** High — a supply-chain compromise of the base image grants full control of the runtime environment.

**Severity:** HIGH | **Confidence:** High

**Fix:**
Pin all `FROM` lines to SHA256 digests:
```dockerfile
# node:24-slim as of May 2026 — verify via:
# docker pull node:24-slim && docker inspect node:24-slim --format '{{index .RepoDigests 0}}'
FROM node:24-slim@sha256:<digest> AS daemon-build
FROM node:24-slim@sha256:<digest> AS api-build
FROM node:24-slim@sha256:<digest> AS runtime
```
Use Renovate or Dependabot with `"matchManagers": ["dockerfile"]` to automate digest bumps.

---

### IS-4: `SUMMARIZE_TOKEN` visible in process table [MED]

**Location:** `summarizer/deploy/entrypoint.js:40-55`

**Category:** Secrets Management

**Misconfigured resource:**
```js
const daemon = spawn(
  process.execPath,
  [
    "/opt/daemon/dist/cli.js",
    "daemon",
    "run",
    "--token",
    token,       // ← token appears in /proc/<pid>/cmdline
    "--port",
    "8787",
  ],
  { stdio: ["ignore", "inherit", "inherit"], env: process.env },
);
```

**Security issue:**
`SUMMARIZE_TOKEN` is passed as a positional CLI argument to the daemon process. Any process in the container that can read `/proc/<daemon-pid>/cmdline` (including the gateway itself if compromised) can extract the token in cleartext. On Cloud Run gen2, the container namespace is isolated and there is no adjacent container, so the practical exposure is low but non-zero if the application itself is compromised.

Note: The shape doc acknowledges the daemon supports the `--token` flag at `cli.ts:610`. If the daemon supports a `SUMMARIZE_TOKEN` environment variable or a config-file path, prefer those channels.

**Blast radius:** Medium — token compromise allows an attacker to call the daemon's internal API directly, bypassing the gateway's `X-API-Key` check. Impact is limited to the container lifetime.

**Severity:** MED | **Confidence:** High

**Fix options (preferred → least preferred):**
1. If the daemon supports reading the token from an environment variable (many CLIs alias `--token` to `$DAEMON_TOKEN`), pass via env only:
   ```js
   const daemon = spawn(
     process.execPath,
     ["/opt/daemon/dist/cli.js", "daemon", "run", "--port", "8787"],
     { stdio: ["ignore","inherit","inherit"], env: { ...process.env, DAEMON_TOKEN: token } },
   );
   ```
2. If the daemon supports a config file, write the token to a temp file with mode `0600`, pass `--config /tmp/daemon-config.json`, and delete after spawn.
3. As a stop-gap, document the risk in `CLOUD-RUN.md` and note that Cloud Run instance isolation mitigates the most likely exploitation vector.

---

### IS-5: No `HEALTHCHECK` directive in production Dockerfile [MED]

**Location:** `summarizer/deploy/Dockerfile` — runtime stage

**Category:** Container Security / Reliability

**Issue:**
The Dockerfile has no `HEALTHCHECK` instruction. The Cloud Run platform uses the port-binding signal (`:8080` listening) as its readiness indicator unless a health probe is configured explicitly. The entrypoint boots the daemon first (up to 30 s) then starts Fastify, so between container start and Fastify bind there is a window where Cloud Run may route traffic to an instance that is not ready. While Cloud Run has its own startup-probe mechanism, a `HEALTHCHECK` in the image makes the intent explicit and enables `docker compose` harness health gates.

**Severity:** MED | **Confidence:** Med

**Fix:**
```dockerfile
# In the runtime stage, before ENTRYPOINT:
HEALTHCHECK --interval=10s --timeout=5s --start-period=45s --retries=3 \
  CMD curl -f http://localhost:8080/health || exit 1
```
Use `--start-period=45s` to allow daemon boot + `/v1/refresh-free` before the first check.

---

### IS-6: Firebase emulator UI and services bind to 0.0.0.0 by default [MED]

**Location:** `backend/firebase.json:22-28`

**Category:** Network Security

**Misconfigured resource:**
```json
"emulators": {
  "auth": { "port": 9099 },
  "firestore": { "port": 8080 },
  "functions": { "port": 5001 },
  "ui": { "enabled": true, "port": 4000 },
  "singleProjectMode": true
}
```

**Security issue:**
The Firebase Emulator Suite CLI defaults to binding `0.0.0.0` (all interfaces) when no `host` key is specified. On a developer machine with a reachable IP, the emulator UI (port 4000) and all service ports (9099, 8080, 5001) are accessible to anyone on the same network. This exposes unauthenticated access to the Firestore emulator (which holds test data), the auth emulator (which can create arbitrary tokens), and the functions emulator (which can invoke all cloud functions without IAM).

**Attack scenario:**
Developer on a shared network (office Wi-Fi, co-working space, conference hotel) runs `firebase emulators:start`. An attacker on the same network accesses `http://<dev-ip>:9099` to mint Firebase Auth tokens with arbitrary UIDs, then uses those tokens to call functions on port 5001 including `requestVideoSummary`, `summaryWebhook`, and cron triggers.

**Blast radius:** Medium — test infrastructure only, but can exhaust external API keys or exfiltrate developer credentials stored in the emulator.

**Severity:** MED | **Confidence:** High

**Fix:**
```json
"emulators": {
  "auth":      { "port": 9099, "host": "127.0.0.1" },
  "firestore": { "port": 8080, "host": "127.0.0.1" },
  "functions": { "port": 5001, "host": "127.0.0.1" },
  "ui": { "enabled": true, "port": 4000, "host": "127.0.0.1" },
  "singleProjectMode": true
}
```

---

### IS-7: Cloud Run runbook suggests `roles/owner` as an acceptable IAM prerequisite [MED]

**Location:** `summarizer/deploy/CLOUD-RUN.md:12-14`

**Category:** IAM & Access Control

**Misconfigured resource:**
```markdown
- GCP project provisioned. Operator has `roles/run.admin`,
  `roles/artifactregistry.admin`, and `roles/secretmanager.admin` (or
  `roles/owner`).
```

**Security issue:**
The parenthetical `(or roles/owner)` effectively documents `roles/owner` as an acceptable alternative for all three specific roles. `roles/owner` grants full project-level admin including billing, IAM management, and deletion of all resources. The rubric flags `roles/owner` in production as HIGH because documenting it as an acceptable shortcut leads operators to use it.

**Blast radius:** High — if the operator's account is compromised, `roles/owner` allows full project destruction, IAM privilege escalation, and billing fraud.

**Severity:** MED | **Confidence:** Med (documentation only, no enforcement)

**Fix:**
Remove the `(or roles/owner)` shortcut. The minimum required roles are already documented: `roles/run.admin`, `roles/artifactregistry.admin`, `roles/secretmanager.admin`. Optionally add `roles/cloudbuild.builds.editor` for Cloud Build.

---

### IS-8: `mock-backend` port 9000 exposed on all host interfaces [LOW]

**Location:** `summarizer/deploy/docker-compose.yml:25-26`

**Category:** Network Security

**Misconfigured resource:**
```yaml
mock-backend:
  ports:
    - "9000:9000"   # binds to 0.0.0.0:9000 on the host
```

**Security issue:**
The `"9000:9000"` short form binds to `0.0.0.0` on the host. The mock-backend holds the `WEBHOOK_SECRET` and writes files to a mounted volume. On a developer machine that is reachable from the LAN, anyone can POST to `http://<host>:9000/webhook` with a forged or replayed signature.

**Severity:** LOW | **Confidence:** High

**Fix:**
```yaml
ports:
  - "127.0.0.1:9000:9000"
```

Similarly for the summarizer:
```yaml
ports:
  - "127.0.0.1:18080:8080"
```

---

### IS-9: `curl` and `python3` left in production runtime image [LOW]

**Location:** `summarizer/deploy/Dockerfile:65-72` (runtime stage apt layer)

**Category:** Container Security / Image Hygiene

**Misconfigured resource:**
```dockerfile
RUN apt-get install -y --no-install-recommends \
    ffmpeg \
    tesseract-ocr \
    ca-certificates \
    tini \
    curl \
    python3 \
```

**Security issue:**
`curl` is installed to download the `yt-dlp` binary (used only at build time within the same `RUN` block) but left in the final runtime image. `python3` is similarly a build-time dependency for native modules in the build stages. Both tools remain in the final runtime image, expanding the attack surface if the container is compromised (curl can be used for data exfiltration; python3 can run arbitrary scripts).

`curl` is used at build time via: `RUN curl -fsSL ... -o /usr/local/bin/yt-dlp`. Once the binary is downloaded, `curl` serves no runtime purpose.

**Blast radius:** Low — post-exploitation tool availability only; does not grant initial access.

**Severity:** LOW | **Confidence:** High

**Fix:**
Move the `yt-dlp` download into a separate build stage or remove `curl` from the final install layer by moving the download earlier and then removing curl:
```dockerfile
# Option A: remove curl after download (within same RUN block)
RUN apt-get install -y --no-install-recommends \
        ffmpeg tesseract-ocr ca-certificates tini curl python3 \
 && curl -fsSL "..." -o /usr/local/bin/yt-dlp \
 && chmod a+rx /usr/local/bin/yt-dlp \
 && yt-dlp --version | tee /etc/yt-dlp.version \
 && apt-get remove -y curl \
 && apt-get autoremove -y \
 && rm -rf /var/lib/apt/lists/*
```
Or use a separate downloader stage (`FROM curlimages/curl AS dl`) to keep the runtime layer clean.

`python3` can likely be removed entirely from the runtime stage since yt-dlp is shipped as a static binary and no other runtime component requires Python. Verify with `python3 -c "import sys; print(sys.version)"` is never needed at runtime.

---

### IS-10: `__BOOTSTRAP_UID__` sentinel in firestore.rules creates latent misconfiguration risk [LOW]

**Location:** `backend/firestore.rules:6`

**Category:** Infra Docs / Deploy Safety

**Misconfigured resource:**
```js
function isAllowlisted() {
  return request.auth != null && request.auth.uid == "__BOOTSTRAP_UID__";
}
```

**Security issue:**
The sentinel string `__BOOTSTRAP_UID__` will never match any real Firebase Auth UID, so if these rules are deployed before the sentinel is replaced, **all client-side reads from the Android app will be silently denied** — not a security breach, but an operational failure that could be mistaken for an auth bug rather than a rules misconfiguration. The complement risk: a future developer might accidentally deploy these rules after a merge that resets the value.

The shape doc documents the two-pass deploy procedure; the concern here is that there is no CI guard to prevent deploying unreplaced sentinel rules.

**Blast radius:** Low — results in operational denial-of-service to the allowlisted operator, not data exposure.

**Severity:** LOW | **Confidence:** High

**Fix:**
Add a `predeploy` script in `backend/firebase.json` that validates the sentinel is replaced before `firebase deploy`:
```json
"predeploy": [
  "grep -q '__BOOTSTRAP_UID__' firestore.rules && echo 'ERROR: Replace __BOOTSTRAP_UID__ before deploying' && exit 1 || true",
  "pnpm --prefix \"$RESOURCE_DIR\" run lint",
  "pnpm --prefix \"$RESOURCE_DIR\" run build"
]
```

---

### IS-11: Token log-ordering is correct [NIT]

**Location:** `summarizer/deploy/entrypoint.js:32-38`

**Category:** Secrets Management (no action needed)

**Observation:**
The token presence check (`if (!token) { ... process.exit(1); }`) occurs at line 32-35, before the `logInfo("starting daemon", ...)` call at line 38. No token value is logged anywhere. `OPENROUTER_API_KEY` is not logged when used in the `Authorization: Bearer` header. The `redactJob` helper in `src/db/jobs.ts` strips `webhook_secret` from log lines. **No finding — log hygiene is correct.**

**Severity:** NIT | **Confidence:** High

---

## 4) Attack Surface Analysis

| Asset | Exposure | Access Control | Risk |
|-------|----------|---------------|------|
| Cloud Run summarizer endpoint | Public URL; IAM `--no-allow-unauthenticated` + `X-API-Key` | IAM invoker + API key (accepted per PO) | LOW — double layer |
| Daemon (port 8787) | Loopback only (`127.0.0.1:8787`) | Bearer token required | LOW |
| Firestore (client reads) | Via Firebase SDK | Allowlisted UID only (hardcoded) | LOW |
| Firestore (writes) | Admin SDK only | `allow write: if false` on client rules | LOW |
| Firebase emulator | Developer machine | **No host binding → 0.0.0.0** (IS-6) | MED |
| Container runtime | Root UID (IS-1, IS-2) | Container namespace isolation | HIGH |
| `SUMMARIZE_TOKEN` | `/proc/<pid>/cmdline` inside container (IS-4) | Process namespace | MED |
| Mock-backend (port 9000) | Host `0.0.0.0:9000` (IS-8) | `WEBHOOK_SECRET` only | LOW |

---

## 5) Compliance Assessment

This is a single-tenant personal-use project. No HIPAA/PCI DSS/SOC 2 compliance is required per the spec. Findings are assessed for security hygiene, not formal compliance.

---

## 6) Recommendations

### High Priority (Fix Before Production Deploy)

1. **IS-1** — Add `USER appuser` to runtime Dockerfile stage. Effort: 5 min.
2. **IS-2** — Add `USER node` to mock-backend Dockerfile. Effort: 2 min.
3. **IS-3** — Pin `FROM node:24-slim` and `FROM node:22-slim` to SHA256 digests. Effort: 10 min. Set up Renovate for automated bumps.

### Medium Priority (Address Before Handoff)

4. **IS-4** — Investigate if the daemon accepts the token via env var instead of `--token` CLI arg. If yes, switch to env injection; if no, document the risk in `CLOUD-RUN.md`. Effort: 15 min investigation + 5 min fix or doc.
5. **IS-5** — Add `HEALTHCHECK` to Dockerfile runtime stage with `--start-period=45s`. Effort: 5 min.
6. **IS-6** — Add `"host": "127.0.0.1"` to all emulator entries in `backend/firebase.json`. Effort: 5 min.
7. **IS-7** — Remove `(or roles/owner)` from CLOUD-RUN.md prerequisites. Effort: 2 min.

### Low Priority (Backlog)

8. **IS-8** — Bind docker-compose ports to `127.0.0.1` explicitly. Effort: 2 min.
9. **IS-9** — Remove `curl` (and possibly `python3`) from final runtime image. Effort: 10 min.
10. **IS-10** — Add a `predeploy` sentinel-check guard to `backend/firebase.json`. Effort: 5 min.

---

## 7) Cloud Run Specific Notes

- **`--no-allow-unauthenticated` is correctly specified** in the runbook. The API-key-only model for the summarizer was explicitly accepted by the PO as a known trade-off (Cloud Run IAM + API key = double layer).
- **Timing-safe token comparison** in the upstream daemon (`0ec12acc`) was a prerequisite of this slice; the subtree is pinned to that commit. No finding here.
- **`WEBHOOK_SECRET` is read from environment** (Secret Manager via `--set-secrets`) — no plaintext in the image layer. No finding.
- **No secrets in `ENV` build-time directives** — all secrets enter via Cloud Run `--set-secrets`. No finding.
- **`tini` as PID 1** — correct. Zombie reaping and signal forwarding are properly handled.

---

*Review completed: 2026-05-21*

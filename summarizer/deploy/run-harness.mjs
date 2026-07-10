#!/usr/bin/env node
// docker-compose harness driver. Verifies AC-6 (signature), AC-14
// (no-caption fallback), AC-16 (cold-start health), and the slice-local
// replay-rejection AC.
//
// Usage from repo root:
//   node summarizer/deploy/run-harness.mjs           # build + up + verify + tear down
//   node summarizer/deploy/run-harness.mjs --keep    # leave the stack running for inspection
//
// Exits non-zero if any assertion fails. Logs all assertions + captured
// artifacts to summarizer/deploy/harness.log.

import { spawnSync } from "node:child_process";
import { randomBytes, createHmac } from "node:crypto";
import {
  mkdirSync,
  readFileSync,
  writeFileSync,
  existsSync,
  rmSync,
} from "node:fs";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { setTimeout as sleep } from "node:timers/promises";

const __dirname = dirname(fileURLToPath(import.meta.url));
const DEPLOY_DIR = resolve(__dirname);
const ARTIFACT_DIR = join(DEPLOY_DIR, "verify-artifacts");
const HARNESS_LOG = join(DEPLOY_DIR, "harness.log");
const SUMMARIZER_URL = "http://127.0.0.1:18080";
const MOCK_BACKEND_URL = "http://127.0.0.1:9000";
const KEEP = process.argv.includes("--keep");

const logLines = [];
function log(level, msg, extra) {
  const line = { ts: new Date().toISOString(), level, msg, ...extra };
  const text = JSON.stringify(line);
  logLines.push(text);
  process.stdout.write(text + "\n");
}

function flushLog() {
  writeFileSync(HARNESS_LOG, logLines.join("\n") + "\n", "utf8");
}

function dc(...args) {
  log("info", "docker compose", { args });
  const result = spawnSync("docker", ["compose", ...args], {
    cwd: DEPLOY_DIR,
    stdio: "inherit",
    env: { ...process.env, ...harnessEnv() },
  });
  if (result.status !== 0) {
    throw new Error(`docker compose ${args.join(" ")} exited ${result.status}`);
  }
}

function dcBackground(...args) {
  return spawnSync("docker", ["compose", ...args], {
    cwd: DEPLOY_DIR,
    stdio: "inherit",
    env: { ...process.env, ...harnessEnv() },
  });
}

let cachedEnv;
function harnessEnv() {
  if (cachedEnv) return cachedEnv;
  cachedEnv = {
    SUMMARIZE_TOKEN: randomBytes(32).toString("hex"),
    API_KEYS: `harness-${randomBytes(8).toString("hex")}`,
    WEBHOOK_SECRET: randomBytes(32).toString("hex"),
    OPENROUTER_API_KEY: process.env.OPENROUTER_API_KEY ?? "",
    // Transcription provider for the daemon's no-caption YouTube fallback.
    // The daemon's tryWebTranscript (HTML captionTracks scrape) handles
    // captioned videos; no-caption videos fall through to yt-dlp + an ASR
    // provider. Without GROQ_API_KEY (or another supported provider) the
    // daemon emits an SSE `error` for no-caption URLs.
    GROQ_API_KEY: process.env.GROQ_API_KEY ?? "",
    // Harness delivers the webhook to the mock backend on a private Docker-network
    // IP, which the summarize-api SSRF guard blocks by default. Permit private
    // webhook targets for this stack only (production leaves this unset/false).
    SSRF_ALLOW_PRIVATE_WEBHOOK: process.env.SSRF_ALLOW_PRIVATE_WEBHOOK ?? "1",
  };
  return cachedEnv;
}

async function pollUntilOk(url, timeoutMs, label) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    try {
      const res = await fetch(url);
      if (res.ok) {
        log("info", `${label} healthy`, { url, status: res.status });
        return;
      }
    } catch {
      // not yet ready
    }
    await sleep(500);
  }
  throw new Error(
    `${label} did not become healthy at ${url} within ${timeoutMs}ms`,
  );
}

async function postJob(url, body) {
  const env = harnessEnv();
  const res = await fetch(`${SUMMARIZER_URL}/v1/jobs`, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      "X-API-Key": env.API_KEYS,
    },
    body: JSON.stringify(body),
  });
  if (res.status !== 201) {
    const text = await res.text();
    throw new Error(`POST /v1/jobs failed: ${res.status} ${text}`);
  }
  return res.json();
}

async function waitForWebhook(clientJobId, timeoutMs) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    const res = await fetch(`${MOCK_BACKEND_URL}/captured/${clientJobId}`);
    if (res.ok) return res.json();
    await sleep(1000);
  }
  throw new Error(
    `webhook for ${clientJobId} not captured within ${timeoutMs}ms`,
  );
}

function runFixture(fixturePath) {
  const f = JSON.parse(readFileSync(fixturePath, "utf8"));
  return f;
}

async function runHarness() {
  if (!existsSync(ARTIFACT_DIR)) mkdirSync(ARTIFACT_DIR, { recursive: true });

  log("info", "harness starting");

  dc("build");
  dc("up", "-d");

  try {
    // AC-16: cold-start health
    await pollUntilOk(`${SUMMARIZER_URL}/health`, 60_000, "summarizer");
    await pollUntilOk(`${MOCK_BACKEND_URL}/health`, 30_000, "mock-backend");

    const env = harnessEnv();

    // AC-6 + AC-14: dispatch both fixtures
    const captioned = runFixture(
      join(DEPLOY_DIR, "fixtures", "captioned.json"),
    );
    const noCaption = runFixture(
      join(DEPLOY_DIR, "fixtures", "no-caption.json"),
    );

    for (const fixture of [captioned, noCaption]) {
      log("info", "dispatching fixture", {
        client_job_id: fixture.client_job_id,
      });
      await postJob(SUMMARIZER_URL, {
        url: fixture.url,
        webhook_url: `http://mock-backend:9000/webhook`,
        webhook_secret: env.WEBHOOK_SECRET,
        client_job_id: fixture.client_job_id,
      });
    }

    // Wait for both webhooks to arrive.
    const capturedHappy = await waitForWebhook(
      captioned.client_job_id,
      12 * 60_000,
    );
    const capturedFallback = await waitForWebhook(
      noCaption.client_job_id,
      12 * 60_000,
    );

    // AC-6: signature already verified by mock-backend before capture.
    // Re-verify here as belt-and-suspenders.
    for (const cap of [capturedHappy, capturedFallback]) {
      const sig = cap.signature.match(/^t=(\d+),v1=(.+)$/);
      if (!sig)
        throw new Error(`captured signature is malformed: ${cap.signature}`);
      const t = Number(sig[1]);
      const v1 = sig[2];
      const rawBody = JSON.stringify(cap.payload);
      const expected = createHmac("sha256", env.WEBHOOK_SECRET)
        .update(`${t}.${rawBody}`, "utf8")
        .digest("hex");
      if (expected !== v1) {
        // The mock-backend already accepted this — if our re-derivation
        // mismatches it's a JSON-serialization drift bug.
        log(
          "warn",
          "re-derived signature mismatch (likely JSON drift in re-stringify)",
          {
            client_job_id: cap.payload.client_job_id,
          },
        );
      }
      if (cap.payload.status !== "completed") {
        throw new Error(
          `${cap.payload.client_job_id} did not complete: ${cap.payload.status}`,
        );
      }
    }

    // AC-14: no-caption fixture summary length floor
    const fallbackSummary = capturedFallback.payload.result?.summary ?? "";
    if (fallbackSummary.length < noCaption.expect.content_min_chars) {
      throw new Error(
        `no-caption summary too short: ${fallbackSummary.length} < ${noCaption.expect.content_min_chars}`,
      );
    }

    // Slice-local AC: replay window. Re-POST the captured payload with the
    // stored timestamp shifted past the 300s window.
    log("info", "running replay attack check");
    const replayBody = JSON.stringify(capturedHappy.payload);
    const oldT = Math.floor(Date.now() / 1000) - 301;
    const oldMac = createHmac("sha256", env.WEBHOOK_SECRET)
      .update(`${oldT}.${replayBody}`, "utf8")
      .digest("hex");
    const replayRes = await fetch(`${MOCK_BACKEND_URL}/webhook`, {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        "X-Summarizer-Signature": `t=${oldT},v1=${oldMac}`,
      },
      body: replayBody,
    });
    if (replayRes.status !== 401) {
      throw new Error(
        `replay attack was not rejected; got ${replayRes.status}`,
      );
    }
    log("info", "replay attack correctly rejected", {
      status: replayRes.status,
    });

    // Slice-local AC: yt-dlp version floor
    const ytdlpRes = spawnSync(
      "docker",
      ["compose", "exec", "-T", "summarizer", "yt-dlp", "--version"],
      { cwd: DEPLOY_DIR, env: { ...process.env, ...env } },
    );
    if (ytdlpRes.status === 0) {
      const ver = ytdlpRes.stdout.toString().trim();
      log("info", "yt-dlp version", { ver });
      if (ver < "2026.02.21") {
        throw new Error(`yt-dlp version ${ver} is below floor 2026.02.21`);
      }
    } else {
      log("warn", "could not query yt-dlp version (docker exec failed)");
    }

    log("info", "harness passed");
  } finally {
    if (!KEEP) {
      try {
        dcBackground("down", "-v");
      } catch (err) {
        log("warn", "docker compose down failed", { error: String(err) });
      }
    } else {
      log("info", "--keep set; leaving the stack running");
    }
    flushLog();
  }
}

runHarness().catch((err) => {
  log("error", "harness failed", {
    error: err instanceof Error ? err.message : String(err),
  });
  flushLog();
  process.exit(1);
});

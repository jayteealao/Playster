// Cloud Run entrypoint for the unified summarizer container.
//
// Boots the vendored summarize-daemon as a child process, waits for /health,
// optionally calls /v1/refresh-free, then imports the summarize-api gateway
// in-process so Cloud Run's $PORT injection reaches Fastify directly.
//
// Signal forwarding: SIGTERM / SIGINT are forwarded to the daemon child.
// Tini (PID 1) reaps zombies and re-forwards Cloud Run's SIGTERM to this
// process.

import { spawn } from "node:child_process";
import { setTimeout as sleep } from "node:timers/promises";

const DAEMON_URL = "http://127.0.0.1:8787";
const DAEMON_HEALTH_TIMEOUT_MS = 30_000;
const DAEMON_HEALTH_POLL_MS = 200;
const SHUTDOWN_GRACE_MS = 10_000;

function logInfo(msg, extra) {
  // Single-line JSON for Cloud Run log explorer.
  const payload = { level: "info", component: "entrypoint", msg };
  if (extra) Object.assign(payload, extra);
  process.stdout.write(`${JSON.stringify(payload)}\n`);
}

function logError(msg, extra) {
  const payload = { level: "error", component: "entrypoint", msg };
  if (extra) Object.assign(payload, extra);
  process.stderr.write(`${JSON.stringify(payload)}\n`);
}

const token = process.env.SUMMARIZE_TOKEN;
if (!token) {
  logError("SUMMARIZE_TOKEN is required but missing");
  process.exit(1);
}

logInfo("starting daemon", { daemonUrl: DAEMON_URL });

const daemon = spawn(
  process.execPath,
  [
    "/opt/daemon/dist/cli.js",
    "daemon",
    "run",
    "--token",
    token,
    "--port",
    "8787",
  ],
  {
    stdio: ["ignore", "inherit", "inherit"],
    env: process.env,
  },
);

daemon.on("exit", (code, signal) => {
  logError("daemon exited", { code, signal });
  // If the daemon dies, the container is unhealthy. Exit so Cloud Run
  // recycles the instance rather than serving traffic without a daemon.
  process.exit(code ?? 1);
});

async function waitForDaemonHealth() {
  const deadline = Date.now() + DAEMON_HEALTH_TIMEOUT_MS;
  let lastErr;
  while (Date.now() < deadline) {
    try {
      const res = await fetch(`${DAEMON_URL}/health`);
      if (res.ok) return;
      lastErr = `status=${res.status}`;
    } catch (err) {
      lastErr = err instanceof Error ? err.message : String(err);
    }
    await sleep(DAEMON_HEALTH_POLL_MS);
  }
  throw new Error(`daemon /health did not return 200 within ${DAEMON_HEALTH_TIMEOUT_MS}ms (last: ${lastErr})`);
}

async function refreshFree() {
  if (!process.env.OPENROUTER_API_KEY) {
    logInfo("OPENROUTER_API_KEY not set, skipping /v1/refresh-free");
    return;
  }
  try {
    const res = await fetch(`${DAEMON_URL}/v1/refresh-free`, {
      method: "POST",
      headers: { Authorization: `Bearer ${token}` },
    });
    if (!res.ok) {
      logError("/v1/refresh-free returned non-2xx — continuing with cached free models", { status: res.status });
      return;
    }
    logInfo("/v1/refresh-free dispatched");
  } catch (err) {
    logError("/v1/refresh-free failed — continuing with cached free models", {
      error: err instanceof Error ? err.message : String(err),
    });
  }
}

function installSignalHandlers() {
  let shuttingDown = false;
  const forward = (signal) => {
    if (shuttingDown) return;
    shuttingDown = true;
    logInfo("received signal, forwarding to daemon", { signal });
    daemon.kill(signal);
    setTimeout(() => {
      logError("daemon did not exit within grace period; force-killing", { graceMs: SHUTDOWN_GRACE_MS });
      daemon.kill("SIGKILL");
      process.exit(1);
    }, SHUTDOWN_GRACE_MS).unref();
  };
  process.on("SIGTERM", () => forward("SIGTERM"));
  process.on("SIGINT", () => forward("SIGINT"));
}

installSignalHandlers();

try {
  await waitForDaemonHealth();
  logInfo("daemon /health is 200");

  await refreshFree();

  logInfo("starting summarize-api gateway in-process");
  await import("/app/summarizer/summarize-api/dist/index.js");
} catch (err) {
  logError("startup failed", { error: err instanceof Error ? err.message : String(err) });
  daemon.kill("SIGTERM");
  process.exit(1);
}

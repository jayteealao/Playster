// Tiny webhook receiver used by the docker-compose harness.
//
// Verifies the Stripe-style X-Summarizer-Signature against the shared
// WEBHOOK_SECRET, rejects timestamps older than 300s (replay window), and
// persists valid payloads to /verify-artifacts/<client_job_id>.json.
//
// Endpoints:
//   POST /webhook                 — receive + verify a webhook
//   GET  /captured/:client_job_id — read back the persisted artifact

import { createServer } from "node:http";
import { createHmac, timingSafeEqual } from "node:crypto";
import { mkdirSync, writeFileSync, readFileSync, existsSync } from "node:fs";
import { join, resolve } from "node:path";

const PORT = Number(process.env.PORT ?? 9000);
const ARTIFACT_DIR = resolve(process.env.ARTIFACT_DIR ?? "/verify-artifacts");
const SECRET = process.env.WEBHOOK_SECRET;
const REPLAY_WINDOW_S = Number(process.env.REPLAY_WINDOW_S ?? 300);

if (!SECRET) {
  process.stderr.write(
    JSON.stringify({ level: "error", msg: "WEBHOOK_SECRET is required" }) + "\n",
  );
  process.exit(1);
}

mkdirSync(ARTIFACT_DIR, { recursive: true });

function log(level, msg, extra) {
  const line = { level, component: "mock-backend", msg, ...extra };
  process.stdout.write(JSON.stringify(line) + "\n");
}

function parseSignature(header) {
  if (!header) return null;
  const parts = header.split(",").map((p) => p.trim());
  const out = {};
  for (const part of parts) {
    const eq = part.indexOf("=");
    if (eq === -1) continue;
    out[part.slice(0, eq)] = part.slice(eq + 1);
  }
  if (!out.t || !out.v1) return null;
  return { t: Number(out.t), v1: out.v1 };
}

function verify(rawBody, sig) {
  const canonical = `${sig.t}.${rawBody}`;
  const expectedHex = createHmac("sha256", SECRET).update(canonical, "utf8").digest("hex");
  // Decode both as hex so timingSafeEqual compares the raw HMAC bytes (32 bytes
  // each), not the variable-length hex string representations.  This matches
  // the pattern used in backend/functions/src/summarizer/webhook.ts.
  try {
    const received = Buffer.from(sig.v1, "hex");
    const expected = Buffer.from(expectedHex, "hex");
    if (received.length !== expected.length) return false;
    return timingSafeEqual(received, expected);
  } catch {
    return false;
  }
}

function safeClientJobId(raw) {
  // Defensive: prevent path traversal. Only alnum, dash, underscore.
  if (typeof raw !== "string") return null;
  if (!/^[A-Za-z0-9_\-]{1,256}$/.test(raw)) return null;
  return raw;
}

const server = createServer((req, res) => {
  if (req.method === "POST" && req.url === "/webhook") {
    let body = "";
    req.on("data", (chunk) => {
      body += chunk.toString("utf8");
    });
    req.on("end", () => {
      const sig = parseSignature(req.headers["x-summarizer-signature"]);
      if (!sig) {
        log("warn", "missing or malformed signature");
        res.writeHead(400, { "Content-Type": "application/json" });
        res.end(JSON.stringify({ error: "missing X-Summarizer-Signature" }));
        return;
      }

      const now = Math.floor(Date.now() / 1000);
      if (Math.abs(now - sig.t) > REPLAY_WINDOW_S) {
        log("warn", "replay-window violation", { age: now - sig.t });
        res.writeHead(401, { "Content-Type": "application/json" });
        res.end(JSON.stringify({ error: "replay window exceeded" }));
        return;
      }

      if (!verify(body, sig)) {
        log("warn", "signature verification failed");
        res.writeHead(401, { "Content-Type": "application/json" });
        res.end(JSON.stringify({ error: "signature mismatch" }));
        return;
      }

      let parsed;
      try {
        parsed = JSON.parse(body);
      } catch {
        res.writeHead(400, { "Content-Type": "application/json" });
        res.end(JSON.stringify({ error: "body is not JSON" }));
        return;
      }

      const cjid = safeClientJobId(parsed.client_job_id);
      if (!cjid) {
        res.writeHead(400, { "Content-Type": "application/json" });
        res.end(JSON.stringify({ error: "invalid client_job_id" }));
        return;
      }

      const artifactPath = join(ARTIFACT_DIR, `${cjid}.json`);
      writeFileSync(
        artifactPath,
        JSON.stringify(
          { received_at: new Date().toISOString(), signature: req.headers["x-summarizer-signature"], payload: parsed },
          null,
          2,
        ),
        "utf8",
      );
      log("info", "captured webhook", { client_job_id: cjid, status: parsed.status });

      res.writeHead(204);
      res.end();
    });
    return;
  }

  const capturedMatch = req.url && req.url.match(/^\/captured\/([^/]+)$/);
  if (req.method === "GET" && capturedMatch) {
    const cjid = safeClientJobId(decodeURIComponent(capturedMatch[1]));
    if (!cjid) {
      res.writeHead(400, { "Content-Type": "application/json" });
      res.end(JSON.stringify({ error: "invalid client_job_id" }));
      return;
    }
    const artifactPath = join(ARTIFACT_DIR, `${cjid}.json`);
    if (!existsSync(artifactPath)) {
      res.writeHead(404, { "Content-Type": "application/json" });
      res.end(JSON.stringify({ error: "not yet captured" }));
      return;
    }
    res.writeHead(200, { "Content-Type": "application/json" });
    res.end(readFileSync(artifactPath, "utf8"));
    return;
  }

  if (req.method === "GET" && req.url === "/health") {
    res.writeHead(200, { "Content-Type": "application/json" });
    res.end(JSON.stringify({ status: "ok" }));
    return;
  }

  res.writeHead(404);
  res.end();
});

server.listen(PORT, () => {
  log("info", "mock-backend listening", { port: PORT, artifactDir: ARTIFACT_DIR });
});

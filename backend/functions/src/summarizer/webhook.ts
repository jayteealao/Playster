import { createHmac, timingSafeEqual } from "node:crypto";
import * as admin from "firebase-admin";
import { onRequest } from "firebase-functions/v2/https";
import * as logger from "firebase-functions/logger";
import type { SummaryDocument } from "../models/index.js";

interface ParsedSignature {
  t: number;
  v1: string;
}

const REPLAY_WINDOW_SECONDS = 300;

const TERMINAL_PERMANENT_CODES: ReadonlySet<string> = new Set([
  "quota_exhausted",
  "unrecoverable",
  "transcript_impossible",
]);

interface WebhookBody {
  client_job_id?: unknown;
  status?: unknown;
  result?: {
    summary?: unknown;
    title?: unknown;
    model?: unknown;
  };
  error?: {
    code?: unknown;
    message?: unknown;
  };
}

export interface WebhookHandlerInput {
  signatureHeader: string | undefined;
  rawBody: Buffer;
  nowSeconds?: number;
}

export interface WebhookHandlerResult {
  status: number;
  body: string;
}

function parseSignatureHeader(value: string | undefined): ParsedSignature | null {
  if (!value) return null;
  let t: number | null = null;
  let v1: string | null = null;
  for (const part of value.split(",")) {
    const [k, raw] = part.trim().split("=");
    if (k === "t" && raw) {
      const parsed = Number(raw);
      if (!Number.isFinite(parsed)) return null;
      t = parsed;
    } else if (k === "v1" && raw) {
      if (!/^[0-9a-f]+$/i.test(raw)) return null;
      v1 = raw.toLowerCase();
    }
  }
  if (t === null || v1 === null) return null;
  return { t, v1 };
}

function verifySignature(
  v1: string,
  rawBody: string,
  timestamp: number,
  secret: string,
): boolean {
  if (!secret) return false;
  const expected = createHmac("sha256", secret)
    .update(`${timestamp}.${rawBody}`, "utf8")
    .digest("hex");
  if (expected.length !== v1.length) return false;
  try {
    return timingSafeEqual(Buffer.from(v1, "hex"), Buffer.from(expected, "hex"));
  } catch {
    return false;
  }
}

function classifyFailure(errorCode: string | undefined): SummaryDocument["status"] {
  if (errorCode && TERMINAL_PERMANENT_CODES.has(errorCode)) {
    return "failed-permanent";
  }
  return "failed-transient";
}

/**
 * Pure handler logic for the summary webhook. Exposed so tests can drive it
 * without spinning up an HTTPS function. Returns `{status, body}` instead of
 * writing to res.
 */
export async function processSummaryWebhook(
  input: WebhookHandlerInput,
): Promise<WebhookHandlerResult> {
  const sig = parseSignatureHeader(input.signatureHeader);
  if (!sig) {
    logger.warn("summaryWebhook: missing or malformed signature header");
    return { status: 400, body: "bad-signature-header" };
  }

  const now = input.nowSeconds ?? Math.floor(Date.now() / 1000);
  if (Math.abs(now - sig.t) > REPLAY_WINDOW_SECONDS) {
    logger.warn("summaryWebhook: replay window exceeded", {
      skew: now - sig.t,
    });
    return { status: 401, body: "stale-timestamp" };
  }

  if (!input.rawBody) {
    logger.error("summaryWebhook: rawBody unavailable on request");
    return { status: 500, body: "raw-body-missing" };
  }
  const raw = input.rawBody.toString("utf8");

  let parsed: WebhookBody;
  try {
    parsed = JSON.parse(raw) as WebhookBody;
  } catch {
    return { status: 400, body: "bad-json" };
  }

  const clientJobId =
    typeof parsed.client_job_id === "string" ? parsed.client_job_id : "";
  if (!clientJobId) {
    return { status: 400, body: "missing-client-job-id" };
  }

  const db = admin.firestore();
  const summaryRef = db.doc(`summaries/${clientJobId}`);
  const summarySnap = await summaryRef.get();
  if (!summarySnap.exists) {
    logger.warn("summaryWebhook: unknown client_job_id", { clientJobId });
    return { status: 404, body: "unknown-job" };
  }

  const summary = summarySnap.data() as Partial<SummaryDocument> | undefined;
  const secret = summary?.webhookSecret ?? "";
  if (!verifySignature(sig.v1, raw, sig.t, secret)) {
    logger.warn("summaryWebhook: signature verification failed", {
      clientJobId,
    });
    return { status: 401, body: "bad-signature" };
  }

  const incomingStatus = typeof parsed.status === "string" ? parsed.status : "";
  const inboundTerminal: SummaryDocument["status"] =
    incomingStatus === "completed" ?
      "completed" :
      classifyFailure(
        typeof parsed.error?.code === "string" ? parsed.error.code : undefined,
      );

  const currentStatus = summary?.status;
  if (
    currentStatus === "completed" ||
    currentStatus === "failed-permanent" ||
    currentStatus === "failed-transient"
  ) {
    if (currentStatus === inboundTerminal) {
      return { status: 204, body: "" };
    }
    logger.warn("summaryWebhook: terminal-state mismatch — keeping first", {
      clientJobId,
      currentStatus,
      inboundTerminal,
    });
    return { status: 200, body: "already-terminal" };
  }

  const updates: Partial<SummaryDocument> = {
    status: inboundTerminal,
    completedAt: admin.firestore.FieldValue.serverTimestamp(),
  };
  if (inboundTerminal === "completed") {
    const summaryText =
      typeof parsed.result?.summary === "string" ? parsed.result.summary : "";
    updates.content = summaryText;
  } else {
    updates.errorCode =
      typeof parsed.error?.code === "string" ? parsed.error.code : "unknown";
    updates.errorMessage =
      typeof parsed.error?.message === "string" ?
        parsed.error.message :
        undefined;
  }

  await summaryRef.set(updates, { merge: true });

  logger.info("summaryWebhook: processed", {
    clientJobId,
    inboundTerminal,
  });
  return { status: 204, body: "" };
}

export const summaryWebhook = onRequest(
  {
    memory: "256MiB",
    timeoutSeconds: 30,
    invoker: "public",
  },
  async (req, res) => {
    if (req.method !== "POST") {
      res.status(405).send("method-not-allowed");
      return;
    }
    const rawBuffer = (req as unknown as { rawBody?: Buffer }).rawBody;
    if (!rawBuffer) {
      logger.error("summaryWebhook: rawBody unavailable on request");
      res.status(500).send("raw-body-missing");
      return;
    }
    const result = await processSummaryWebhook({
      signatureHeader:
        req.header("x-summarizer-signature") ??
        req.header("X-Summarizer-Signature"),
      rawBody: rawBuffer,
    });
    res.status(result.status).send(result.body);
  },
);

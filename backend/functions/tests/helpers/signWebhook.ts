/**
 * Thin wrapper around the canonical signer in summarize-api so there is a
 * single source of truth for the HMAC scheme.  Any change to the wire format
 * only needs to happen in `summarizer/summarize-api/src/webhooks/signer.ts`.
 */
import { buildSignatureHeader } from "../../../../summarizer/summarize-api/src/webhooks/signer.js";

export interface SignedWebhook {
  rawBody: string;
  header: string;
  timestamp: number;
}

export function signWebhook(
  payload: unknown,
  secret: string,
  timestamp: number = Math.floor(Date.now() / 1000),
): SignedWebhook {
  const rawBody = JSON.stringify(payload);
  const { header } = buildSignatureHeader(secret, rawBody, timestamp);
  return { rawBody, header, timestamp };
}

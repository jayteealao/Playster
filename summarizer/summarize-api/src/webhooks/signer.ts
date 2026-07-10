import { createHmac } from "node:crypto";

// Stripe-style webhook signature.
//
// Canonical bytes: `${timestamp}.${rawBody}` where `rawBody` is the exact
// UTF-8 string used as the HTTP POST body. The verifier (slice 3) MUST read
// the raw request body before any JSON parse — any whitespace/key-order
// difference on either side will break verification.
//
// Header format: `X-Summarizer-Signature: t=<unix>,v1=<sha256-hex>`.

export interface SignatureHeader {
  header: string;
  timestamp: number;
}

export function buildSignatureHeader(
  secret: string,
  rawBody: string,
  timestamp: number = Math.floor(Date.now() / 1000),
): SignatureHeader {
  const canonical = `${timestamp}.${rawBody}`;
  const mac = createHmac("sha256", secret)
    .update(canonical, "utf8")
    .digest("hex");
  return { header: `t=${timestamp},v1=${mac}`, timestamp };
}

import { createHmac } from "node:crypto";

/**
 * Byte-for-byte replica of the summarize-api signer at
 * `summarizer/summarize-api/src/webhooks/signer.ts`. Kept in sync via the
 * fixture test in `tests/signWebhook-fixture.test.ts`.
 */
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
  const canonical = `${timestamp}.${rawBody}`;
  const mac = createHmac("sha256", secret).update(canonical, "utf8").digest("hex");
  return {
    rawBody,
    header: `t=${timestamp},v1=${mac}`,
    timestamp,
  };
}

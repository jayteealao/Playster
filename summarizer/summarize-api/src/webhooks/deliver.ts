import { buildSignatureHeader } from "./signer.js";

export interface WebhookDeliveryResult {
  ok: boolean;
  status?: number;
  attempts: number;
  error?: string;
}

export interface DeliverWebhookOptions {
  url: string;
  secret: string;
  payload: unknown;
  attempts?: number;
  // Base delay between attempts. The Nth retry waits `baseDelayMs * 3^N`.
  // Tests override to keep runs fast.
  baseDelayMs?: number;
  // Indirection for tests. Defaults to global fetch.
  fetchImpl?: typeof fetch;
  sleepImpl?: (ms: number) => Promise<void>;
  // Jitter source for the retry backoff. Defaults to Math.random; tests
  // override it to make the computed delays deterministic.
  randomFn?: () => number;
}

export async function deliverWebhook(
  opts: DeliverWebhookOptions,
): Promise<WebhookDeliveryResult> {
  const attempts = opts.attempts ?? 3;
  const baseDelayMs = opts.baseDelayMs ?? 5_000;
  const doFetch = opts.fetchImpl ?? fetch;
  const sleep =
    opts.sleepImpl ??
    ((ms: number) => new Promise<void>((resolve) => setTimeout(resolve, ms)));
  const randomFn = opts.randomFn ?? Math.random;

  // Compute the raw body ONCE — the same bytes go to the HMAC and the POST.
  // Re-stringifying would risk key-order or whitespace drift between signer
  // and verifier.
  const rawBody = JSON.stringify(opts.payload);
  const { header } = buildSignatureHeader(opts.secret, rawBody);

  let lastStatus: number | undefined;
  let lastError: string | undefined;

  for (let i = 0; i < attempts; i += 1) {
    try {
      const res = await doFetch(opts.url, {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          "X-Summarizer-Signature": header,
        },
        body: rawBody,
      });
      lastStatus = res.status;
      if (res.ok) {
        return { ok: true, status: res.status, attempts: i + 1 };
      }
      // 4xx (other than 408/429) are not retried — the receiver disagrees on
      // body/sig and a retry will just repeat the same rejection.
      if (
        res.status >= 400 &&
        res.status < 500 &&
        res.status !== 408 &&
        res.status !== 429
      ) {
        return {
          ok: false,
          status: res.status,
          attempts: i + 1,
          error: `non-retryable ${res.status}`,
        };
      }
    } catch (err) {
      lastError = err instanceof Error ? err.message : String(err);
    }
    if (i < attempts - 1) {
      const baseMs = baseDelayMs * 3 ** i;
      const jitterMs = baseMs * (0.75 + randomFn() * 0.5);
      await sleep(jitterMs);
    }
  }
  return {
    ok: false,
    status: lastStatus,
    attempts,
    error:
      lastError ??
      (lastStatus !== undefined
        ? `last status ${lastStatus}`
        : "exhausted retries"),
  };
}

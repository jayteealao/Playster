import { describe, it, expect, vi } from "vitest";
import { deliverWebhook } from "../src/webhooks/deliver.js";
import { buildSignatureHeader } from "../src/webhooks/signer.js";

function jsonResponse(status: number): Response {
  return new Response("", { status });
}

describe("deliverWebhook", () => {
  it("delivers once on a 200 and returns ok", async () => {
    const fetchImpl = vi.fn().mockResolvedValue(jsonResponse(200));
    const sleepImpl = vi.fn().mockResolvedValue(undefined);

    const result = await deliverWebhook({
      url: "https://example.test/webhook",
      secret: "sixteen-byte-key",
      payload: { client_job_id: "abc", status: "completed" },
      fetchImpl,
      sleepImpl,
    });

    expect(result).toEqual({ ok: true, status: 200, attempts: 1 });
    expect(fetchImpl).toHaveBeenCalledTimes(1);
    expect(sleepImpl).not.toHaveBeenCalled();
  });

  it("sends the same raw bytes used in the HMAC", async () => {
    let capturedBody: string | undefined;
    let capturedHeader: string | undefined;
    const fetchImpl = vi
      .fn()
      .mockImplementation((_url: string, init: RequestInit) => {
        capturedBody = init.body as string;
        capturedHeader = (init.headers as Record<string, string>)[
          "X-Summarizer-Signature"
        ];
        return Promise.resolve(jsonResponse(200));
      });

    const payload = {
      client_job_id: "abc",
      status: "completed",
      result: { summary: "ok" },
    };
    await deliverWebhook({
      url: "https://example.test/webhook",
      secret: "sixteen-byte-key",
      payload,
      fetchImpl,
      sleepImpl: () => Promise.resolve(),
    });

    expect(capturedBody).toBe(JSON.stringify(payload));
    expect(capturedHeader).toMatch(/^t=\d+,v1=[0-9a-f]{64}$/);

    // Re-derive: header timestamp + captured body must verify against secret.
    const tMatch = capturedHeader!.match(/^t=(\d+),v1=(.+)$/);
    expect(tMatch).not.toBeNull();
    const t = Number(tMatch![1]);
    const expected = buildSignatureHeader(
      "sixteen-byte-key",
      capturedBody!,
      t,
    ).header;
    expect(capturedHeader).toBe(expected);
  });

  it("retries on 503 with the expected backoff and succeeds on the third try", async () => {
    const fetchImpl = vi
      .fn()
      .mockResolvedValueOnce(jsonResponse(503))
      .mockResolvedValueOnce(jsonResponse(503))
      .mockResolvedValueOnce(jsonResponse(200));
    const sleepImpl = vi.fn().mockResolvedValue(undefined);

    const result = await deliverWebhook({
      url: "https://example.test/webhook",
      secret: "sixteen-byte-key",
      payload: {},
      baseDelayMs: 10,
      fetchImpl,
      sleepImpl,
    });

    expect(result).toEqual({ ok: true, status: 200, attempts: 3 });
    expect(fetchImpl).toHaveBeenCalledTimes(3);
    expect(sleepImpl).toHaveBeenCalledTimes(2);
    expect(sleepImpl).toHaveBeenNthCalledWith(1, 10);
    expect(sleepImpl).toHaveBeenNthCalledWith(2, 30);
  });

  it("gives up after `attempts` failures", async () => {
    const fetchImpl = vi.fn().mockResolvedValue(jsonResponse(503));

    const result = await deliverWebhook({
      url: "https://example.test/webhook",
      secret: "sixteen-byte-key",
      payload: {},
      attempts: 3,
      baseDelayMs: 1,
      fetchImpl,
      sleepImpl: () => Promise.resolve(),
    });

    expect(result.ok).toBe(false);
    expect(result.attempts).toBe(3);
    expect(result.status).toBe(503);
    expect(fetchImpl).toHaveBeenCalledTimes(3);
  });

  it("does not retry on non-retryable 4xx", async () => {
    const fetchImpl = vi.fn().mockResolvedValue(jsonResponse(401));

    const result = await deliverWebhook({
      url: "https://example.test/webhook",
      secret: "sixteen-byte-key",
      payload: {},
      attempts: 3,
      baseDelayMs: 1,
      fetchImpl,
      sleepImpl: () => Promise.resolve(),
    });

    expect(result.ok).toBe(false);
    expect(result.attempts).toBe(1);
    expect(result.status).toBe(401);
    expect(fetchImpl).toHaveBeenCalledTimes(1);
  });

  it("retries on 408 and 429", async () => {
    const fetchImpl = vi
      .fn()
      .mockResolvedValueOnce(jsonResponse(429))
      .mockResolvedValueOnce(jsonResponse(200));

    const result = await deliverWebhook({
      url: "https://example.test/webhook",
      secret: "sixteen-byte-key",
      payload: {},
      attempts: 3,
      baseDelayMs: 1,
      fetchImpl,
      sleepImpl: () => Promise.resolve(),
    });

    expect(result).toEqual({ ok: true, status: 200, attempts: 2 });
    expect(fetchImpl).toHaveBeenCalledTimes(2);
  });

  it("retries on network errors", async () => {
    const fetchImpl = vi
      .fn()
      .mockRejectedValueOnce(new Error("ECONNREFUSED"))
      .mockResolvedValueOnce(jsonResponse(200));

    const result = await deliverWebhook({
      url: "https://example.test/webhook",
      secret: "sixteen-byte-key",
      payload: {},
      baseDelayMs: 1,
      fetchImpl,
      sleepImpl: () => Promise.resolve(),
    });

    expect(result.ok).toBe(true);
    expect(result.attempts).toBe(2);
  });
});

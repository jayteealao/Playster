import { describe, it, expect } from "vitest";
import { createHmac } from "node:crypto";
import { signWebhook } from "./helpers/signWebhook";

/**
 * Sanity-check for the signWebhook test helper.  The helper now delegates to
 * the canonical signer in summarize-api, so there is a single implementation.
 * These assertions confirm the adapter still produces the correct wire format.
 */
describe("signWebhook helper", () => {
  it("matches a known vector byte-for-byte", () => {
    const secret = "test-secret-with-min-16-bytes";
    const payload = { client_job_id: "abc", status: "completed" };
    const timestamp = 1_700_000_000;
    const rawBody = '{"client_job_id":"abc","status":"completed"}';
    const expectedMac = createHmac("sha256", secret)
      .update(`${timestamp}.${rawBody}`, "utf8")
      .digest("hex");

    const { header, rawBody: signedRaw } = signWebhook(payload, secret, timestamp);
    expect(signedRaw).toBe(rawBody);
    expect(header).toBe(`t=${timestamp},v1=${expectedMac}`);
  });

  it("emits the t=<unix>,v1=<hex> header shape", () => {
    const { header } = signWebhook({ x: 1 }, "sixteen-byte-key", 1_700_000_001);
    expect(header).toMatch(/^t=\d+,v1=[0-9a-f]{64}$/);
  });
});

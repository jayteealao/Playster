import { describe, it, expect } from "vitest";
import { createHmac } from "node:crypto";
import { buildSignatureHeader } from "../src/webhooks/signer.js";

describe("buildSignatureHeader", () => {
  it("produces the expected v1 hex for a fixed vector", () => {
    const secret = "test-secret-with-min-16-bytes";
    const rawBody = '{"client_job_id":"abc","status":"completed"}';
    const timestamp = 1_700_000_000;

    const expectedMac = createHmac("sha256", secret)
      .update(`${timestamp}.${rawBody}`, "utf8")
      .digest("hex");

    const { header } = buildSignatureHeader(secret, rawBody, timestamp);
    expect(header).toBe(`t=${timestamp},v1=${expectedMac}`);
  });

  it("emits the t=<unix>,v1=<hex> header format", () => {
    const { header, timestamp } = buildSignatureHeader(
      "sixteen-byte-key",
      "{}",
    );
    expect(header).toMatch(/^t=\d+,v1=[0-9a-f]{64}$/);
    expect(header.startsWith(`t=${timestamp},v1=`)).toBe(true);
  });

  it("uses Math.floor(Date.now()/1000) as the default timestamp", () => {
    const before = Math.floor(Date.now() / 1000);
    const { timestamp } = buildSignatureHeader("sixteen-byte-key", "{}");
    const after = Math.floor(Date.now() / 1000);
    expect(timestamp).toBeGreaterThanOrEqual(before);
    expect(timestamp).toBeLessThanOrEqual(after);
  });

  it("handles multibyte UTF-8 in the body without corrupting the HMAC", () => {
    const secret = "sixteen-byte-key";
    const rawBody = '{"text":"日本語 — emoji 🌟 — done"}';
    const timestamp = 1_700_000_001;

    const expectedMac = createHmac("sha256", secret)
      .update(`${timestamp}.${rawBody}`, "utf8")
      .digest("hex");

    const { header } = buildSignatureHeader(secret, rawBody, timestamp);
    expect(header).toBe(`t=${timestamp},v1=${expectedMac}`);
  });

  it("changes the signature when the body changes by a single byte", () => {
    const secret = "sixteen-byte-key";
    const t = 1_700_000_002;
    const a = buildSignatureHeader(secret, '{"x":1}', t).header;
    const b = buildSignatureHeader(secret, '{"x":2}', t).header;
    expect(a).not.toBe(b);
  });
});

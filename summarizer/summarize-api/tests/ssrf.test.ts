import { describe, it, expect, vi, afterEach } from "vitest";
import dns from "node:dns";
import { isPrivateIp, validateUrl } from "../src/security/ssrf.js";

describe("SSRF protection", () => {
  describe("isPrivateIp", () => {
    it("blocks 127.0.0.1 (loopback)", () => {
      expect(isPrivateIp("127.0.0.1")).toBe(true);
    });

    it("blocks 10.0.0.1 (private class A)", () => {
      expect(isPrivateIp("10.0.0.1")).toBe(true);
    });

    it("blocks 192.168.1.1 (private class C)", () => {
      expect(isPrivateIp("192.168.1.1")).toBe(true);
    });

    it("blocks 169.254.169.254 (link-local / cloud metadata)", () => {
      expect(isPrivateIp("169.254.169.254")).toBe(true);
    });

    it("blocks 172.16.0.1 (private class B)", () => {
      expect(isPrivateIp("172.16.0.1")).toBe(true);
    });

    it("blocks 0.0.0.0", () => {
      expect(isPrivateIp("0.0.0.0")).toBe(true);
    });

    it("allows public IP 8.8.8.8", () => {
      expect(isPrivateIp("8.8.8.8")).toBe(false);
    });

    it("allows public IP 1.1.1.1", () => {
      expect(isPrivateIp("1.1.1.1")).toBe(false);
    });

    it("blocks IPv6 loopback ::1", () => {
      expect(isPrivateIp("::1")).toBe(true);
    });

    it("blocks IPv6 link-local fe80::", () => {
      expect(isPrivateIp("fe80::1")).toBe(true);
    });

    it("returns false for hostnames (defers to DNS resolution)", () => {
      // Regression: previously every hostname was misclassified as private
      // because isPrivateIpv4 treated 'not 4 numeric octets' as malformed-and-block,
      // short-circuiting before DNS could resolve real hostnames.
      expect(isPrivateIp("example.com")).toBe(false);
      expect(isPrivateIp("api.github.com")).toBe(false);
      expect(isPrivateIp("not-an-ip")).toBe(false);
    });
  });

  describe("validateUrl", () => {
    it("allows public hostnames that resolve to public IPs", async () => {
      // Uses real DNS — example.com is IANA-reserved for documentation
      // and consistently resolves to a public IP block.
      const result = await validateUrl("https://example.com");
      expect(result.safe).toBe(true);
    });

    it("blocks non-http schemes (ftp)", async () => {
      const result = await validateUrl("ftp://example.com/file");
      expect(result.safe).toBe(false);
      expect(result.error).toContain("Blocked URL scheme");
    });

    it("blocks non-http schemes (file)", async () => {
      const result = await validateUrl("file:///etc/passwd");
      expect(result.safe).toBe(false);
      expect(result.error).toContain("Blocked URL scheme");
    });

    it("blocks non-http schemes (javascript)", async () => {
      const result = await validateUrl("javascript:alert(1)");
      expect(result.safe).toBe(false);
    });

    it("blocks URLs with private IP 127.0.0.1", async () => {
      const result = await validateUrl("http://127.0.0.1:8080/api");
      expect(result.safe).toBe(false);
      expect(result.error).toContain("private");
    });

    it("blocks URLs with private IP 10.0.0.1", async () => {
      const result = await validateUrl("http://10.0.0.1/internal");
      expect(result.safe).toBe(false);
      expect(result.error).toContain("private");
    });

    it("blocks URLs with private IP 192.168.1.1", async () => {
      const result = await validateUrl("http://192.168.1.1/admin");
      expect(result.safe).toBe(false);
      expect(result.error).toContain("private");
    });

    it("blocks URLs with metadata IP 169.254.169.254", async () => {
      const result = await validateUrl(
        "http://169.254.169.254/latest/meta-data/",
      );
      expect(result.safe).toBe(false);
      expect(result.error).toContain("private");
    });

    it("rejects invalid URLs", async () => {
      const result = await validateUrl("not a url");
      expect(result.safe).toBe(false);
      expect(result.error).toContain("Invalid URL");
    });
  });

  describe("validateUrl allowPrivate (test-only webhook relaxation)", () => {
    afterEach(() => {
      vi.restoreAllMocks();
    });

    // Mirrors the harness case: a hostname (like the docker-network `mock-backend`)
    // that resolves via DNS to a private IP. Mock the resolver so the test is
    // deterministic and does not depend on real network state.
    function mockResolvesToPrivate() {
      vi.spyOn(dns.promises, "resolve4").mockResolvedValue(["172.18.0.3"]);
      vi.spyOn(dns.promises, "resolve6").mockRejectedValue(new Error("no-aaaa"));
    }

    it("blocks a hostname resolving to a private IP when allowPrivate is unset (default)", async () => {
      mockResolvesToPrivate();
      const result = await validateUrl("http://mock-backend:9000/webhook");
      expect(result.safe).toBe(false);
      expect(result.error).toContain("blocked IP");
    });

    it("permits a hostname resolving to a private IP when allowPrivate is true", async () => {
      mockResolvesToPrivate();
      const result = await validateUrl("http://mock-backend:9000/webhook", {
        allowPrivate: true,
      });
      expect(result.safe).toBe(true);
    });

    it("still enforces scheme even when allowPrivate is true", async () => {
      const result = await validateUrl("file:///etc/passwd", {
        allowPrivate: true,
      });
      expect(result.safe).toBe(false);
      expect(result.error).toContain("Blocked URL scheme");
    });
  });
});

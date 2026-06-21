import dns from "node:dns";

function isIpv4Literal(s: string): boolean {
  const parts = s.split(".").map(Number);
  return (
    parts.length === 4 &&
    parts.every((p) => Number.isInteger(p) && p >= 0 && p <= 255)
  );
}

export function isPrivateIp(ip: string): boolean {
  // IPv6 checks
  if (ip.includes(":")) {
    const normalized = ip.toLowerCase();
    if (normalized === "::1") return true;
    // fc00::/7 — starts with fc or fd
    if (normalized.startsWith("fc") || normalized.startsWith("fd")) return true;
    // fe80::/10 — link-local
    if (normalized.startsWith("fe80")) return true;
    // IPv4-mapped IPv6 (::ffff:x.x.x.x)
    const v4Match = normalized.match(/^::ffff:(\d+\.\d+\.\d+\.\d+)$/);
    if (v4Match && isIpv4Literal(v4Match[1])) return isPrivateIpv4(v4Match[1]);
    return false;
  }

  // Not an IPv4 literal — caller (validateUrl) will resolve via DNS and
  // re-check the resolved addresses. Treating malformed strings as private
  // here would incorrectly block every hostname before DNS ever runs.
  if (!isIpv4Literal(ip)) return false;
  return isPrivateIpv4(ip);
}

function isPrivateIpv4(ip: string): boolean {
  const parts = ip.split(".").map(Number);
  const [a, b] = parts;

  // 0.0.0.0/8
  if (a === 0) return true;
  // 10.0.0.0/8
  if (a === 10) return true;
  // 127.0.0.0/8
  if (a === 127) return true;
  // 172.16.0.0/12
  if (a === 172 && b >= 16 && b <= 31) return true;
  // 192.168.0.0/16
  if (a === 192 && b === 168) return true;
  // 169.254.0.0/16 (link-local, includes metadata 169.254.169.254)
  if (a === 169 && b === 254) return true;

  return false;
}

export async function validateUrl(
  url: string,
): Promise<{ safe: boolean; error?: string }> {
  let parsed: URL;
  try {
    parsed = new URL(url);
  } catch {
    return { safe: false, error: "Invalid URL" };
  }

  if (parsed.protocol !== "http:" && parsed.protocol !== "https:") {
    return {
      safe: false,
      error: `Blocked URL scheme "${parsed.protocol}" — only http and https are allowed`,
    };
  }

  const hostname = parsed.hostname;

  // Check if hostname is an IP literal
  if (isPrivateIp(hostname)) {
    return { safe: false, error: `Blocked private/reserved IP: ${hostname}` };
  }

  // Resolve DNS and check all IPs
  const ips: string[] = [];

  try {
    const ipv4 = await dns.promises.resolve4(hostname);
    ips.push(...ipv4);
  } catch {
    // No A records — that's fine, check AAAA
  }

  try {
    const ipv6 = await dns.promises.resolve6(hostname);
    ips.push(...ipv6);
  } catch {
    // No AAAA records — that's fine
  }

  if (ips.length === 0) {
    return {
      safe: false,
      error: `DNS resolution failed for hostname: ${hostname}`,
    };
  }

  for (const ip of ips) {
    if (isPrivateIp(ip)) {
      return {
        safe: false,
        error: `Hostname "${hostname}" resolves to blocked IP: ${ip}`,
      };
    }
  }

  return { safe: true };
}

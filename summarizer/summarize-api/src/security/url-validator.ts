import { validateUrl } from "./ssrf.js";

export async function validateAndNormalizeUrl(url: string): Promise<string> {
  let parsed: URL;
  try {
    parsed = new URL(url);
  } catch {
    throw new Error(`Malformed URL: ${url}`);
  }

  const result = await validateUrl(parsed.href);
  if (!result.safe) {
    throw new Error(result.error ?? "URL failed security validation");
  }

  return parsed.href;
}

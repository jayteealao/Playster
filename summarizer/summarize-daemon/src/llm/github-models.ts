export const GITHUB_MODELS_BASE_URL = "https://models.github.ai/inference";
export const GITHUB_MODELS_API_VERSION = "2026-03-10";

const GITHUB_COPILOT_PROVIDER_PATTERNS = {
  openai: [/^gpt-/i, /^chatgpt-/i, /^o\d(?=$|[-.])/i],
  anthropic: [/^claude-/i, /^(opus|sonnet|haiku)-/i],
  google: [/^gemini-/i],
  xai: [/^grok-/i],
} as const;

export function resolveGitHubModelsApiKey(env: Record<string, string | undefined>): string | null {
  const githubToken = env.GITHUB_TOKEN?.trim();
  if (githubToken) return githubToken;
  const ghToken = env.GH_TOKEN?.trim();
  return ghToken || null;
}

function inferGitHubCopilotProvider(
  model: string,
): "openai" | "anthropic" | "google" | "xai" | null {
  for (const [provider, patterns] of Object.entries(GITHUB_COPILOT_PROVIDER_PATTERNS)) {
    if (patterns.some((pattern) => pattern.test(model))) {
      return provider as "openai" | "anthropic" | "google" | "xai";
    }
  }
  return null;
}

export function resolveGitHubCopilotBackendModelId(model: string): string {
  const trimmed = model.trim();
  if (!trimmed) return trimmed;
  if (trimmed.includes("/")) return trimmed;
  const provider = inferGitHubCopilotProvider(trimmed);
  if (provider === "anthropic" && /^(opus|sonnet|haiku)-/i.test(trimmed)) {
    return `anthropic/claude-${trimmed}`;
  }
  if (provider) return `${provider}/${trimmed}`;
  return trimmed;
}

export function buildGitHubModelsHeaders(
  existing?: Record<string, string>,
): Record<string, string> {
  return {
    ...(existing ?? {}),
    Accept: "application/vnd.github+json",
    "X-GitHub-Api-Version": GITHUB_MODELS_API_VERSION,
  };
}

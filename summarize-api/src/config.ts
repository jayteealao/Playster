export interface Config {
  port: number;
  host: string;
  apiKeys: string[];
  daemonUrl: string;
  summarizeToken: string;
  maxUploadSize: number;
  maxConcurrentJobs: number;
  jobTimeout: number;
  rateLimitMax: number;
  rateLimitWindow: string;
  dbPath: string;
}

export function loadConfig(): Config {
  const apiKeysRaw = process.env["API_KEYS"];
  const summarizeToken = process.env["SUMMARIZE_TOKEN"];

  if (!summarizeToken) {
    throw new Error("SUMMARIZE_TOKEN environment variable is required");
  }
  if (!apiKeysRaw) {
    throw new Error("API_KEYS environment variable is required");
  }

  const apiKeys = apiKeysRaw
    .split(",")
    .map((k) => k.trim())
    .filter(Boolean);

  if (apiKeys.length === 0) {
    throw new Error("API_KEYS must contain at least one non-empty key");
  }

  return {
    port: Number(process.env["PORT"] ?? 3000),
    host: process.env["HOST"] ?? "0.0.0.0",
    apiKeys,
    daemonUrl: process.env["DAEMON_URL"] ?? "http://localhost:8787",
    summarizeToken,
    maxUploadSize: Number(process.env["MAX_UPLOAD_SIZE"] ?? 104857600),
    maxConcurrentJobs: Number(process.env["MAX_CONCURRENT_JOBS"] ?? 5),
    jobTimeout: Number(process.env["JOB_TIMEOUT_MS"] ?? 300000),
    rateLimitMax: Number(process.env["RATE_LIMIT_MAX"] ?? 30),
    rateLimitWindow: process.env["RATE_LIMIT_WINDOW"] ?? "1 minute",
    dbPath: process.env["DB_PATH"] ?? "./data/jobs.db",
  };
}

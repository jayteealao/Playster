# Security Model

## SSRF Protection

All user-supplied URLs (for both URL jobs and RSS feeds) pass through SSRF validation before any network request is made.

### Blocked IP Ranges

The following IP ranges are rejected:

| Range | Description |
|-------|-------------|
| `0.0.0.0/8` | Current network |
| `10.0.0.0/8` | Private (RFC 1918) |
| `127.0.0.0/8` | Loopback |
| `169.254.0.0/16` | Link-local / cloud metadata (e.g. `169.254.169.254`) |
| `172.16.0.0/12` | Private (RFC 1918) |
| `192.168.0.0/16` | Private (RFC 1918) |
| `::1` | IPv6 loopback |
| `fc00::/7` | IPv6 unique local (`fc` and `fd` prefixes) |
| `fe80::/10` | IPv6 link-local |
| `::ffff:x.x.x.x` | IPv4-mapped IPv6 (checked against IPv4 rules) |

Malformed IP addresses are treated as private and blocked.

### DNS Resolution Checks

URLs are validated in two stages:

1. **Scheme check** — only `http:` and `https:` are allowed. All other schemes (e.g. `file:`, `ftp:`, `gopher:`) are rejected.
2. **IP literal check** — if the hostname is an IP address, it is checked directly against the blocked ranges.
3. **DNS resolution** — the hostname is resolved via both A (IPv4) and AAAA (IPv6) records. Every resolved IP is checked against the blocked ranges. If DNS resolution fails entirely, the URL is rejected.

This prevents DNS rebinding attacks where a hostname initially resolves to a public IP but later resolves to a private one.

### RSS Feed URLs

RSS feed URLs go through the same SSRF validation. After parsing the feed, the resolved item URL is also validated via the URL runner before fetching.

---

## API Key Authentication

All endpoints except `/health` and `/v1/ping` require a valid API key.

### How It Works

- Pass the key in the `X-API-Key` request header.
- The server compares the key against the `API_KEYS` environment variable (comma-separated list).
- If the key is missing, empty, or not in the list, the request is rejected with `401 Unauthorized`.

### Configuration

```bash
# Single key
API_KEYS=sk-my-secret-key

# Multiple keys
API_KEYS=sk-key-one,sk-key-two,sk-key-three
```

At least one non-empty key is required. The server will refuse to start without it.

---

## Rate Limiting

Rate limiting is applied per-IP using `@fastify/rate-limit`.

| Setting | Default | Env Var |
|---------|---------|---------|
| Max requests per window | 30 | `RATE_LIMIT_MAX` |
| Time window | 1 minute | `RATE_LIMIT_WINDOW` |

When the limit is exceeded, the server returns `429 Too Many Requests`.

---

## Input Validation

All JSON request bodies are validated using Zod schemas before processing.

### URL Jobs

- `url` — must be a valid URL string
- `options.model` — optional string
- `options.format` — optional, one of `"markdown"`, `"md"`, `"text"`
- `options.length` — optional, one of `"short"`, `"medium"`, `"long"`, `"xl"`, `"xxl"`
- `options.language` — optional string
- `options.mode` — optional, one of `"auto"`, `"website"`, `"youtube"`, `"media"`
- `options.prompt` — optional string

### RSS Jobs

- `rss` — must be a valid URL string
- `item` — optional, string or number (defaults to `"latest"`)
- `options.model` — optional string
- `options.format` — optional, one of `"markdown"`, `"md"`, `"text"`
- `options.length` — optional, one of `"short"`, `"medium"`, `"long"`, `"xl"`, `"xxl"`
- `options.language` — optional string

### File Uploads

- File must be present in the multipart form.
- The optional `options` form field must be valid JSON if provided.

Validation errors return `400` with field-level error details.

---

## Upload Size Limits

File uploads are limited by `MAX_UPLOAD_SIZE` (default: 100 MB / 104,857,600 bytes).

Two checks are performed:

1. **Pre-check** — the `Content-Length` header is compared to the limit before reading the body.
2. **Stream check** — the file is streamed to disk via `@fastify/multipart`. If the stream is truncated (file exceeded the configured `fileSize` limit), a `413` error is returned.

This prevents both declared-oversize and streaming-oversize attacks.

---

## Job Timeout

Each job has a configurable timeout (`JOB_TIMEOUT_MS`, default: 5 minutes / 300,000 ms).

- **URL jobs**: an `AbortController` signal is attached to both the daemon POST request and the SSE stream. If the timeout fires, both are aborted and the job is marked as failed.
- **Upload jobs**: the child process is sent `SIGTERM`. If it doesn't exit within 5 seconds, `SIGKILL` is sent.

---

## Internal Network

The summarize-api server acts as a gateway to an internal summarization daemon (`DAEMON_URL`, default: `http://localhost:8787`). The daemon is not exposed publicly — all client requests go through the API server, which handles authentication, rate limiting, and SSRF protection.

Communication between the API server and daemon uses a `SUMMARIZE_TOKEN` bearer token for authentication.

---

## CORS

CORS is disabled by default (`origin: false`). No cross-origin browser requests are accepted unless CORS is explicitly configured.

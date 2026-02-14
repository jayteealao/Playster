# API Reference

Base URL: `http://localhost:3000`

All endpoints except `/health` require the `X-API-Key` header.

---

## POST /v1/jobs

Create a summarization job. Accepts three input types: URL, RSS feed, or file upload.

### URL Job (JSON)

Summarize a web page or media URL.

```bash
curl -X POST http://localhost:3000/v1/jobs \
  -H "Content-Type: application/json" \
  -H "X-API-Key: YOUR_API_KEY" \
  -d '{
    "url": "https://example.com/article"
  }'
```

With all options:

```bash
curl -X POST http://localhost:3000/v1/jobs \
  -H "Content-Type: application/json" \
  -H "X-API-Key: YOUR_API_KEY" \
  -d '{
    "url": "https://example.com/article",
    "options": {
      "model": "gpt-4o",
      "format": "markdown",
      "length": "long",
      "language": "en",
      "mode": "website",
      "prompt": "Focus on the key takeaways"
    }
  }'
```

**Response** (`201 Created`):

```json
{ "ok": true, "id": "abc123" }
```

### RSS Feed Job (JSON)

Summarize an item from an RSS feed.

```bash
curl -X POST http://localhost:3000/v1/jobs \
  -H "Content-Type: application/json" \
  -H "X-API-Key: YOUR_API_KEY" \
  -d '{
    "rss": "https://example.com/feed.xml"
  }'
```

With item selection and options:

```bash
curl -X POST http://localhost:3000/v1/jobs \
  -H "Content-Type: application/json" \
  -H "X-API-Key: YOUR_API_KEY" \
  -d '{
    "rss": "https://example.com/feed.xml",
    "item": "latest",
    "options": {
      "model": "gpt-4o",
      "format": "text",
      "length": "short",
      "language": "es"
    }
  }'
```

The `item` field selects which feed entry to summarize:

| Value | Behavior |
|-------|----------|
| `"latest"` (default) | First item in the feed |
| `0`, `1`, `2`... | Item by index |
| `"guid-string"` | Item by GUID |

**Response** (`201 Created`):

```json
{ "ok": true, "id": "abc123" }
```

### File Upload (Multipart)

Upload a file for summarization.

```bash
curl -X POST http://localhost:3000/v1/jobs \
  -H "X-API-Key: YOUR_API_KEY" \
  -F "file=@document.pdf"
```

With options (passed as a JSON string in the `options` form field):

```bash
curl -X POST http://localhost:3000/v1/jobs \
  -H "X-API-Key: YOUR_API_KEY" \
  -F "file=@document.pdf" \
  -F 'options={"model":"gpt-4o","length":"medium"}'
```

**Response** (`201 Created`):

```json
{ "ok": true, "id": "abc123" }
```

---

## GET /v1/jobs/:id

Get the current status of a job.

```bash
curl http://localhost:3000/v1/jobs/abc123 \
  -H "X-API-Key: YOUR_API_KEY"
```

**Response** (`200 OK`):

```json
{
  "id": "abc123",
  "type": "url",
  "status": "completed",
  "source": "https://example.com/article",
  "options": "{\"model\":\"gpt-4o\"}",
  "result": "{\"summary\":\"...\"}",
  "error": null,
  "daemon_job_id": "daemon-xyz",
  "client_id": null,
  "metadata": null,
  "created_at": "2025-01-15T10:30:00.000Z",
  "updated_at": "2025-01-15T10:30:05.000Z",
  "completed_at": "2025-01-15T10:30:05.000Z"
}
```

Job statuses: `queued`, `running`, `completed`, `failed`.

---

## GET /v1/jobs/:id/events

Stream real-time events for a job via Server-Sent Events (SSE).

```bash
curl -N http://localhost:3000/v1/jobs/abc123/events \
  -H "X-API-Key: YOUR_API_KEY"
```

The `-N` flag disables curl's output buffering, which is required for streaming.

**Event stream format:**

```
data: {"type":"status","data":{"status":"running"},"timestamp":"..."}

data: {"type":"chunk","data":{"text":"The article discusses..."},"timestamp":"..."}

data: {"type":"meta","data":{...},"timestamp":"..."}

data: {"type":"metrics","data":{...},"timestamp":"..."}

data: {"type":"done","data":{"done":true,"result":"Full summary text..."},"timestamp":"..."}

```

### Event Types

| Type | Description |
|------|-------------|
| `status` | Job status updates and progress messages |
| `chunk` | Incremental text chunks of the summary |
| `meta` | Metadata about the content being summarized |
| `metrics` | Processing metrics |
| `done` | Terminal event — summary is complete |
| `error` | Terminal event — job failed |

The stream replays all existing events on connect, then streams new events in real time. The connection closes automatically after a `done` or `error` event.

A keepalive comment (`:`) is sent every 15 seconds.

---

## GET /health

Health check endpoint. Does not require authentication.

```bash
curl http://localhost:3000/health
```

**Response when healthy** (`200 OK`):

```json
{
  "status": "ok",
  "daemon": "reachable",
  "timestamp": "2025-01-15T10:30:00.000Z"
}
```

**Response when degraded** (`503 Service Unavailable`):

```json
{
  "status": "degraded",
  "daemon": "unreachable",
  "timestamp": "2025-01-15T10:30:00.000Z"
}
```

---

## Options Reference

These options apply to URL and RSS jobs (JSON body) and file upload jobs (via the `options` form field).

| Parameter | Type | Values | Description |
|-----------|------|--------|-------------|
| `model` | string | Any model ID (e.g. `"gpt-4o"`) | LLM model to use |
| `format` | enum | `"markdown"`, `"md"`, `"text"` | Output format |
| `length` | enum | `"short"`, `"medium"`, `"long"`, `"xl"`, `"xxl"` | Summary length |
| `language` | string | Language code (e.g. `"en"`, `"es"`) | Output language |
| `mode` | enum | `"auto"`, `"website"`, `"youtube"`, `"media"` | Content detection mode (URL jobs only) |
| `prompt` | string | Any text | Custom prompt for the summarizer (URL jobs only) |

---

## Error Responses

### 400 Bad Request

Missing or invalid input.

```json
{
  "error": "Validation failed",
  "details": {
    "url": ["Invalid url"]
  }
}
```

```json
{
  "error": "Request must include a 'url' or 'rss' field, or be a multipart file upload"
}
```

### 401 Unauthorized

Missing or invalid API key.

```json
{
  "error": "Unauthorized",
  "message": "Invalid or missing API key"
}
```

### 403 Forbidden

URL blocked by SSRF protection.

```json
{
  "error": "Blocked private/reserved IP: 127.0.0.1"
}
```

### 404 Not Found

Job ID does not exist.

```json
{
  "error": "Job not found"
}
```

### 413 Payload Too Large

Uploaded file exceeds the size limit.

```json
{
  "error": "File too large"
}
```

### 429 Too Many Requests

Rate limit exceeded.

```json
{
  "error": "Rate limit exceeded, retry in 60 seconds"
}
```

# Summarize API

An HTTP API server for summarizing web pages, RSS feed items, and uploaded files using LLMs. Submit a job, stream results via Server-Sent Events.

## Architecture

```
┌─────────┐       ┌──────────────────┐       ┌──────────────────┐
│  Client  │──────▶│  summarize-api   │──────▶│  Summarize       │
│  (curl,  │ HTTP  │  (Fastify)       │ HTTP  │  Daemon          │
│  browser)│◀──────│                  │◀──────│  (localhost:8787) │
└─────────┘  SSE  │  - Auth          │  SSE  └──────────────────┘
                   │  - Rate limiting │
                   │  - SSRF checks   │
                   │  - Job queue     │
                   │  - SQLite DB     │
                   └──────────────────┘
```

The API server is the public-facing gateway. It authenticates requests, validates input, enforces rate limits and SSRF protections, then delegates summarization to an internal daemon.

## Quickstart

### 1. Clone

```bash
git clone <repo-url>
cd summarize-api
```

### 2. Install dependencies

```bash
pnpm install
```

### 3. Configure environment

```bash
cp .env.example .env
# Edit .env — at minimum set:
#   API_KEYS=sk-your-secret-key
#   SUMMARIZE_TOKEN=tok-daemon-secret
#   OPENAI_API_KEY=sk-...   (or another LLM provider)
```

See [docs/CONFIGURATION.md](docs/CONFIGURATION.md) for all environment variables.

### 4. Start the server

```bash
# Development (with hot reload)
pnpm dev

# Production
pnpm build && pnpm start
```

### 5. Verify and submit a job

```bash
# Health check
curl http://localhost:3000/health

# Submit a URL for summarization
curl -X POST http://localhost:3000/v1/jobs \
  -H "Content-Type: application/json" \
  -H "X-API-Key: sk-your-secret-key" \
  -d '{"url": "https://example.com"}'

# Stream results
curl -N http://localhost:3000/v1/jobs/<job-id>/events \
  -H "X-API-Key: sk-your-secret-key"
```

## Documentation

- **[API Reference](docs/USAGE.md)** — All endpoints, parameters, curl examples, and error codes
- **[Configuration](docs/CONFIGURATION.md)** — Environment variables and defaults
- **[Security](docs/SECURITY.md)** — SSRF protection, auth, rate limiting, input validation
- **[SSE Client Example](docs/SSE-CLIENT.md)** — Runnable Node.js script for consuming the event stream
- **[Deploy harness + Cloud Run runbook](deploy/README.md)** — Unified container, docker-compose harness, deploy procedure

## License

MIT

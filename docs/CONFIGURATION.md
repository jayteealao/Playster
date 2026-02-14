# Configuration

All configuration is via environment variables. Create a `.env` file in the project root or export variables in your shell.

## Required Variables

| Variable | Description |
|----------|-------------|
| `API_KEYS` | Comma-separated list of valid API keys for client authentication. At least one key is required. |
| `SUMMARIZE_TOKEN` | Bearer token for authenticating with the internal summarization daemon. |

The server will refuse to start if either of these is missing.

## Server

| Variable | Default | Description |
|----------|---------|-------------|
| `PORT` | `3000` | HTTP port the API server listens on. |
| `HOST` | `0.0.0.0` | Bind address. Use `127.0.0.1` to restrict to localhost. |

## Daemon Connection

| Variable | Default | Description |
|----------|---------|-------------|
| `DAEMON_URL` | `http://localhost:8787` | URL of the internal summarization daemon. |
| `SUMMARIZE_TOKEN` | *(required)* | Bearer token sent in the `Authorization` header to the daemon. |

## Limits

| Variable | Default | Description |
|----------|---------|-------------|
| `MAX_UPLOAD_SIZE` | `104857600` (100 MB) | Maximum file upload size in bytes. |
| `MAX_CONCURRENT_JOBS` | `5` | Maximum number of jobs processed in parallel. |
| `JOB_TIMEOUT_MS` | `300000` (5 min) | Per-job timeout in milliseconds. Jobs exceeding this are killed. |

## Rate Limiting

| Variable | Default | Description |
|----------|---------|-------------|
| `RATE_LIMIT_MAX` | `30` | Maximum requests per time window per IP. |
| `RATE_LIMIT_WINDOW` | `1 minute` | Time window for rate limiting. Accepts human-readable strings (e.g. `"1 minute"`, `"30 seconds"`). |

## Database

| Variable | Default | Description |
|----------|---------|-------------|
| `DB_PATH` | `./data/jobs.db` | Path to the SQLite database file. The directory is created automatically. |

## LLM Provider Keys

These are passed through to the summarization daemon. Set the keys for whichever providers you intend to use:

| Variable | Description |
|----------|-------------|
| `OPENAI_API_KEY` | OpenAI API key |
| `ANTHROPIC_API_KEY` | Anthropic API key |
| `GOOGLE_API_KEY` | Google AI API key |
| `MISTRAL_API_KEY` | Mistral API key |
| `GROQ_API_KEY` | Groq API key |

## Example `.env` File

```bash
# Required
API_KEYS=sk-my-api-key-1,sk-my-api-key-2
SUMMARIZE_TOKEN=tok-daemon-secret

# Server
PORT=3000
HOST=0.0.0.0

# Daemon
DAEMON_URL=http://localhost:8787

# Limits
MAX_UPLOAD_SIZE=104857600
MAX_CONCURRENT_JOBS=5
JOB_TIMEOUT_MS=300000

# Rate limiting
RATE_LIMIT_MAX=30
RATE_LIMIT_WINDOW=1 minute

# Database
DB_PATH=./data/jobs.db

# LLM providers (set the ones you use)
OPENAI_API_KEY=sk-...
ANTHROPIC_API_KEY=sk-ant-...
```

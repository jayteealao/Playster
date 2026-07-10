# SSE Client Example

A runnable Node.js script that creates a summarization job and streams the results via Server-Sent Events.

## Usage

```bash
# Summarize a URL
node sse-client.mjs https://example.com/article

# With a custom API key and server
API_KEY=sk-my-key API_URL=http://localhost:3000 node sse-client.mjs https://example.com/article
```

## Script

Save the following as `sse-client.mjs`:

```js
#!/usr/bin/env node

const API_URL = process.env.API_URL || "http://localhost:3000";
const API_KEY = process.env.API_KEY || "test-key";

const url = process.argv[2];
if (!url) {
  console.error("Usage: node sse-client.mjs <url>");
  process.exit(1);
}

async function main() {
  // Step 1: Create a job
  console.log(`Creating job for: ${url}`);

  const createRes = await fetch(`${API_URL}/v1/jobs`, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      "X-API-Key": API_KEY,
    },
    body: JSON.stringify({ url }),
  });

  if (!createRes.ok) {
    const body = await createRes.text();
    console.error(`Failed to create job (${createRes.status}): ${body}`);
    process.exit(1);
  }

  const { id } = await createRes.json();
  console.log(`Job created: ${id}`);
  console.log(`Streaming events from ${API_URL}/v1/jobs/${id}/events\n`);

  // Step 2: Connect to SSE stream
  const sseRes = await fetch(`${API_URL}/v1/jobs/${id}/events`, {
    headers: {
      "X-API-Key": API_KEY,
      Accept: "text/event-stream",
    },
  });

  if (!sseRes.ok || !sseRes.body) {
    console.error(`SSE connection failed (${sseRes.status})`);
    process.exit(1);
  }

  // Step 3: Parse SSE stream manually
  const reader = sseRes.body.getReader();
  const decoder = new TextDecoder();
  let buffer = "";

  while (true) {
    const { done, value } = await reader.read();
    if (done) break;

    buffer += decoder.decode(value, { stream: true });

    // SSE events are separated by double newlines
    const parts = buffer.split("\n\n");
    buffer = parts.pop(); // Keep incomplete part in buffer

    for (const part of parts) {
      const line = part.trim();

      // Skip keepalive comments
      if (line.startsWith(":") || line === "") continue;

      // Extract data from "data: {...}" lines
      const match = line.match(/^data:\s*(.+)$/m);
      if (!match) continue;

      let event;
      try {
        event = JSON.parse(match[1]);
      } catch {
        console.log(`[raw] ${match[1]}`);
        continue;
      }

      switch (event.type) {
        case "status":
          console.log(`[status] ${event.data?.message || event.data?.status || JSON.stringify(event.data)}`);
          break;
        case "chunk":
          process.stdout.write(event.data?.text || "");
          break;
        case "meta":
          console.log(`[meta] ${JSON.stringify(event.data)}`);
          break;
        case "metrics":
          console.log(`[metrics] ${JSON.stringify(event.data)}`);
          break;
        case "done":
          console.log("\n\n[done] Summary complete.");
          reader.cancel();
          return;
        case "error":
          console.error(`\n[error] ${event.data?.message || JSON.stringify(event.data)}`);
          reader.cancel();
          process.exit(1);
        default:
          console.log(`[${event.type}] ${JSON.stringify(event.data)}`);
      }
    }
  }
}

main().catch((err) => {
  console.error("Fatal:", err.message);
  process.exit(1);
});
```

## How It Works

1. **POST /v1/jobs** — creates a summarization job and receives a job ID.
2. **GET /v1/jobs/:id/events** — opens an SSE connection that replays existing events and streams new ones.
3. The script parses the `data:` lines from the SSE stream manually using `fetch` and a `ReadableStream` reader (no external dependencies).
4. Text chunks are printed to stdout as they arrive, building up the summary in real time.
5. The stream ends when a `done` or `error` event is received.

## Event Types

| Type | Behavior |
|------|----------|
| `status` | Logged as a status message |
| `chunk` | Written to stdout (the summary text) |
| `meta` | Logged as JSON |
| `metrics` | Logged as JSON |
| `done` | Terminal — script exits successfully |
| `error` | Terminal — script exits with code 1 |

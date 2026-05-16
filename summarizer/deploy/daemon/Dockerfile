FROM node:22-slim

# Install system dependencies (ffmpeg, yt-dlp, python3, curl for healthcheck)
RUN apt-get update && apt-get install -y --no-install-recommends \
    python3 python3-pip ffmpeg curl ca-certificates git \
    && rm -rf /var/lib/apt/lists/*

# Install yt-dlp
RUN curl -L https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp -o /usr/local/bin/yt-dlp \
    && chmod a+rx /usr/local/bin/yt-dlp

# Install pnpm
RUN corepack enable && corepack prepare pnpm@10.25.0 --activate

# Clone and build summarize
WORKDIR /app
RUN git clone --depth 1 https://github.com/steipete/summarize.git . \
    && rm -rf .git

RUN pnpm install --frozen-lockfile
RUN pnpm build

# Patch the daemon to bind to 0.0.0.0 instead of 127.0.0.1
# The upstream daemon hardcodes 127.0.0.1 which doesn't work in Docker networking
RUN sed -i 's/127\.0\.0\.1/0.0.0.0/g' dist/daemon/constants.js || true

# Create config and cache directories
RUN mkdir -p /root/.summarize/logs

# Entrypoint script writes daemon config and starts in foreground
COPY entrypoint.sh /app/entrypoint.sh
RUN chmod +x /app/entrypoint.sh

EXPOSE 8787

ENTRYPOINT ["/app/entrypoint.sh"]

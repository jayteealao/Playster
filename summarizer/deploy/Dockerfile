# syntax=docker/dockerfile:1.7
#
# Single image that runs both the summarize-api Fastify gateway and the
# vendored steipete/summarize daemon as one Cloud Run service.
#
# Three stages:
#   1. daemon-build — install + build the vendored subtree (its own pnpm)
#   2. api-build    — install + build summarize-api (workspace pnpm)
#   3. runtime      — node:24-slim with ffmpeg/tesseract/yt-dlp + both builds
#
# Build from the repository root:
#   docker build -f summarizer/deploy/Dockerfile -t playster/summarizer:dev .

# ---------------------------------------------------------------------------
# Stage 1 — daemon-build
# ---------------------------------------------------------------------------
# Updated digest by running: docker manifest inspect node:24-slim
FROM node:26-slim@sha256:ffc78385a788964bb3cbab5e434ff79a10bdc25b8ae6db03fe5fe6cb14053c09 AS daemon-build

ENV CI=true

RUN apt-get update \
 && apt-get install -y --no-install-recommends python3 make g++ \
 && rm -rf /var/lib/apt/lists/*

RUN corepack enable && corepack prepare pnpm@10.33.2 --activate

WORKDIR /daemon
COPY summarizer/summarize-daemon/ ./
RUN pnpm install --frozen-lockfile
RUN pnpm build
RUN pnpm prune --prod --ignore-scripts

# ---------------------------------------------------------------------------
# Stage 2 — api-build
# ---------------------------------------------------------------------------
# Updated digest by running: docker manifest inspect node:24-slim
FROM node:26-slim@sha256:ffc78385a788964bb3cbab5e434ff79a10bdc25b8ae6db03fe5fe6cb14053c09 AS api-build

ENV CI=true

RUN apt-get update \
 && apt-get install -y --no-install-recommends python3 make g++ \
 && rm -rf /var/lib/apt/lists/*

RUN corepack enable && corepack prepare pnpm@10.33.2 --activate

WORKDIR /app
COPY pnpm-workspace.yaml pnpm-lock.yaml package.json ./
COPY summarizer/summarize-api/package.json ./summarizer/summarize-api/package.json
COPY backend/functions/package.json ./backend/functions/package.json

RUN pnpm install --frozen-lockfile --filter summarize-api...

COPY summarizer/summarize-api/ ./summarizer/summarize-api/

RUN pnpm --filter summarize-api build
RUN cd summarizer/summarize-api && pnpm prune --prod --ignore-scripts

# ---------------------------------------------------------------------------
# Stage 3 — runtime
# ---------------------------------------------------------------------------
# Updated digest by running: docker manifest inspect node:24-slim
FROM node:26-slim@sha256:ffc78385a788964bb3cbab5e434ff79a10bdc25b8ae6db03fe5fe6cb14053c09 AS runtime

ARG YT_DLP_VERSION=2026.02.21

RUN apt-get update \
 && apt-get install -y --no-install-recommends \
        ffmpeg \
        tesseract-ocr \
        ca-certificates \
        tini \
        curl \
        python3 \
 && rm -rf /var/lib/apt/lists/*

# SHA256 verified against https://github.com/yt-dlp/yt-dlp/releases/download/${YT_DLP_VERSION}/SHA2-256SUMS
# Run `sha256sum -c` to verify; update YT_DLP_SHA256 whenever bumping YT_DLP_VERSION.
ARG YT_DLP_SHA256=91d023f421dffd6fd354d233edb784a05d5dde2c1c585fcb599d5cc23ecb759e
RUN curl -fsSL "https://github.com/yt-dlp/yt-dlp/releases/download/${YT_DLP_VERSION}/yt-dlp" \
        -o /usr/local/bin/yt-dlp \
 && echo "${YT_DLP_SHA256}  /usr/local/bin/yt-dlp" | sha256sum -c - \
 && chmod a+rx /usr/local/bin/yt-dlp \
 && /usr/local/bin/yt-dlp --version | tee /etc/yt-dlp.version

COPY --from=daemon-build /daemon                                /opt/daemon
COPY --from=api-build    /app/node_modules                      /app/node_modules
COPY --from=api-build    /app/summarizer/summarize-api          /app/summarizer/summarize-api
COPY summarizer/deploy/entrypoint.js                            /opt/entrypoint.js

ENV NODE_ENV=production \
    DAEMON_URL=http://127.0.0.1:8787 \
    PORT=8080 \
    HOST=0.0.0.0 \
    NODE_PATH=/app/node_modules \
    DB_PATH=/app/summarizer/summarize-api/data/jobs.db

EXPOSE 8080

HEALTHCHECK --start-period=45s --interval=30s --timeout=10s --retries=3 \
  CMD curl -fsS "http://localhost:${PORT:-8080}/health" || exit 1

RUN chown -R node:node /app /opt

USER node

ENTRYPOINT ["/usr/bin/tini", "--"]
CMD ["node", "/opt/entrypoint.js"]

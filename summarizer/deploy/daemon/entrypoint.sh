#!/bin/sh
set -e

# The upstream daemon reads its config from ~/.summarize/daemon.json
# Write the config file with the token from the environment
DAEMON_PORT="${PORT:-8787}"
DAEMON_TOKEN="${SUMMARIZE_TOKEN:?SUMMARIZE_TOKEN environment variable is required}"

cat > /root/.summarize/daemon.json <<EOF
{
  "version": 1,
  "token": "${DAEMON_TOKEN}",
  "port": ${DAEMON_PORT},
  "installedAt": "$(date -u +%Y-%m-%dT%H:%M:%S.000Z)"
}
EOF

echo "Starting summarize daemon on 0.0.0.0:${DAEMON_PORT}"

# Run daemon in foreground mode
exec node dist/cli.js daemon run

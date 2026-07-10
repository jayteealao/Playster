#!/usr/bin/env bash
# Seed quota/openrouter to requestCount = dailyLimit so the QuotaBanner shows
# and all Summarize CTAs disable.
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
HELPER_JS="${ROOT_DIR}/helpers/write-quota-cap.js"

cd "${ROOT_DIR}/../.."
firebase emulators:exec --only firestore --project playster-dev \
  "node ${HELPER_JS}"

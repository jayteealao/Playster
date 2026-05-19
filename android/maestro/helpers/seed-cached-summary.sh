#!/usr/bin/env bash
# Seed summaries/VID_CACHED at status=completed so the cached-navigation
# slice-local AC has a deterministic fixture.
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
HELPER_JS="${ROOT_DIR}/helpers/write-cached-summary.js"

cd "${ROOT_DIR}/../.."
firebase emulators:exec --only firestore --project playster-dev \
  "node ${HELPER_JS}"

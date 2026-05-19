#!/usr/bin/env bash
# Seed the Firebase emulator with a single playlist + video that have NO
# corresponding summaries/ doc. AC-5 flow: tap Summarize → in-progress UI.
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
HELPER_JS="${ROOT_DIR}/helpers/write-fresh-state.js"

cd "${ROOT_DIR}/../.."
firebase emulators:exec --only firestore --project playster-dev \
  "node ${HELPER_JS}"

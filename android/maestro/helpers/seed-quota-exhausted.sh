#!/usr/bin/env bash
# Seed the editorial corpus + an exhausted quota/openrouter doc so the re-skinned
# quota notice appears on the Playlist Summary tab (semantics preserved).
# Re-pointed off the legacy playster-dev / throwaway-emulator target onto the
# running editorial corpus under the app's own project id (see
# quota-exhausted-banner.yaml prerequisites).
set -euo pipefail

HELPER_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
export FIRESTORE_EMULATOR_HOST="${FIRESTORE_EMULATOR:-${FIRESTORE_EMULATOR_HOST:-127.0.0.1:8080}}"

bash "${HELPER_DIR}/seed-editorial-corpus.sh"
node "${HELPER_DIR}/write-quota-cap.js"
echo "seed-quota-exhausted OK (firestore=${FIRESTORE_EMULATOR_HOST})"

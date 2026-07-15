#!/usr/bin/env bash
# Seed the editorial corpus — which already includes summaries/ed-v09 at
# status=completed — so the cached-summary regression flow reads a completed
# summary on the Playlist Summary tab with NO new requestSummary call. Re-pointed
# off the legacy playster-dev / throwaway-emulator target onto the running
# editorial corpus (see cached-summary-navigation.yaml prerequisites).
set -euo pipefail

HELPER_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
bash "${HELPER_DIR}/seed-editorial-corpus.sh"
echo "seed-cached-summary OK (editorial corpus seeded; summaries/ed-v09 completed)"

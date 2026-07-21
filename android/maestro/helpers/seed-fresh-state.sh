#!/usr/bin/env bash
# Construct the manual-summary FRESH state: the full editorial corpus seeded
# (episode present), then the featured episode's summaries/ doc and the
# quota doc DELETED — so the Player's Summary tab offers Summarize and the
# dispatch is quota-clear. Mirrors seed-editorial-corpus.sh's running-suite
# pattern (no throwaway emulators:exec — the suite must outlive this script
# so the app can drive against it).
#
# Requires a RUNNING emulator suite (from backend/):
#   firebase emulators:start --only auth,firestore,functions --project playster-406121
# (functions included because the manual-summary flow dispatches the real
# callable; see manual-summary-fresh.yaml prerequisites for the secrets +
# mock dispatch-target setup.)
set -euo pipefail

HELPER_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

bash "${HELPER_DIR}/seed-editorial-corpus.sh"

export FIRESTORE_EMULATOR_HOST="${FIRESTORE_EMULATOR:-${FIRESTORE_EMULATOR_HOST:-127.0.0.1:8080}}"
node "${HELPER_DIR}/write-fresh-state.js"

#!/usr/bin/env bash
# Predeploy guard: prevents accidental deployment of firestore.rules while the
# bootstrap UID sentinel "__BOOTSTRAP_UID__" is still present.
# Wire this as a firestore predeploy step in firebase.json.
set -euo pipefail

SENTINEL="__BOOTSTRAP_UID__"
RULES_FILE="$(dirname "$0")/../firestore.rules"

if grep -qF "$SENTINEL" "$RULES_FILE"; then
  echo "ERROR: firestore.rules still contains the sentinel '${SENTINEL}'." >&2
  echo "Replace it with the real operator UID before deploying." >&2
  exit 1
fi

echo "check-allowlist-uid: sentinel not found — rules are safe to deploy."

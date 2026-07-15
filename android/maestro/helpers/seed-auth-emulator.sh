#!/usr/bin/env bash
# Seed the fixture verification user into the local Firebase Auth emulator,
# with the uid pinned to the operator allowlist literal in
# backend/firestore.rules — seeded flows must pass the PRODUCTION security
# rules, and those gate on both the allowlist and path ownership.
# The app's debug-only fixture sign-in broadcast uses these credentials
# (debug builds only; the credentials are meaningless outside a local
# emulator). Requires the Auth emulator to be running:
#   (from backend/) firebase emulators:start --only auth,firestore --project playster-dev
set -euo pipefail

HELPER_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

export FIREBASE_AUTH_EMULATOR_HOST="${AUTH_EMULATOR:-${FIREBASE_AUTH_EMULATOR_HOST:-127.0.0.1:9099}}"
node "${HELPER_DIR}/create-fixture-user.js"

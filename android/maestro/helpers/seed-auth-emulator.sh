#!/usr/bin/env bash
# Seed the fixture verification user into the local Firebase Auth emulator.
# The app's debug-only fixture sign-in broadcast uses these credentials
# (debug builds only; the credentials are meaningless outside a local
# emulator). Requires the Auth emulator to be running:
#   firebase emulators:start --only auth,firestore --project playster-dev
set -euo pipefail

AUTH_EMULATOR="${AUTH_EMULATOR:-127.0.0.1:9099}"
EMAIL="verify@playster.test"
PASSWORD="playster-verify-fixture"

RESPONSE="$(curl -sS -X POST \
  "http://${AUTH_EMULATOR}/identitytoolkit.googleapis.com/v1/accounts:signUp?key=fixture-key" \
  -H 'Content-Type: application/json' \
  -d "{\"email\":\"${EMAIL}\",\"password\":\"${PASSWORD}\",\"returnSecureToken\":true}")"

if echo "${RESPONSE}" | grep -q '"idToken"'; then
  echo "Seeded ${EMAIL} in the Auth emulator at ${AUTH_EMULATOR}."
elif echo "${RESPONSE}" | grep -q 'EMAIL_EXISTS'; then
  echo "${EMAIL} already present in the Auth emulator - nothing to do."
else
  echo "Unexpected Auth emulator response:" >&2
  echo "${RESPONSE}" >&2
  exit 1
fi

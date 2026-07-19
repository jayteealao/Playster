#!/usr/bin/env bash
# Seed the local emulator suite with the editorial verification corpus:
# fixture user (uid pinned to the rules allowlist), mock-shaped playlists +
# videos, progress spread, notes, highlights, transcripts (incl. the
# 2,000-paragraph stressor), a chapters-bearing summary, a
# description-chapters video, and a no-transcript video. Fixture ids:
# android/maestro/fixtures/editorial-fixtures.json.
#
# Requires a RUNNING emulator suite (it must outlive this script so the app
# can drive against it):
#   (from backend/) firebase emulators:start --only auth,firestore --project playster-406121
#
# Ports/hosts: an already-exported FIREBASE_AUTH_EMULATOR_HOST /
# FIRESTORE_EMULATOR_HOST wins (emulators:exec sets both), then the
# AUTH_EMULATOR / FIRESTORE_EMULATOR overrides, then the suite defaults.
set -euo pipefail

HELPER_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

export FIREBASE_AUTH_EMULATOR_HOST="${AUTH_EMULATOR:-${FIREBASE_AUTH_EMULATOR_HOST:-127.0.0.1:9099}}"
export FIRESTORE_EMULATOR_HOST="${FIRESTORE_EMULATOR:-${FIRESTORE_EMULATOR_HOST:-127.0.0.1:8080}}"

node "${HELPER_DIR}/create-fixture-user.js"
node "${HELPER_DIR}/write-editorial-corpus.js"
echo "seed-editorial-corpus OK (auth=${FIREBASE_AUTH_EMULATOR_HOST}, firestore=${FIRESTORE_EMULATOR_HOST})"

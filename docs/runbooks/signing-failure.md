# Runbook: signing-failure

## When this fires

CI logs matching any of the following patterns trigger this runbook:

- `(?i)keystore`
- `(?i)SIGNING_STORE`
- `(?i)failed to (read|load) key`
- `(?i)jarsigner`

## Steps

1. Confirm SIGNING_STORE_FILE_B64 / SIGNING_STORE_PASSWORD / SIGNING_KEY_ALIAS / SIGNING_KEY_PASSWORD are all set in repo secrets.
2. Re-encode the keystore: `base64 -w0 keystore.jks` (no trailing newline) and update SIGNING_STORE_FILE_B64.
3. Verify the alias matches: `keytool -list -keystore keystore.jks -alias <SIGNING_KEY_ALIAS>`.
4. Re-run the release; the "check for signing secrets" step gates the decode.

## Notes

_Seeded from ship plan `recovery-playbooks[signing-failure]`. Update this file as the playbook evolves._
_Last synced from plan version: 1_

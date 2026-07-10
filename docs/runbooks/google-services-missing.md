# Runbook: google-services-missing

## When this fires

CI logs matching any of the following patterns trigger this runbook:

- `(?i)google-services\.json`
- `(?i)File google-services\.json is missing`
- `(?i)GOOGLE_SERVICES_JSON_B64 is empty`

## Steps

1. Confirm GOOGLE_SERVICES_JSON_B64 is set and non-empty.
2. Re-encode: `base64 -w0 app/google-services.json`; the workflow strips CR/LF/tabs/spaces before decoding.
3. Validate locally: `echo "$B64" | tr -d '\r\n\t ' | base64 -d | jq type`.

## Notes

_Seeded from ship plan `recovery-playbooks[google-services-missing]`. Update this file as the playbook evolves._
_Last synced from plan version: 1_

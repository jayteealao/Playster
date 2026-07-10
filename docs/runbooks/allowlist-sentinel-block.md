# Runbook: allowlist-sentinel-block

## When this fires

CI logs matching any of the following patterns trigger this runbook:

- `(?i)__BOOTSTRAP_UID__`
- `(?i)check-allowlist-uid`
- `(?i)allowlist`

## Steps

1. `backend/scripts/check-allowlist-uid.sh` blocks the prod Firestore-rules deploy while the `__BOOTSTRAP_UID__` sentinel is still present in `firestore.rules`.
2. Follow `docs/operations/bootstrap-allowlisted-uid.md`: capture the real signed-in uid, replace the sentinel in `backend/firestore.rules`.
3. Re-deploy rules: `cd backend && firebase deploy --only firestore:rules --project <GCP_PROJECT_ID>`.

## Notes

_Seeded from ship plan `recovery-playbooks[allowlist-sentinel-block]`. Update this file as the playbook evolves._
_Last synced from plan version: 1_

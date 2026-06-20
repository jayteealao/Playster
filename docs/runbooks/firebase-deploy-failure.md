# Runbook: firebase-deploy-failure

## When this fires

CI logs matching any of the following patterns trigger this runbook:

- `(?i)firebase deploy`
- `(?i)functions.*deploy.*fail`
- `(?i)HTTP Error: 4\d\d`
- `(?i)predeploy`

## Steps

1. Check OIDC auth: `google-github-actions/auth` resolved GCP_WORKLOAD_IDENTITY_PROVIDER + GCP_SERVICE_ACCOUNT.
2. Confirm the predeploy hook passed: `pnpm --prefix functions run lint && run build`.
3. Verify the service account holds `roles/cloudfunctions.admin` + `roles/iam.serviceAccountUser`; check region/quota.

## Notes

_Seeded from ship plan `recovery-playbooks[firebase-deploy-failure]`. Update this file as the playbook evolves._
_Last synced from plan version: 1_

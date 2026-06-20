# Runbook: cloud-run-deploy-failure

## When this fires

CI logs matching any of the following patterns trigger this runbook:

- `(?i)Cloud Run`
- `(?i)Revision .* failed`
- `(?i)PERMISSION_DENIED`
- `(?i)artifact ?registry`
- `(?i)--set-secrets`

## Steps

1. Confirm the image built and pushed to Artifact Registry (repo `playster` in `<REGION>`).
2. Verify Secret Manager bindings exist for SUMMARIZE_TOKEN / SUMMARIZER_API_KEY / OPENROUTER_API_KEY / GROQ_API_KEY and the runtime SA has `roles/secretmanager.secretAccessor`.
3. Check the service account holds `roles/run.admin` + `roles/artifactregistry.admin`; inspect the failed revision logs in Cloud Run.

## Notes

_Seeded from ship plan `recovery-playbooks[cloud-run-deploy-failure]`. Update this file as the playbook evolves._
_Last synced from plan version: 1_

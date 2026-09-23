#!/usr/bin/env bash
# One-time GCP setup for clickup-management on Cloud Run (project octo-agents).
# Safe to re-run: every step skips what already exists.
#
# Requires: gcloud logged in with project owner/admin rights, and CLICKUP_CLIENT_SECRET in the environment
# (e.g. `set -a; source .env; set +a` before running).
set -euo pipefail

PROJECT=octo-agents
PROJECT_NUMBER=182906449104
REGION=europe-west1
SERVICE=clickup-management
WORKSPACE_ID=90152153739
GITHUB_REPO=pm-guerra/clickup-management
POOL=github-pool
PROVIDER=github-provider

RUNTIME_SA="${SERVICE}-run@${PROJECT}.iam.gserviceaccount.com"
DEPLOYER_SA="${SERVICE}-deployer@${PROJECT}.iam.gserviceaccount.com"
TOKEN_SECRET="clickup-oauth-token-${WORKSPACE_ID}"
WEBHOOK_SECRET="clickup-webhook-${WORKSPACE_ID}"

: "${CLICKUP_CLIENT_SECRET:?CLICKUP_CLIENT_SECRET must be set}"

echo "==> Enabling APIs"
gcloud services enable run.googleapis.com secretmanager.googleapis.com --project "$PROJECT"

echo "==> Service accounts"
for sa in "${SERVICE}-run" "${SERVICE}-deployer"; do
  gcloud iam service-accounts describe "${sa}@${PROJECT}.iam.gserviceaccount.com" --project "$PROJECT" >/dev/null 2>&1 \
    || gcloud iam service-accounts create "$sa" --project "$PROJECT" --display-name "$sa"
done

create_secret() {
  gcloud secrets describe "$1" --project "$PROJECT" >/dev/null 2>&1 \
    || gcloud secrets create "$1" --project "$PROJECT" --replication-policy=user-managed --locations="$REGION"
}

has_versions() {
  [ -n "$(gcloud secrets versions list "$1" --project "$PROJECT" --limit=1 --format='value(name)')" ]
}

echo "==> Secrets"
for s in clickup-client-secret clickup-admin-api-key "$TOKEN_SECRET" "$WEBHOOK_SECRET"; do
  create_secret "$s"
done
has_versions clickup-client-secret \
  || printf '%s' "$CLICKUP_CLIENT_SECRET" | gcloud secrets versions add clickup-client-secret --project "$PROJECT" --data-file=-
has_versions clickup-admin-api-key \
  || openssl rand -hex 24 | tr -d '\r\n' | gcloud secrets versions add clickup-admin-api-key --project "$PROJECT" --data-file=-

echo "==> Runtime service account permissions"
for s in clickup-client-secret clickup-admin-api-key "$TOKEN_SECRET" "$WEBHOOK_SECRET"; do
  gcloud secrets add-iam-policy-binding "$s" --project "$PROJECT" \
    --member "serviceAccount:${RUNTIME_SA}" --role roles/secretmanager.secretAccessor --condition=None >/dev/null
done
# The app writes new versions of these two (after OAuth and webhook registration), nothing else.
for s in "$TOKEN_SECRET" "$WEBHOOK_SECRET"; do
  gcloud secrets add-iam-policy-binding "$s" --project "$PROJECT" \
    --member "serviceAccount:${RUNTIME_SA}" --role roles/secretmanager.secretVersionAdder --condition=None >/dev/null
done

echo "==> Deployer service account permissions"
gcloud projects add-iam-policy-binding "$PROJECT" \
  --member "serviceAccount:${DEPLOYER_SA}" --role roles/run.admin --condition=None >/dev/null
gcloud artifacts repositories add-iam-policy-binding containers --project "$PROJECT" --location "$REGION" \
  --member "serviceAccount:${DEPLOYER_SA}" --role roles/artifactregistry.writer >/dev/null
gcloud iam service-accounts add-iam-policy-binding "$RUNTIME_SA" --project "$PROJECT" \
  --member "serviceAccount:${DEPLOYER_SA}" --role roles/iam.serviceAccountUser >/dev/null

echo "==> GitHub Workload Identity Federation"
CONDITION=$(gcloud iam workload-identity-pools providers describe "$PROVIDER" \
  --workload-identity-pool "$POOL" --location global --project "$PROJECT" --format='value(attributeCondition)')
if [[ "$CONDITION" != *"'${GITHUB_REPO}'"* ]]; then
  gcloud iam workload-identity-pools providers update-oidc "$PROVIDER" \
    --workload-identity-pool "$POOL" --location global --project "$PROJECT" \
    --attribute-condition "${CONDITION} || assertion.repository=='${GITHUB_REPO}'"
fi
gcloud iam service-accounts add-iam-policy-binding "$DEPLOYER_SA" --project "$PROJECT" \
  --role roles/iam.workloadIdentityUser \
  --member "principalSet://iam.googleapis.com/projects/${PROJECT_NUMBER}/locations/global/workloadIdentityPools/${POOL}/attribute.repository/${GITHUB_REPO}" >/dev/null

echo "==> Done. Push to master (or run the Deploy workflow) to deploy."

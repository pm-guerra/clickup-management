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
SQL_INSTANCE="${SERVICE}-db"
DB_NAME=clickup
DB_USER=clickup
SERVICE_URL="https://${SERVICE}-${PROJECT_NUMBER}.${REGION}.run.app"

RUNTIME_SA="${SERVICE}-run@${PROJECT}.iam.gserviceaccount.com"
DEPLOYER_SA="${SERVICE}-deployer@${PROJECT}.iam.gserviceaccount.com"
TOKEN_SECRET="clickup-oauth-token-${WORKSPACE_ID}"
WEBHOOK_SECRET="clickup-webhook-${WORKSPACE_ID}"

: "${CLICKUP_CLIENT_SECRET:?CLICKUP_CLIENT_SECRET must be set}"

# Strips CR/LF: on Windows (Git Bash) tools emit CRLF, which would end up inside secrets.
clean() { tr -d '\r\n'; }

echo "==> Enabling APIs"
gcloud services enable run.googleapis.com secretmanager.googleapis.com sqladmin.googleapis.com \
  cloudscheduler.googleapis.com --project "$PROJECT"

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
  [ -n "$(gcloud secrets versions list "$1" --project "$PROJECT" --filter='state=ENABLED' --limit=1 --format='value(name)')" ]
}

echo "==> Secrets"
for s in clickup-client-secret clickup-admin-api-key clickup-db-password "$TOKEN_SECRET" "$WEBHOOK_SECRET"; do
  create_secret "$s"
done
has_versions clickup-client-secret \
  || printf '%s' "$CLICKUP_CLIENT_SECRET" | clean | gcloud secrets versions add clickup-client-secret --project "$PROJECT" --data-file=-
has_versions clickup-admin-api-key \
  || openssl rand -hex 24 | clean | gcloud secrets versions add clickup-admin-api-key --project "$PROJECT" --data-file=-
has_versions clickup-db-password \
  || openssl rand -base64 30 | tr -d '\r\n/+=' | gcloud secrets versions add clickup-db-password --project "$PROJECT" --data-file=-

echo "==> Cloud SQL (Postgres)"
gcloud sql instances describe "$SQL_INSTANCE" --project "$PROJECT" >/dev/null 2>&1 \
  || gcloud sql instances create "$SQL_INSTANCE" --project "$PROJECT" --region "$REGION" \
       --database-version POSTGRES_17 --edition ENTERPRISE --tier db-f1-micro \
       --storage-type SSD --storage-size 10 --storage-auto-increase \
       --backup-start-time 03:00 --deletion-protection
gcloud sql databases describe "$DB_NAME" --instance "$SQL_INSTANCE" --project "$PROJECT" >/dev/null 2>&1 \
  || gcloud sql databases create "$DB_NAME" --instance "$SQL_INSTANCE" --project "$PROJECT"
DB_PASSWORD=$(gcloud secrets versions access latest --secret clickup-db-password --project "$PROJECT" | clean)
if gcloud sql users list --instance "$SQL_INSTANCE" --project "$PROJECT" --format='value(name)' | tr -d '\r' | grep -qx "$DB_USER"; then
  gcloud sql users set-password "$DB_USER" --instance "$SQL_INSTANCE" --project "$PROJECT" --password "$DB_PASSWORD" >/dev/null
else
  gcloud sql users create "$DB_USER" --instance "$SQL_INSTANCE" --project "$PROJECT" --password "$DB_PASSWORD"
fi

echo "==> Runtime service account permissions"
gcloud projects add-iam-policy-binding "$PROJECT" \
  --member "serviceAccount:${RUNTIME_SA}" --role roles/cloudsql.client --condition=None >/dev/null
for s in clickup-client-secret clickup-admin-api-key clickup-db-password "$TOKEN_SECRET" "$WEBHOOK_SECRET"; do
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
  --workload-identity-pool "$POOL" --location global --project "$PROJECT" --format='value(attributeCondition)' | tr -d '\r')
if [[ "$CONDITION" != *"'${GITHUB_REPO}'"* ]]; then
  gcloud iam workload-identity-pools providers update-oidc "$PROVIDER" \
    --workload-identity-pool "$POOL" --location global --project "$PROJECT" \
    --attribute-condition "${CONDITION} || assertion.repository=='${GITHUB_REPO}'"
fi
gcloud iam service-accounts add-iam-policy-binding "$DEPLOYER_SA" --project "$PROJECT" \
  --role roles/iam.workloadIdentityUser \
  --member "principalSet://iam.googleapis.com/projects/${PROJECT_NUMBER}/locations/global/workloadIdentityPools/${POOL}/attribute.repository/${GITHUB_REPO}" >/dev/null

echo "==> Cloud Scheduler: process due/failed webhook events every minute"
ADMIN_KEY=$(gcloud secrets versions access latest --secret clickup-admin-api-key --project "$PROJECT" | clean)
SCHEDULER_ARGS=(--project "$PROJECT" --location "$REGION" --schedule "* * * * *"
  --uri "${SERVICE_URL}/admin/events/process-due" --http-method POST --message-body "{}"
  --attempt-deadline 300s)
if gcloud scheduler jobs describe "${SERVICE}-retry" --project "$PROJECT" --location "$REGION" >/dev/null 2>&1; then
  gcloud scheduler jobs update http "${SERVICE}-retry" "${SCHEDULER_ARGS[@]}" \
    --update-headers "X-Admin-Key=${ADMIN_KEY},Content-Type=application/json" >/dev/null
else
  gcloud scheduler jobs create http "${SERVICE}-retry" "${SCHEDULER_ARGS[@]}" \
    --headers "X-Admin-Key=${ADMIN_KEY},Content-Type=application/json" >/dev/null
fi

echo "==> Done. Push to master (or run the Deploy workflow) to deploy."

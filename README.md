# clickup-management

Java 25 + Spring Boot 4 service that integrates with ClickUp via OAuth and webhooks, and runs task automations
(copying fields to subtasks, changing status/assignees, …) in response to ClickUp events.

## How it works

```
POST /clickup/webhook
  -> signature verification (HMAC-SHA256 of the raw body with the webhook secret, X-Signature header)
  -> normalize payload into a ClickUpEvent
  -> store in webhook_event (unique idempotency key webhook_id:history_item_id, so duplicates are dropped)
  -> immediate processing attempt: EventDispatcher -> EventHandler (business workflow) -> ClickUpClient
  -> respond 200 (once stored, failures are ours to retry, not ClickUp's)

POST /admin/events/process-due   (Cloud Scheduler, every minute; @Scheduled locally)
  -> events that are due (FAILED with elapsed backoff, or stuck PROCESSING) are processed again
```

### Event inbox and retries

Every verified delivery is a row in `webhook_event` with a status:

| Status       | Meaning                                                                                  |
|--------------|------------------------------------------------------------------------------------------|
| `PENDING`    | Stored, not processed yet                                                                |
| `PROCESSING` | Claimed by a worker; if its lock (5 min) expires, it's picked up again                   |
| `SUCCEEDED`  | Done; pruned after 30 days                                                               |
| `FAILED`     | Attempt failed; retried at `next_attempt_at` (1 min, doubling, capped at 6 h)            |
| `DEAD`       | Gave up: non-retryable error (ClickUp 4xx other than 408/429) or 8 attempts reached      |

`last_error` holds only the exception type and, for ClickUp errors, status + ECODE (no task data).
Handlers must be idempotent, because a retry re-runs the whole handler.

Admin (all need `X-Admin-Key`): `GET /admin/events?status=FAILED|DEAD|...`, `GET /admin/events/{id}`,
`POST /admin/events/{id}/retry` (requeue a FAILED/DEAD event with a fresh attempt budget and run it now),
`POST /admin/events/process-due`.

| Package    | Responsibility                                                                       |
|------------|--------------------------------------------------------------------------------------|
| `oauth`    | `/clickup/oauth/start` + `/callback`, one-time `state` values                         |
| `secret`   | `SecretStore`: GCP Secret Manager when deployed, AES-GCM encrypted files locally     |
| `token`    | `AccessTokenStore` abstraction over `SecretStore`                                    |
| `client`   | `ClickUpClient` (tasks, custom fields, webhooks), timeouts, error mapping, 429 retry |
| `webhook`  | Receiver, signature check, normalization, dispatch, webhook admin API                |
| `event`    | Event inbox (`webhook_event`), processor, retry policy/job, event admin API          |
| `workflow` | Business rules (`EventHandler` implementations)                                      |

### Workflow: tag -> subtask (`TagSubtaskWorkflow`)

On `taskTagUpdated`, for each tag *added* that matches a rule in `clickup.workflows.tag-subtasks.rules`, creates a
subtask under the tagged task (any task type) in one API call. Current rule, `backend`:

- title `Backend | <parent name>`, status `to do`, tag `backend`, default task type
- `Pre Go Live` and priority inherited from the parent (whatever values it has, including none)
- `maintenance` = true **only if the parent's task type is Bug or Change**; otherwise left unset. Type names are
  resolved from the workspace's custom task types (cached; reloaded when an unknown type id appears)

Guardrails:
- removing a tag does nothing;
- if the parent already has a subtask tagged `backend` (or titled `Backend | <name>`), nothing is created, so
  re-adding the tag and retries don't duplicate it;
- tasks whose name already starts with `Backend | ` are skipped, so the generated subtask never spawns another.

Custom fields are matched **by name** on the parent's list (they exist on the Product list); a field that isn't on
the list is skipped with a warning. Add more rules (e.g. `frontend`) in `application.yml`.

The webhook subscribes to exactly the events the enabled workflows handle; after enabling/disabling a workflow,
re-register it with `POST /admin/clickup/webhook`.

### Workflow: copy parent Bug fields to new subtasks (disabled until mappings are configured)

`CopyParentFieldsWorkflow` handles `taskCreated`: fetches the new task, its parent (optionally only if the parent is
the "Bug" custom task type), and copies the fields configured in `CLICKUP_CUSTOM_FIELD_MAPPINGS`.

**Loop protection:** the workflow only subscribes to `taskCreated`. The custom-field writes produce `taskUpdated`
events, which nothing listens to. Writes are also skipped when the child already has the value, and idempotency records stop
duplicate deliveries. If you add a handler for `taskUpdated`, make it ignore history items for fields it manages.

## Configuration

All config is via environment variables (see [.env.example](.env.example)). For local runs, put them in a
git-ignored `.env` file at the repo root; it is loaded automatically.

| Variable                        | Notes                                                                 |
|---------------------------------|-----------------------------------------------------------------------|
| `CLICKUP_CLIENT_ID` / `_SECRET` | From the ClickUp OAuth app                                            |
| `CLICKUP_WORKSPACE_ID`          | The number after `app.clickup.com/` in your ClickUp URL               |
| `APP_BASE_URL`                  | Public URL of this service; the webhook endpoint is derived from it   |
| `CLICKUP_REDIRECT_URI`          | Defaults to `${APP_BASE_URL}/clickup/oauth/callback`                  |
| `TOKEN_ENCRYPTION_KEY`          | Local only: `openssl rand -base64 32`; encrypts `./data/secrets`      |
| `SECRET_STORE` / `GCP_PROJECT_ID` | `local` (default) or `gcp` (Secret Manager in that project)         |
| `ADMIN_API_KEY`                 | Sent as `X-Admin-Key` to `/admin/**`                                  |
| `CLICKUP_CUSTOM_FIELD_MAPPINGS` | JSON `{"childFieldId":"parentFieldId"}`                               |
| `CLICKUP_BUG_CUSTOM_ITEM_ID`    | Optional: only treat parents of this custom task type as Bugs         |

## Running locally

Locally the database is an H2 file in `./data` (PostgreSQL mode), so nothing needs installing; on Cloud Run
it's Cloud SQL Postgres. The OAuth token and webhook secret live in the `SecretStore`: encrypted files in
`./data/secrets` locally, Secret Manager on Cloud Run. OAuth `state` is in memory, so Cloud Run runs one instance.

```bash
./mvnw spring-boot:run
```

1. Register the redirect URL in the ClickUp OAuth app (e.g. `http://localhost:8080/clickup/oauth/callback`).
2. Open http://localhost:8080/clickup/oauth/start in a browser and authorize the workspace.
3. ClickUp must be able to reach the webhook, so expose the app publicly (e.g. `ngrok http 8080`), set
   `APP_BASE_URL` to that URL, restart, then register the webhook:

```bash
curl -X POST -H "X-Admin-Key: $ADMIN_API_KEY" http://localhost:8080/admin/clickup/webhook
```

`POST` (re-)registers, replacing any previous webhook. Also: `GET` and `DELETE` on `/admin/clickup/webhook`.

## Tests

```bash
./mvnw test
```

## Deployment (Cloud Run, project `octo-agents`)

Service URL: https://clickup-management-182906449104.europe-west1.run.app

- **One-time setup:** `set -a; source .env; set +a; bash deploy/gcp-setup.sh`. This enables the APIs, creates
  the service accounts, secrets, Cloud SQL instance (`clickup-management-db`, Postgres 17, db-f1-micro) with
  database/user, the Cloud Scheduler retry job, and lets this repo deploy via Workload Identity Federation.
- **Deploy:** push to `master` (or run the *Deploy* workflow). GitHub Actions tests, builds the Dockerfile,
  pushes to Artifact Registry and deploys to Cloud Run.
- **Config:** non-secret env vars in [deploy/cloudrun-env.yaml](deploy/cloudrun-env.yaml); secrets in Secret Manager
  (`clickup-client-secret`, `clickup-admin-api-key`, `clickup-db-password`, `clickup-oauth-token-<ws>`,
  `clickup-webhook-<ws>`). Cloud Run reaches Cloud SQL through the Cloud SQL Java connector (no public IP allow-list).
- **Rotating the admin key:** add a secret version, redeploy, and re-run the setup script so the Cloud Scheduler
  job gets the new header.
- **Admin key:** `gcloud secrets versions access latest --secret clickup-admin-api-key --project octo-agents`
- Admin POSTs need a body (Google's front end rejects POST without `Content-Length`): `curl -X POST -d '' -H "X-Admin-Key: $KEY" ...`

After the first deploy: add `<service URL>/clickup/oauth/callback` as a redirect URL in the ClickUp app, open
`<service URL>/clickup/oauth/start`, then `POST <service URL>/admin/clickup/webhook` with the admin key.

## Compliance

HIPAA: never log names, emails, task names/descriptions or custom field values. Logs carry IDs, event
types and status codes only; ClickUp error bodies are reduced to their `ECODE`.

# clickup-management

Java 25 + Spring Boot 4 service that integrates with ClickUp via OAuth and webhooks, and runs task automations
(copying fields to subtasks, changing status/assignees, …) in response to ClickUp events.

## How it works

```
POST /clickup/webhook
  -> signature verification (HMAC-SHA256 of the raw body with the webhook secret, X-Signature header)
  -> normalize payload into a ClickUpEvent
  -> idempotency check (webhook_id:history_item_id, persisted in processed_event)
  -> EventDispatcher -> EventHandler (business workflow)
  -> ClickUpClient (all ClickUp HTTP goes through here)
```

| Package    | Responsibility                                                                       |
|------------|--------------------------------------------------------------------------------------|
| `oauth`    | `/clickup/oauth/start` + `/callback`, one-time `state` values                         |
| `token`    | `AccessTokenStore` abstraction; JDBC impl encrypts tokens with AES-GCM               |
| `client`   | `ClickUpClient` (tasks, custom fields, webhooks), timeouts, error mapping, 429 retry |
| `webhook`  | Receiver, signature check, normalization, idempotency, dispatch, webhook admin API   |
| `workflow` | Business rules (`EventHandler` implementations)                                      |

### First workflow: copy parent Bug fields to new subtasks

`CopyParentFieldsWorkflow` handles `taskCreated`: fetches the new task, its parent (optionally only if the parent is
the "Bug" custom task type), and copies the fields configured in `CLICKUP_CUSTOM_FIELD_MAPPINGS`.

**Loop protection:** the workflow only subscribes to `taskCreated`, and the webhook only registers the events
listed in `clickup.webhook-events`. The custom-field writes produce `taskUpdated` events, which nothing
listens to. Writes are also skipped when the child already has the value, and idempotency records stop
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
| `TOKEN_ENCRYPTION_KEY`          | `openssl rand -base64 32`; encrypts tokens and webhook secrets at rest |
| `ADMIN_API_KEY`                 | Sent as `X-Admin-Key` to `/admin/**`                                  |
| `CLICKUP_CUSTOM_FIELD_MAPPINGS` | JSON `{"childFieldId":"parentFieldId"}`                               |
| `CLICKUP_BUG_CUSTOM_ITEM_ID`    | Optional: only treat parents of this custom task type as Bugs         |
| `PGHOST` `PGPORT` `PGDATABASE` `PGUSER` `PGPASSWORD` | Postgres connection (`postgres` profile only)    |

## Running locally

By default the app uses an H2 file database in `./data`, so no database setup is needed:

```bash
./mvnw spring-boot:run
```

Deployed environments set `SPRING_PROFILES_ACTIVE=postgres` and the `PG*` variables.

1. Register the redirect URL in the ClickUp OAuth app (e.g. `http://localhost:8080/clickup/oauth/callback`).
2. Open http://localhost:8080/clickup/oauth/start in a browser and authorize the workspace.
3. ClickUp must be able to reach the webhook, so expose the app publicly (e.g. `ngrok http 8080`), set
   `APP_BASE_URL` to that URL, restart, then register the webhook:

```bash
curl -X POST -H "X-Admin-Key: $ADMIN_API_KEY" http://localhost:8080/admin/clickup/webhooks
```

Other admin endpoints: `GET /admin/clickup/webhooks`, `DELETE /admin/clickup/webhooks/{id}`.

## Tests

```bash
./mvnw test
```

## Deployment

A `Dockerfile` is included. The target is GCP (same setup as `octo-agents`: GitHub Actions + Workload Identity
Federation); not wired up yet. The app reads `PORT`, exposes `/actuator/health/liveness` and `/readiness`.

## Compliance

HIPAA: never log names, emails, task names/descriptions or custom field values. Logs carry IDs, event
types and status codes only; ClickUp error bodies are reduced to their `ECODE`.

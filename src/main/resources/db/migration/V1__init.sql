-- OAuth access tokens, one per ClickUp Workspace. Token is AES-GCM encrypted.
create table clickup_token (
    workspace_id           varchar(64)              primary key,
    encrypted_access_token text                     not null,
    authorized_user_id     varchar(64),
    created_at             timestamp with time zone not null,
    updated_at             timestamp with time zone not null
);

-- Pending OAuth "state" values (CSRF protection), consumed on callback.
create table oauth_state (
    state      varchar(128)             primary key,
    created_at timestamp with time zone not null
);

-- Webhooks registered with ClickUp. Secret is AES-GCM encrypted.
create table webhook_registration (
    webhook_id       varchar(64)              primary key,
    workspace_id     varchar(64)              not null,
    endpoint         varchar(1024)            not null,
    events           varchar(1024)            not null,
    encrypted_secret text                     not null,
    created_at       timestamp with time zone not null
);

-- Idempotency records for processed webhook deliveries.
create table processed_event (
    idempotency_key varchar(255)             primary key,
    webhook_id      varchar(64)              not null,
    event           varchar(64)              not null,
    task_id         varchar(64),
    received_at     timestamp with time zone not null,
    completed_at    timestamp with time zone
);

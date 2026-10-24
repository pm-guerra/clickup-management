-- Sends of a mention to the agent that failed so far; it's retried until max-attempts.
alter table octo_mention add column forward_attempts integer not null default 0;

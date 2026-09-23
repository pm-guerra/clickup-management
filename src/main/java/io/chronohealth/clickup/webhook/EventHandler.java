package io.chronohealth.clickup.webhook;

import java.util.Set;

/**
 * A business workflow reacting to ClickUp events. Handlers must be safe to run more than once for the same event
 * (idempotency is best-effort) and must not react to changes they themselves make (loop protection).
 */
public interface EventHandler {

    /**
     * ClickUp event types (e.g. {@code taskCreated}) this handler wants. Keep this as narrow as possible.
     */
    Set<String> eventTypes();

    void handle(ClickUpEvent event);
}

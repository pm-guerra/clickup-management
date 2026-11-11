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

    /**
     * True for handlers that react on any list (mentions of the bot); the others only see events for tasks on the
     * managed lists ({@link ListScope}).
     */
    default boolean everyList() {
        return false;
    }

    void handle(ClickUpEvent event);
}

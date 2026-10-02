package io.chronohealth.clickup.audit;

import io.chronohealth.clickup.webhook.ClickUpEvent;
import java.util.List;

/**
 * A check on a change someone made (a stored ClickUp webhook event, which says who changed what), as opposed to an
 * {@link AuditRule}, which checks a task's current state. Each finding is reported once and doesn't stay open.
 */
public interface AuditEventRule {

    /**
     * Stable identifier, stored with findings. Don't rename once deployed.
     */
    String id();

    boolean enabled();

    /**
     * ClickUp event types this rule looks at.
     */
    List<String> eventTypes();

    /**
     * @return one message per wrong change in the event (empty if all changes are allowed)
     */
    List<String> check(ClickUpEvent event, AuditContext context);
}

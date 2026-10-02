package io.chronohealth.clickup.audit;

import io.chronohealth.clickup.client.dto.Task;
import java.util.Optional;

/**
 * One check the hourly audit runs on every task that changed since the previous run.
 * <p>
 * Return a finding when the task breaks the rule, empty when it complies <em>or</em> the rule doesn't apply to it.
 * Empty resolves any previously open finding for this rule and task.
 */
public interface AuditRule {

    /**
     * Stable identifier, stored with findings. Don't rename once deployed.
     */
    String id();

    boolean enabled();

    Optional<String> check(Task task, AuditContext context);
}

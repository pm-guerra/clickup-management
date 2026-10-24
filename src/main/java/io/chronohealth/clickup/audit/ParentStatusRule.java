package io.chronohealth.clickup.audit;

import io.chronohealth.clickup.client.dto.Task;
import io.chronohealth.clickup.client.dto.TaskUpdate;
import io.chronohealth.clickup.workflow.ParentStatusPolicy;
import io.chronohealth.clickup.workflow.ParentStatuses;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Fixes parents whose status is out of step with their subtasks (see {@link ParentStatusPolicy}), e.g. after a
 * missed event, and reports the fix. The real-time workflow normally keeps them in step.
 */
@Component
public class ParentStatusRule implements AuditRule {

    private final ParentStatuses parentStatuses;
    private final AuditProperties.Toggle config;

    public ParentStatusRule(ParentStatuses parentStatuses, AuditProperties properties) {
        this.parentStatuses = parentStatuses;
        this.config = properties.rules().parentStatus();
    }

    @Override
    public String id() {
        return "parent-status";
    }

    @Override
    public boolean enabled() {
        return config.enabled();
    }

    @Override
    public Optional<String> check(Task task, AuditContext context) {
        if (!parentStatuses.manages(task, context)) {
            return Optional.empty();
        }
        Task parent = context.taskWithSubtasks(task.id());
        Optional<String> derived = parentStatuses.derivedStatus(parent, context);
        if (derived.isEmpty()) {
            return Optional.empty();
        }
        String current = parent.status() == null ? "" : parent.status().status();
        Optional<String> target = ParentStatusPolicy.move(current, derived.get());
        if (target.isEmpty()) {
            return Optional.empty();
        }
        context.client().updateTask(parent.id(), TaskUpdate.status(target.get()));
        return Optional.of("Was in '" + current + "' while its subtasks put it at '" + target.get()
                + "'. Moved automatically.");
    }
}

package io.chronohealth.clickup.audit;

import io.chronohealth.clickup.client.dto.Task;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Stream tasks (Task-type subtasks of a Story/Bug/Change, e.g. "Mobile | ...") may only be in to do / in progress /
 * review / complete / waiting info. E.g. a stream task in "ready for testing" is a violation: testing happens on the
 * parent. Bugs/Changes/Stories nested under another task are not stream tasks and aren't checked.
 */
@Component
public class StreamTaskStatusRule implements AuditRule {

    private final AuditProperties.StreamTaskStatus config;

    public StreamTaskStatusRule(AuditProperties properties) {
        this.config = properties.rules().streamTaskStatus();
    }

    @Override
    public String id() {
        return "stream-task-status";
    }

    @Override
    public boolean enabled() {
        return config.enabled();
    }

    @Override
    public Optional<String> check(Task task, AuditContext context) {
        if (task.parent() == null || task.status() == null || task.status().status() == null) {
            return Optional.empty();
        }
        boolean streamType = context.typeName(task).map(t -> contains(config.streamTypesOrEmpty(), t)).orElse(false);
        if (!streamType) {
            return Optional.empty();
        }
        Task parent = context.task(task.parent());
        boolean streamTask = context.typeName(parent).map(t -> contains(config.parentTypesOrEmpty(), t)).orElse(false);
        if (!streamTask) {
            return Optional.empty();
        }
        String status = task.status().status();
        if (contains(config.allowedStatusesOrEmpty(), status)) {
            return Optional.empty();
        }
        return Optional.of("Stream task is in '" + status + "' (allowed: "
                + String.join(", ", config.allowedStatusesOrEmpty()) + ")");
    }

    private static boolean contains(List<String> values, String value) {
        String wanted = value.trim().toLowerCase(Locale.ROOT);
        return values.stream().anyMatch(v -> v.trim().toLowerCase(Locale.ROOT).equals(wanted));
    }
}

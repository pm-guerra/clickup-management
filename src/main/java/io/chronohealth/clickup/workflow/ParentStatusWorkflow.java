package io.chronohealth.clickup.workflow;

import io.chronohealth.clickup.client.ClickUpClient;
import io.chronohealth.clickup.client.ClickUpClientFactory;
import io.chronohealth.clickup.client.dto.Task;
import io.chronohealth.clickup.client.dto.TaskStatus;
import io.chronohealth.clickup.client.dto.TaskUpdate;
import io.chronohealth.clickup.config.ClickUpProperties;
import io.chronohealth.clickup.webhook.ClickUpEvent;
import io.chronohealth.clickup.webhook.EventHandler;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * When a subtask's status changes, moves its parent forward:
 * <ul>
 *   <li>all subtasks in a "done" status (complete) -> parent to the done target (ready for testing);</li>
 *   <li>otherwise, at least one subtask in a "started" status -> parent to the started target (in progress).</li>
 * </ul>
 * Only parents of the configured types (Story, Bug, Change) are touched, and only forward: the parent moves only if
 * the target comes later in the list's status order than its current status, so a parent someone already moved
 * further (review, ready for testing, complete...) is never pulled back.
 * <p>
 * No loop: the parent's own status change produces an event for the parent, which is then evaluated as a subtask of
 * <em>its</em> parent (if any). Changes only ever propagate upwards and stop at the top.
 */
@Component
public class ParentStatusWorkflow implements EventHandler {

    static final String EVENT = "taskStatusUpdated";

    private static final Logger log = LoggerFactory.getLogger(ParentStatusWorkflow.class);

    private final ClickUpClientFactory clientFactory;
    private final TaskTypeResolver taskTypes;
    private final ListStatusResolver listStatuses;
    private final ClickUpProperties.ParentStatus config;

    public ParentStatusWorkflow(ClickUpClientFactory clientFactory, TaskTypeResolver taskTypes,
                                ListStatusResolver listStatuses, ClickUpProperties properties) {
        this.clientFactory = clientFactory;
        this.taskTypes = taskTypes;
        this.listStatuses = listStatuses;
        this.config = properties.workflows().parentStatus();
    }

    @Override
    public Set<String> eventTypes() {
        return config.enabled() ? Set.of(EVENT) : Set.of();
    }

    @Override
    public void handle(ClickUpEvent event) {
        ClickUpClient client = clientFactory.forWorkspace(event.workspaceId());
        Task child = client.getTask(event.taskId());
        if (child.parent() == null) {
            return;
        }

        Task parent = client.getTask(child.parent(), true);
        Optional<String> parentType = taskTypes.typeName(client, event.workspaceId(), parent);
        if (!matchesAny(parentType.orElse(null), config.parentTypesOrEmpty())) {
            log.debug("Parent {} is of type {}; not managed", parent.id(), parentType.orElse("unknown"));
            return;
        }

        Optional<String> target = targetStatus(parent.subtasksOrEmpty());
        if (target.isEmpty()) {
            return;
        }
        String current = statusName(parent.status());
        if (target.get().equalsIgnoreCase(current)) {
            return;
        }

        Optional<Integer> targetOrder = listStatuses.orderOf(client, parent.list().id(), target.get());
        if (targetOrder.isEmpty()) {
            log.warn("Status '{}' doesn't exist on the list of task {}; not updating it", target.get(), parent.id());
            return;
        }
        Integer currentOrder = parent.status() == null ? null : parent.status().orderindex();
        if (currentOrder != null && currentOrder >= targetOrder.get()) {
            log.info("Parent {} is already at '{}', at or past '{}'; leaving it", parent.id(), current, target.get());
            return;
        }

        client.updateTask(parent.id(), TaskUpdate.status(target.get()));
        log.info("Moved parent {} from '{}' to '{}' (subtask {} changed)", parent.id(), current, target.get(), child.id());
    }

    /**
     * All subtasks done -> done target; else any started -> started target; else nothing.
     */
    Optional<String> targetStatus(List<Task> subtasks) {
        if (subtasks.isEmpty()) {
            return Optional.empty();
        }
        Set<String> statuses = subtasks.stream().map(t -> normalize(statusName(t.status()))).collect(Collectors.toSet());
        Set<String> done = normalized(config.doneStatusesOrEmpty());
        if (done.containsAll(statuses)) {
            return Optional.of(config.doneTarget());
        }
        Set<String> started = normalized(config.startedStatusesOrEmpty());
        if (statuses.stream().anyMatch(started::contains)) {
            return Optional.of(config.startedTarget());
        }
        return Optional.empty();
    }

    private static boolean matchesAny(String value, List<String> allowed) {
        return allowed.isEmpty() || (value != null && normalized(allowed).contains(normalize(value)));
    }

    private static String statusName(TaskStatus status) {
        return status == null || status.status() == null ? "" : status.status();
    }

    private static Set<String> normalized(List<String> values) {
        return values.stream().map(ParentStatusWorkflow::normalize).collect(Collectors.toSet());
    }

    private static String normalize(String value) {
        return value.trim().toLowerCase(Locale.ROOT);
    }
}

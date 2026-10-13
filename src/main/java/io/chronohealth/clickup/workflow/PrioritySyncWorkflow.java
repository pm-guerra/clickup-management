package io.chronohealth.clickup.workflow;

import io.chronohealth.clickup.client.ClickUpClient;
import io.chronohealth.clickup.client.ClickUpClientFactory;
import io.chronohealth.clickup.client.dto.Task;
import io.chronohealth.clickup.config.ClickUpProperties;
import io.chronohealth.clickup.webhook.ClickUpEvent;
import io.chronohealth.clickup.webhook.EventHandler;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * While a Story/Bug/Change is still in "to do", a change to its priority is copied to its stream tasks (Task-type
 * subtasks). Once work has started the subtasks keep their own priority.
 * <p>
 * Uses the parent's current priority (cleared too), and only touches subtasks whose priority differs. No loop: the
 * subtasks' own priority events are ignored because they aren't of a parent type.
 */
@Component
public class PrioritySyncWorkflow implements EventHandler {

    static final String EVENT = "taskPriorityUpdated";

    private static final Logger log = LoggerFactory.getLogger(PrioritySyncWorkflow.class);

    private final ClickUpClientFactory clientFactory;
    private final TaskTypeResolver taskTypes;
    private final ClickUpProperties.PrioritySync config;

    public PrioritySyncWorkflow(ClickUpClientFactory clientFactory, TaskTypeResolver taskTypes,
                                ClickUpProperties properties) {
        this.clientFactory = clientFactory;
        this.taskTypes = taskTypes;
        this.config = properties.workflows().prioritySync();
    }

    @Override
    public Set<String> eventTypes() {
        return config.enabled() ? Set.of(EVENT) : Set.of();
    }

    @Override
    public void handle(ClickUpEvent event) {
        ClickUpClient client = clientFactory.forWorkspace(event.workspaceId());
        Task parent = client.getTask(event.taskId(), true);
        Optional<String> type = taskTypes.typeName(client, event.workspaceId(), parent);
        if (!contains(config.parentTypesOrEmpty(), type.orElse(null))) {
            return;
        }
        String status = parent.status() == null ? null : parent.status().status();
        if (!contains(config.whenStatusesOrEmpty(), status)) {
            log.debug("Task {} is in '{}'; not syncing its priority", parent.id(), status);
            return;
        }

        Integer priority = parent.priority() == null ? null : parent.priority().level();
        int updated = 0;
        for (Task subtask : parent.subtasksOrEmpty()) {
            if (!contains(config.subtaskTypesOrEmpty(), taskTypes.typeName(client, event.workspaceId(), subtask).orElse(null))) {
                continue;
            }
            Integer current = subtask.priority() == null ? null : subtask.priority().level();
            if (!Objects.equals(current, priority)) {
                client.setPriority(subtask.id(), priority);
                updated++;
            }
        }
        log.info("Synced priority of {} {} to {} subtask(s)", type.get(), parent.id(), updated);
    }

    private static boolean contains(List<String> values, String value) {
        if (value == null) {
            return false;
        }
        String wanted = value.trim().toLowerCase(Locale.ROOT);
        return values.stream().anyMatch(v -> v.trim().toLowerCase(Locale.ROOT).equals(wanted));
    }
}

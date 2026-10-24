package io.chronohealth.clickup.workflow;

import io.chronohealth.clickup.audit.AuditContext;
import io.chronohealth.clickup.client.ClickUpClient;
import io.chronohealth.clickup.client.ClickUpClientFactory;
import io.chronohealth.clickup.client.dto.Task;
import io.chronohealth.clickup.client.dto.TaskUpdate;
import io.chronohealth.clickup.config.ClickUpProperties;
import io.chronohealth.clickup.webhook.ClickUpEvent;
import io.chronohealth.clickup.webhook.EventHandler;
import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * When a subtask's status changes or a subtask is created, moves its parent to the status its subtasks call for
 * (see {@link ParentStatusPolicy}), forwards or backwards.
 * <p>
 * Works at any depth without looping: the parent's own status change produces an event for the parent, which is then
 * evaluated as a subtask of <em>its</em> parent (if any). Changes only ever propagate upwards and stop at the top.
 */
@Component
public class ParentStatusWorkflow implements EventHandler {

    static final String STATUS_EVENT = "taskStatusUpdated";
    static final String CREATED_EVENT = "taskCreated";

    private static final Logger log = LoggerFactory.getLogger(ParentStatusWorkflow.class);

    private final ClickUpClientFactory clientFactory;
    private final TaskTypeResolver taskTypes;
    private final ListStatusResolver listStatuses;
    private final ParentStatuses parentStatuses;
    private final ClickUpProperties.ParentStatus config;

    public ParentStatusWorkflow(ClickUpClientFactory clientFactory, TaskTypeResolver taskTypes,
                                ListStatusResolver listStatuses, ParentStatuses parentStatuses,
                                ClickUpProperties properties) {
        this.clientFactory = clientFactory;
        this.taskTypes = taskTypes;
        this.listStatuses = listStatuses;
        this.parentStatuses = parentStatuses;
        this.config = properties.workflows().parentStatus();
    }

    @Override
    public Set<String> eventTypes() {
        return config.enabled() ? Set.of(STATUS_EVENT, CREATED_EVENT) : Set.of();
    }

    @Override
    public void handle(ClickUpEvent event) {
        ClickUpClient client = clientFactory.forWorkspace(event.workspaceId());
        Task child = client.getTask(event.taskId());
        if (child.parent() == null) {
            return;
        }

        AuditContext context = new AuditContext(client, event.workspaceId(), taskTypes, Duration.ZERO);
        Task parent = context.taskWithSubtasks(child.parent());
        Optional<String> derived = parentStatuses.derivedStatus(parent, context);
        if (derived.isEmpty()) {
            return;
        }
        String current = parent.status() == null ? "" : parent.status().status();
        Optional<String> target = ParentStatusPolicy.move(current, derived.get());
        if (target.isEmpty()) {
            return;
        }
        if (listStatuses.orderOf(client, parent.list().id(), target.get()).isEmpty()) {
            log.warn("Status '{}' doesn't exist on the list of task {}; not updating it", target.get(), parent.id());
            return;
        }

        client.updateTask(parent.id(), TaskUpdate.status(target.get()));
        log.info("Moved parent {} from '{}' to '{}' (subtask {} {})", parent.id(), current, target.get(), child.id(),
                CREATED_EVENT.equals(event.type()) ? "created" : "changed");
    }
}

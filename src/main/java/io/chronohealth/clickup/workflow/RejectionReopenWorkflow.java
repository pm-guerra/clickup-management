package io.chronohealth.clickup.workflow;

import io.chronohealth.clickup.client.ClickUpClient;
import io.chronohealth.clickup.client.ClickUpClientFactory;
import io.chronohealth.clickup.client.dto.Task;
import io.chronohealth.clickup.client.dto.TaskUpdate;
import io.chronohealth.clickup.config.ClickUpProperties;
import io.chronohealth.clickup.webhook.ClickUpEvent;
import io.chronohealth.clickup.webhook.ClickUpWebhookPayload.HistoryItem;
import io.chronohealth.clickup.webhook.EventHandler;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * When testing rejects a task (a Story/Bug/Change goes from "ready for testing" back to "in progress" or "to do"),
 * reopens its completed subtasks by moving them to "waiting info".
 * <p>
 * The transition is read from the status history item's before/after, so only that exact move triggers it.
 * Idempotent: a retry finds the subtasks already reopened. No loop with {@link ParentStatusWorkflow}: that one only
 * moves parents forward, and "waiting info" isn't a started status.
 */
@Component
public class RejectionReopenWorkflow implements EventHandler {

    static final String EVENT = "taskStatusUpdated";
    private static final String STATUS_FIELD = "status";

    private static final Logger log = LoggerFactory.getLogger(RejectionReopenWorkflow.class);

    private final ClickUpClientFactory clientFactory;
    private final TaskTypeResolver taskTypes;
    private final ClickUpProperties.RejectionReopen config;

    public RejectionReopenWorkflow(ClickUpClientFactory clientFactory, TaskTypeResolver taskTypes,
                                   ClickUpProperties properties) {
        this.clientFactory = clientFactory;
        this.taskTypes = taskTypes;
        this.config = properties.workflows().rejectionReopen();
    }

    @Override
    public Set<String> eventTypes() {
        return config.enabled() ? Set.of(EVENT) : Set.of();
    }

    @Override
    public void handle(ClickUpEvent event) {
        if (!isRejection(event.historyItems())) {
            return;
        }
        ClickUpClient client = clientFactory.forWorkspace(event.workspaceId());
        Task task = client.getTask(event.taskId(), true);
        Optional<String> type = taskTypes.typeName(client, event.workspaceId(), task);
        if (type.isEmpty() || !normalized(config.taskTypesOrEmpty()).contains(normalize(type.get()))) {
            return;
        }

        Set<String> reopen = normalized(config.reopenStatusesOrEmpty());
        List<Task> toReopen = task.subtasksOrEmpty().stream()
                .filter(s -> s.status() != null && s.status().status() != null)
                .filter(s -> reopen.contains(normalize(s.status().status())))
                .toList();
        for (Task subtask : toReopen) {
            client.updateTask(subtask.id(), TaskUpdate.status(config.reopenTo()));
        }
        log.info("{} {} was rejected by testing; moved {} completed subtask(s) to '{}'", type.get(), task.id(),
                toReopen.size(), config.reopenTo());
    }

    /**
     * True if a status history item moves from one of {@code from-statuses} to one of {@code to-statuses}.
     */
    boolean isRejection(List<HistoryItem> historyItems) {
        Set<String> from = normalized(config.fromStatusesOrEmpty());
        Set<String> to = normalized(config.toStatusesOrEmpty());
        return historyItems.stream()
                .filter(item -> STATUS_FIELD.equals(item.field()))
                .anyMatch(item -> statusName(item.before()).map(from::contains).orElse(false)
                        && statusName(item.after()).map(to::contains).orElse(false));
    }

    /**
     * ClickUp sends a status as an object ({@code {"status": "ready for testing", ...}}) or, defensively, a string.
     */
    private static Optional<String> statusName(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return Optional.empty();
        }
        String name = node.isString() ? node.asString() : node.path("status").asString(null);
        return name == null || name.isBlank() ? Optional.empty() : Optional.of(normalize(name));
    }

    private static Set<String> normalized(List<String> values) {
        return values.stream().map(RejectionReopenWorkflow::normalize).collect(Collectors.toSet());
    }

    private static String normalize(String value) {
        return value.trim().toLowerCase(Locale.ROOT);
    }
}

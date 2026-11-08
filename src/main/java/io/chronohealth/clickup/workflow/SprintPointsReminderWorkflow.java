package io.chronohealth.clickup.workflow;

import io.chronohealth.clickup.client.ClickUpClient;
import io.chronohealth.clickup.client.ClickUpClientFactory;
import io.chronohealth.clickup.client.dto.Task;
import io.chronohealth.clickup.config.ClickUpProperties;
import io.chronohealth.clickup.mention.MentionProperties;
import io.chronohealth.clickup.webhook.ClickUpEvent;
import io.chronohealth.clickup.webhook.ClickUpWebhookPayload.HistoryItem;
import io.chronohealth.clickup.webhook.EventHandler;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * When someone moves a Feature, Bug or Change to "to do" and it has no sprint points, they're mentioned in a comment
 * asking them to estimate it. Moves made by the bot itself (a parent following its subtasks) ask nobody.
 * <p>
 * Reads the task as it is now, so points added in the meantime (or a task that already moved on) ask nothing.
 */
@Component
public class SprintPointsReminderWorkflow implements EventHandler {

    static final String EVENT = "taskStatusUpdated";
    private static final String STATUS_FIELD = "status";

    private static final Logger log = LoggerFactory.getLogger(SprintPointsReminderWorkflow.class);

    private final ClickUpClientFactory clientFactory;
    private final TaskTypeResolver taskTypes;
    private final ClickUpProperties.PointsReminder config;
    private final Long botUserId;

    public SprintPointsReminderWorkflow(ClickUpClientFactory clientFactory, TaskTypeResolver taskTypes,
                                        ClickUpProperties properties, MentionProperties mentionProperties) {
        this.clientFactory = clientFactory;
        this.taskTypes = taskTypes;
        this.config = properties.workflows().pointsReminder();
        this.botUserId = mentionProperties.botUserId();
    }

    @Override
    public Set<String> eventTypes() {
        return config.enabled() ? Set.of(EVENT) : Set.of();
    }

    @Override
    public void handle(ClickUpEvent event) {
        Optional<HistoryItem> move = event.historyItems().stream()
                .filter(i -> STATUS_FIELD.equals(i.field()))
                .filter(i -> i.user() != null && i.user().id() != null && !Objects.equals(i.user().id(), botUserId))
                .filter(i -> statusName(i.after()).map(s -> contains(config.statusesOrEmpty(), s)).orElse(false))
                .findFirst();
        if (move.isEmpty()) {
            return;
        }
        ClickUpClient client = clientFactory.forWorkspace(event.workspaceId());
        Task task = client.getTask(event.taskId());
        String status = task.status() == null ? null : task.status().status();
        if (status == null || !contains(config.statusesOrEmpty(), status)
                || (task.points() != null && task.points() > 0)) {
            return;
        }
        Optional<String> type = taskTypes.typeName(client, event.workspaceId(), task);
        if (type.isEmpty() || !contains(config.taskTypesOrEmpty(), type.get())) {
            return;
        }
        long userId = move.get().user().id();
        client.addCommentMentioning(task.id(), userId, "you moved this " + type.get() + " to '" + status.trim()
                + "', but it has no sprint points yet. Please estimate it and add them.");
        log.info("Asked user {} for the sprint points of {} {}", userId, type.get(), task.id());
    }

    /**
     * ClickUp sends a status as an object ({@code {"status": "to do", ...}}) or, defensively, a string.
     */
    private static Optional<String> statusName(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return Optional.empty();
        }
        String name = node.isString() ? node.asString() : node.path("status").asString(null);
        return name == null || name.isBlank() ? Optional.empty() : Optional.of(name);
    }

    private static boolean contains(List<String> values, String value) {
        String wanted = value.trim().toLowerCase(Locale.ROOT);
        return values.stream().anyMatch(v -> v.trim().toLowerCase(Locale.ROOT).equals(wanted));
    }
}

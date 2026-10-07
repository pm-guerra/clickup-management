package io.chronohealth.clickup.audit;

import io.chronohealth.clickup.audit.TaskKinds.Classification;
import io.chronohealth.clickup.audit.TaskKinds.Kind;
import io.chronohealth.clickup.client.dto.Task;
import io.chronohealth.clickup.webhook.ClickUpEvent;
import io.chronohealth.clickup.webhook.ClickUpWebhookPayload.HistoryItem;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Who may change a task's status:
 * <ul>
 *   <li>main tasks (Story/Bug/Change/Epic): testers and admins;</li>
 *   <li>stream tasks and the sub-subtasks inside them: devs of that stream and admins;</li>
 *   <li>other tasks: anyone.</li>
 * </ul>
 * A task's initial status when it's created (no previous status) isn't a move and isn't checked.
 */
@Component
public class StatusMoveRule implements AuditEventRule {

    private static final String STATUS_FIELD = "status";

    private final People people;
    private final TaskKinds kinds;
    private final Permissions permissions;
    private final AuditProperties.Toggle config;

    public StatusMoveRule(People people, TaskKinds kinds, Permissions permissions, AuditProperties properties) {
        this.people = people;
        this.kinds = kinds;
        this.permissions = permissions;
        this.config = properties.rules().statusMove();
    }

    @Override
    public String id() {
        return "status-move";
    }

    @Override
    public boolean enabled() {
        return config.enabled();
    }

    @Override
    public List<String> eventTypes() {
        return List.of("taskStatusUpdated");
    }

    @Override
    public List<String> check(ClickUpEvent event, AuditContext context) {
        List<HistoryItem> moves = event.historyItems().stream()
                .filter(i -> STATUS_FIELD.equals(i.field()))
                .filter(i -> isPresent(i.before()))
                .toList();
        if (moves.isEmpty()) {
            return List.of();
        }
        Task task = context.task(event.taskId());
        Classification kind = kinds.classify(task, context);
        if (kind.kind() == Kind.OTHER) {
            return List.of();
        }
        List<String> findings = new ArrayList<>();
        for (HistoryItem move : moves) {
            Long userId = move.user() == null ? null : move.user().id();
            if (!permissions.canMoveStatus(userId, kind, status(move.after()))) {
                findings.add(people.describe(userId) + " moved it from '" + status(move.before()) + "' to '"
                        + status(move.after()) + "'. " + permissions.whoMayMove(kind)
                        + (people.isEnforced(userId) ? " Undone automatically." : ""));
            }
        }
        return findings;
    }


    static boolean isPresent(JsonNode node) {
        return node != null && !node.isNull() && !node.isMissingNode()
                && !(node.isObject() && node.path("status").isMissingNode());
    }

    static String status(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return "?";
        }
        return node.isString() ? node.asString() : node.path("status").asString("?");
    }
}

package io.chronohealth.clickup.audit;

import io.chronohealth.clickup.audit.People.Role;
import io.chronohealth.clickup.audit.TaskKinds.Classification;
import io.chronohealth.clickup.audit.TaskKinds.Kind;
import io.chronohealth.clickup.client.dto.Task;
import io.chronohealth.clickup.webhook.ClickUpEvent;
import io.chronohealth.clickup.webhook.ClickUpWebhookPayload.HistoryItem;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
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
    private final AuditProperties.Toggle config;

    public StatusMoveRule(People people, TaskKinds kinds, AuditProperties properties) {
        this.people = people;
        this.kinds = kinds;
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
            if (!allowed(userId, kind)) {
                findings.add(people.describe(userId) + " moved it from '" + status(move.before()) + "' to '"
                        + status(move.after()) + "'. " + whoMay(kind));
            }
        }
        return findings;
    }

    private boolean allowed(Long userId, Classification kind) {
        Optional<Role> role = people.role(userId);
        if (role.isEmpty()) {
            return false;
        }
        return switch (role.get()) {
            case ADMIN -> true;
            case TESTER -> kind.kind() == Kind.MAIN;
            case DEV -> kind.kind() == Kind.STREAM
                    && (kind.stream().isEmpty() || kind.stream().equals(people.stream(userId)));
        };
    }

    private static String whoMay(Classification kind) {
        return kind.kind() == Kind.MAIN
                ? "Only testers and admins may move Stories/Bugs/Changes/Epics."
                : "Only " + kind.stream().map(s -> s + " devs").orElse("devs") + " and admins may move this stream task.";
    }

    private static boolean isPresent(JsonNode node) {
        return node != null && !node.isNull() && !node.isMissingNode()
                && !(node.isObject() && node.path("status").isMissingNode());
    }

    private static String status(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return "?";
        }
        return node.isString() ? node.asString() : node.path("status").asString("?");
    }
}

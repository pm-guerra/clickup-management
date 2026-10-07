package io.chronohealth.clickup.audit;

import io.chronohealth.clickup.audit.People.Role;
import io.chronohealth.clickup.audit.TaskKinds.Classification;
import io.chronohealth.clickup.audit.TaskKinds.Kind;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * The permission rules, shared by the hourly report and the real-time enforcement so they never disagree:
 * <ul>
 *   <li>main tasks (Story/Bug/Change/Epic): testers and admins change their status; devs may only move them into
 *       one of {@code dev-main-task-statuses} (e.g. waiting info);</li>
 *   <li>stream tasks (and sub-subtasks in them): devs of that stream and admins;</li>
 *   <li>other tasks: anyone;</li>
 *   <li>stream tags (backend/web/mobile): admins only.</li>
 * </ul>
 */
@Component
public class Permissions {

    private final People people;
    private final Set<String> devMainTaskStatuses;

    public Permissions(People people, AuditProperties properties) {
        this.people = people;
        this.devMainTaskStatuses = properties.devMainTaskStatusesOrEmpty().stream()
                .map(s -> s.trim().toLowerCase(Locale.ROOT)).collect(Collectors.toSet());
    }

    /**
     * @param toStatus the status the task was moved to
     */
    public boolean canMoveStatus(Long userId, Classification kind, String toStatus) {
        if (kind.kind() == Kind.OTHER) {
            return true;
        }
        Optional<Role> role = people.role(userId);
        if (role.isEmpty()) {
            return false;
        }
        return switch (role.get()) {
            case ADMIN -> true;
            case TESTER -> kind.kind() == Kind.MAIN;
            case DEV -> kind.kind() == Kind.STREAM
                    ? kind.stream().isEmpty() || kind.stream().equals(people.stream(userId))
                    : toStatus != null && devMainTaskStatuses.contains(toStatus.trim().toLowerCase(Locale.ROOT));
        };
    }

    public boolean canChangeStreamTags(Long userId) {
        return people.role(userId).map(r -> r == Role.ADMIN).orElse(false);
    }

    public String whoMayMove(Classification kind) {
        return kind.kind() == Kind.MAIN
                ? "Only testers and admins may move Stories/Bugs/Changes/Epics"
                        + (devMainTaskStatuses.isEmpty() ? "." : " (devs may only move them to "
                        + String.join(" / ", devMainTaskStatuses.stream().sorted().toList()) + ").")
                : "Only " + kind.stream().map(s -> s + " devs").orElse("devs") + " and admins may move this stream task.";
    }

    public String whoMayTag() {
        return "Only admins may add or remove stream tags.";
    }
}

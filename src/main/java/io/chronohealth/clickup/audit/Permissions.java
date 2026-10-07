package io.chronohealth.clickup.audit;

import io.chronohealth.clickup.audit.People.Role;
import io.chronohealth.clickup.audit.TaskKinds.Classification;
import io.chronohealth.clickup.audit.TaskKinds.Kind;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * The permission rules, shared by the hourly report and the real-time enforcement so they never disagree:
 * <ul>
 *   <li>main tasks (Story/Bug/Change/Epic): testers and admins change their status;</li>
 *   <li>stream tasks (and sub-subtasks in them): devs of that stream and admins;</li>
 *   <li>other tasks: anyone;</li>
 *   <li>stream tags (backend/web/mobile): admins only.</li>
 * </ul>
 */
@Component
public class Permissions {

    private final People people;

    public Permissions(People people) {
        this.people = people;
    }

    public boolean canMoveStatus(Long userId, Classification kind) {
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
                    && (kind.stream().isEmpty() || kind.stream().equals(people.stream(userId)));
        };
    }

    public boolean canChangeStreamTags(Long userId) {
        return people.role(userId).map(r -> r == Role.ADMIN).orElse(false);
    }

    public String whoMayMove(Classification kind) {
        return kind.kind() == Kind.MAIN
                ? "Only testers and admins may move Stories/Bugs/Changes/Epics."
                : "Only " + kind.stream().map(s -> s + " devs").orElse("devs") + " and admins may move this stream task.";
    }

    public String whoMayTag() {
        return "Only admins may add or remove stream tags.";
    }
}

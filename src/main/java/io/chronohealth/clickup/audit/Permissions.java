package io.chronohealth.clickup.audit;

import io.chronohealth.clickup.audit.TaskKinds.Classification;
import io.chronohealth.clickup.audit.TaskKinds.Kind;
import io.chronohealth.clickup.config.PlanningProperties;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * The permission rules, shared by the hourly report and the real-time enforcement so they never disagree. Admins
 * (Pedro, José, Octo bot) may do everything; everyone else by team:
 * <ul>
 *   <li>Features and Changes (planned work): the Product and Planning teams move them between pending and in lobby,
 *       the Planning team from in lobby to to do, testers from in testing to approved / complete. From to do on they
 *       follow their subtasks, so other moves are for admins only;</li>
 *   <li>other main tasks (Bug, Defect, Epic): testers;</li>
 *   <li>stream tasks (and sub-subtasks in them): the Tech team;</li>
 *   <li>other tasks: anyone;</li>
 *   <li>stream tags (backend/web/mobile): the Planning team;</li>
 *   <li>priority and Product Priority of a task where work has started: admins only.</li>
 * </ul>
 */
@Component
public class Permissions {

    private static final String IN_TESTING = "in testing";
    private static final Set<String> AFTER_TESTING = Set.of("approved", "complete");
    private static final Set<String> TESTED_FROM = Set.of(IN_TESTING, "approved");

    private final People people;
    private final PlanningProperties planning;

    public Permissions(People people, PlanningProperties planning) {
        this.people = people;
        this.planning = planning;
    }

    /**
     * @param from the status it was in
     * @param to   the status it was moved to
     */
    public boolean canMoveStatus(Long userId, Classification kind, String from, String to) {
        if (kind.kind() == Kind.OTHER || people.isAdmin(userId)) {
            return true;
        }
        if (kind.kind() == Kind.STREAM) {
            return people.inTeam(userId, People.TECH);
        }
        if (!planning.isPlanned(kind.type())) {
            return people.inTeam(userId, People.TESTERS);
        }
        return switch (plannedMove(from, to)) {
            case WRITE_UP -> people.inTeam(userId, People.PRODUCT) || people.inTeam(userId, People.PLANNING);
            case PLANNED -> people.inTeam(userId, People.PLANNING);
            case TESTED -> people.inTeam(userId, People.TESTERS);
            case OTHER -> false;
        };
    }

    public boolean canChangeStreamTags(Long userId) {
        return people.isAdmin(userId) || people.inTeam(userId, People.PLANNING);
    }

    public boolean canChangeLockedPriority(Long userId) {
        return people.isAdmin(userId);
    }

    public String whoMayMove(Classification kind, String from, String to) {
        if (kind.kind() == Kind.STREAM) {
            return "Only the Tech team and admins may move stream tasks.";
        }
        if (!planning.isPlanned(kind.type())) {
            return "Only testers and admins may move Bugs, Defects and Epics.";
        }
        return switch (plannedMove(from, to)) {
            case WRITE_UP -> "Only the Product and Planning teams and admins may move a Feature or Change between "
                    + planning.pendingStatus() + " and " + planning.lobbyStatus() + ".";
            case PLANNED -> "Only the Planning team and admins may move a Feature or Change to "
                    + planning.toDoStatus() + ".";
            case TESTED -> "Only testers and admins may move a Feature or Change to approved or complete.";
            case OTHER -> "Only admins may move a Feature or Change from '" + from + "' to '" + to
                    + "': from " + planning.toDoStatus() + " on it follows its subtasks.";
        };
    }

    public String whoMayTag() {
        return "Only the Planning team and admins may add or remove stream tags.";
    }

    public String whoMayChangeLockedPriority() {
        return "Once work has started, only admins may change the priority or Product Priority.";
    }

    private enum PlannedMove { WRITE_UP, PLANNED, TESTED, OTHER }

    private PlannedMove plannedMove(String from, String to) {
        if (planning.isBeforePlanningDone(from) && planning.isBeforePlanningDone(to)) {
            return PlannedMove.WRITE_UP;
        }
        if (planning.isLobby(from) && planning.isToDo(to)) {
            return PlannedMove.PLANNED;
        }
        if (TESTED_FROM.contains(normalize(from)) && AFTER_TESTING.contains(normalize(to))) {
            return PlannedMove.TESTED;
        }
        return PlannedMove.OTHER;
    }

    private static String normalize(String status) {
        return status == null ? "" : status.trim().toLowerCase(Locale.ROOT);
    }
}

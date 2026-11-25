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
 *   <li>other main tasks (Bug, Defect, Epic): testers, and a Bug between pending and in lobby also the
 *       Product team, who write it up there like a Feature;</li>
 *   <li>stream tasks (and sub-subtasks in them): the Tech team;</li>
 *   <li>other tasks: anyone;</li>
 *   <li>stream tags (backend/web/mobile): the Planning team;</li>
 *   <li>plain subtasks under a Feature, Change, Bug or Defect: the Planning team (stream tasks come from the tags);
 *       inside a stream task: the Tech team too. Typed subtasks (a Defect, a nested Bug) aren't limited;</li>
 *   <li>priority and Product Priority of a task where work has started: admins only.</li>
 * </ul>
 */
@Component
public class Permissions {

    private static final String IN_TESTING = "in testing";
    private static final Set<String> AFTER_TESTING = Set.of("approved", "complete");
    private static final Set<String> TESTED_FROM = Set.of(IN_TESTING, "approved");
    private static final String PLAIN = "task";
    private static final Set<String> MANAGED_PARENTS = Set.of("feature", "change", "bug", "defect");
    // Not planned, but written up by the Product team like a Feature, so they move these between pending and in lobby
    private static final Set<String> PRODUCT_WRITE_UP_TYPES = Set.of("bug");

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
            return people.inTeam(userId, People.TESTERS)
                    || (isProductWriteUp(kind, from, to) && people.inTeam(userId, People.PRODUCT));
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

    /**
     * @param parent  the task the subtask was created under
     * @param newType the new subtask's type, lower case ("task", "defect", ...)
     */
    public boolean canCreateSubtask(Long userId, Classification parent, String newType) {
        // Only plain subtasks (would-be stream tasks) under the managed task types, or inside a stream task, are
        // checked: typed ones (a Feature in an Epic, a nested Bug, a Defect), Epics and plain Tasks are left alone.
        boolean managedParent = parent.kind() == Kind.STREAM
                || (parent.kind() == Kind.MAIN && MANAGED_PARENTS.contains(parent.type()));
        if (!PLAIN.equals(newType) || !managedParent) {
            return true;
        }
        if (people.isAdmin(userId) || people.inTeam(userId, People.PLANNING)) {
            return true;
        }
        return parent.kind() == Kind.STREAM && people.inTeam(userId, People.TECH);
    }

    public String whoMayCreateSubtask() {
        return "Only the Planning team creates subtasks here (stream tasks come from the backend / web / mobile tags),"
                + " and the Tech team inside a stream task. To report a problem, create a Defect instead.";
    }

    public boolean canChangeLockedPriority(Long userId) {
        return people.isAdmin(userId);
    }

    public String whoMayMove(Classification kind, String from, String to) {
        if (kind.kind() == Kind.STREAM) {
            return "Only the Tech team and admins may move stream tasks.";
        }
        if (!planning.isPlanned(kind.type())) {
            if (isProductWriteUp(kind, from, to)) {
                return "Only the Product team, testers and admins may move a Bug between "
                        + planning.pendingStatus() + " and " + planning.lobbyStatus() + ".";
            }
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

    /**
     * Whether this is the write-up move (between pending and in lobby) on a task type the Product team writes up
     * itself although it isn't planned: a Bug.
     */
    private boolean isProductWriteUp(Classification kind, String from, String to) {
        return PRODUCT_WRITE_UP_TYPES.contains(kind.type()) && plannedMove(from, to) == PlannedMove.WRITE_UP;
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

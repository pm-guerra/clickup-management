package io.chronohealth.clickup.workflow;

import static java.util.Map.entry;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Which status a parent should have, given its subtasks' statuses. The least advanced subtask decides, top rule first:
 * <ol>
 *   <li>all pending -> pending; all pending or to do -> to do;</li>
 *   <li>all blocked -> blocked;</li>
 *   <li>any in progress, waiting info or blocked, or a mix of started and not started -> in progress;</li>
 *   <li>any in review -> review;</li>
 *   <li>any in ready_for_dev -> ready_for_dev if every subtask is mobile, else review;</li>
 *   <li>any in development -> in development;</li>
 *   <li>all complete -> ready for testing (Story/Bug/Change/Feature), complete (a Task wrapping tasks).</li>
 * </ol>
 * A subtask in ready for testing or approved (a nested Bug, say) counts as complete.
 * <p>
 * The app never pulls a parent back into the testers' stages before development (pending, in lobby, waiting info,
 * to do), and leaves a parent that testers moved on to approved or complete once all its subtasks are complete.
 */
public final class ParentStatusPolicy {

    public static final String PENDING = "pending";
    public static final String IN_LOBBY = "in lobby";
    public static final String WAITING_INFO = "waiting info";
    public static final String TO_DO = "to do";
    public static final String IN_PROGRESS = "in progress";
    public static final String BLOCKED = "blocked";
    public static final String REVIEW = "review";
    public static final String READY_FOR_DEV = "ready_for_dev";
    public static final String IN_DEVELOPMENT = "in development";
    public static final String READY_FOR_TESTING = "ready for testing";
    public static final String APPROVED = "approved";
    public static final String COMPLETE = "complete";

    private static final Set<String> BEFORE_DEVELOPMENT = Set.of(PENDING, IN_LOBBY, WAITING_INFO, TO_DO);
    private static final Set<String> AFTER_TESTING = Set.of(APPROVED, COMPLETE);

    private enum Stage { PENDING, NOT_STARTED, BLOCKED, ACTIVE, REVIEW, READY_FOR_DEV, IN_DEVELOPMENT, DONE }

    private static final Map<String, Stage> STAGES = Map.ofEntries(
            entry(PENDING, Stage.PENDING),
            entry(IN_LOBBY, Stage.NOT_STARTED),
            entry(TO_DO, Stage.NOT_STARTED),
            entry(BLOCKED, Stage.BLOCKED),
            entry(IN_PROGRESS, Stage.ACTIVE),
            entry(WAITING_INFO, Stage.ACTIVE),
            entry(REVIEW, Stage.REVIEW),
            entry(READY_FOR_DEV, Stage.READY_FOR_DEV),
            entry(IN_DEVELOPMENT, Stage.IN_DEVELOPMENT),
            entry(READY_FOR_TESTING, Stage.DONE),
            entry(APPROVED, Stage.DONE),
            entry(COMPLETE, Stage.DONE));

    /**
     * How far along a status is, to tell whether a parent is ahead of its subtasks. The stages before development
     * share a rank: moving between them is the testers' call.
     */
    private static final Map<String, Integer> RANKS = Map.ofEntries(
            entry(PENDING, 1), entry(IN_LOBBY, 1), entry(WAITING_INFO, 1), entry(TO_DO, 1),
            entry(IN_PROGRESS, 2), entry(BLOCKED, 2),
            entry(REVIEW, 3),
            entry(READY_FOR_DEV, 4),
            entry(IN_DEVELOPMENT, 5),
            entry(READY_FOR_TESTING, 6),
            entry(APPROVED, 7),
            entry(COMPLETE, 8));

    private ParentStatusPolicy() {
    }

    /**
     * @param status a subtask's status
     * @param mobile whether the subtask belongs to the mobile stream
     */
    public record Child(String status, boolean mobile) {
    }

    /**
     * @param testingType the parent is tested as a whole (Story/Bug/Change/Feature): done means ready for testing
     * @return the status the subtasks call for; empty without subtasks or when one is in a status not known here
     */
    public static Optional<String> derive(List<Child> children, boolean testingType) {
        if (children.isEmpty()) {
            return Optional.empty();
        }
        List<Stage> stages = new ArrayList<>();
        for (Child child : children) {
            Stage stage = STAGES.get(normalize(child.status()));
            if (stage == null) {
                return Optional.empty();
            }
            stages.add(stage);
        }
        if (stages.stream().allMatch(s -> s == Stage.PENDING)) {
            return Optional.of(PENDING);
        }
        if (stages.stream().allMatch(s -> s == Stage.PENDING || s == Stage.NOT_STARTED)) {
            return Optional.of(TO_DO);
        }
        if (stages.stream().allMatch(s -> s == Stage.BLOCKED)) {
            return Optional.of(BLOCKED);
        }
        if (stages.stream().anyMatch(s -> s.compareTo(Stage.ACTIVE) <= 0)) {
            return Optional.of(IN_PROGRESS);
        }
        if (stages.contains(Stage.REVIEW)) {
            return Optional.of(REVIEW);
        }
        if (stages.contains(Stage.READY_FOR_DEV)) {
            return Optional.of(children.stream().allMatch(Child::mobile) ? READY_FOR_DEV : REVIEW);
        }
        if (stages.contains(Stage.IN_DEVELOPMENT)) {
            return Optional.of(IN_DEVELOPMENT);
        }
        return Optional.of(testingType ? READY_FOR_TESTING : COMPLETE);
    }

    /**
     * @return the status to move a parent in {@code current} to, when its subtasks call for {@code derived}; empty to
     *         leave it where it is
     */
    public static Optional<String> move(String current, String derived) {
        String status = normalize(current);
        if (status.equals(derived)) {
            return Optional.empty();
        }
        if (BEFORE_DEVELOPMENT.contains(status) && (PENDING.equals(derived) || TO_DO.equals(derived))) {
            return Optional.empty();
        }
        if (READY_FOR_TESTING.equals(derived) && AFTER_TESTING.contains(status)) {
            return Optional.empty();
        }
        return Optional.of(derived);
    }

    /**
     * @return true if a parent in {@code status} is further along than its subtasks ({@code derived}) allow
     */
    public static boolean isAhead(String status, String derived) {
        String normalized = normalize(status);
        Integer rank = RANKS.get(normalized);
        Integer derivedRank = RANKS.get(derived);
        if (rank == null || derivedRank == null) {
            return false;
        }
        if (READY_FOR_TESTING.equals(derived) && AFTER_TESTING.contains(normalized)) {
            return false;
        }
        return rank > derivedRank;
    }

    public static String normalize(String status) {
        return status == null ? "" : status.trim().toLowerCase(Locale.ROOT);
    }
}

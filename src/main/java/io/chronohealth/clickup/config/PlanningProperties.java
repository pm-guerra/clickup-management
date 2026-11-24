package io.chronohealth.clickup.config;

import java.util.List;
import java.util.Locale;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * How planned work flows before implementation (Features and Changes): the Product team writes it up in
 * {@code pending}, moves it to {@code in lobby}, where the Planning team plans it and adds the stream tags, then a
 * planner moves it to {@code to do}.
 *
 * @param enabled              apply the planning flow at all
 * @param taskTypes            task types that go through planning (Feature, Change)
 * @param pendingStatus        where the Product team writes the task up ("pending")
 * @param lobbyStatus          where the Planning team plans it ("in lobby"); stream subtasks created while the parent
 *                             is being written up or planned start here too
 * @param toDoStatus           where implementation starts ("to do")
 * @param priorityRequiredTypes tasks of these types can't reach {@code toDoStatus} without a priority (Feature,
 *                             Change, Bug)
 * @param plannerField         People custom field set to the planner who moved the task to {@code toDoStatus}, so
 *                             Octo bot knows whom to ask ("Planner"); empty for none
 */
@Validated
@ConfigurationProperties("app.planning")
public record PlanningProperties(
        boolean enabled,
        List<String> taskTypes,
        String pendingStatus,
        String lobbyStatus,
        String toDoStatus,
        List<String> priorityRequiredTypes,
        String plannerField
) {

    public boolean hasPlannerField() {
        return plannerField != null && !plannerField.isBlank();
    }

    public boolean isPlanned(String type) {
        return enabled && contains(taskTypes, type);
    }

    public boolean needsPriority(String type) {
        return enabled && contains(priorityRequiredTypes, type);
    }

    /**
     * Pending or in lobby: the task is still being written up or planned.
     */
    public boolean isBeforePlanningDone(String status) {
        return is(status, pendingStatus) || is(status, lobbyStatus);
    }

    public boolean isLobby(String status) {
        return is(status, lobbyStatus);
    }

    public boolean isToDo(String status) {
        return is(status, toDoStatus);
    }

    private static boolean is(String status, String expected) {
        return status != null && expected != null && normalize(status).equals(normalize(expected));
    }

    private static boolean contains(List<String> values, String value) {
        return value != null && values != null && values.stream().anyMatch(v -> normalize(v).equals(normalize(value)));
    }

    private static String normalize(String value) {
        return value.trim().toLowerCase(Locale.ROOT);
    }
}

package io.chronohealth.clickup.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import io.chronohealth.clickup.workflow.ParentStatusPolicy.Child;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class ParentStatusPolicyTest {

    @Test
    void notStartedSubtasks() {
        assertThat(derive("pending", "pending")).isEqualTo("pending");
        assertThat(derive("pending", "to do")).isEqualTo("to do");
        assertThat(derive("to do", "to do")).isEqualTo("to do");
    }

    @Test
    void blockedOnlyWhenAllBlocked() {
        assertThat(derive("blocked", "blocked")).isEqualTo("blocked");
        assertThat(derive("blocked", "review")).isEqualTo("in progress");
    }

    @Test
    void anythingInProgressOrMixedMeansInProgress() {
        assertThat(derive("in progress", "complete")).isEqualTo("in progress");
        assertThat(derive("to do", "review")).isEqualTo("in progress");
        assertThat(derive("pending", "complete")).isEqualTo("in progress");
    }

    @Test
    void reviewWhenAllInReviewOrBeyond() {
        assertThat(derive("review", "review")).isEqualTo("review");
        assertThat(derive("review", "complete")).isEqualTo("review");
        assertThat(derive("review", "in development")).isEqualTo("review");
    }

    @Test
    void readyForDevOnlyWhenAllSubtasksAreMobile() {
        assertThat(ParentStatusPolicy.derive(List.of(mobile("ready_for_dev"), mobile("ready_for_dev")), true))
                .contains("ready_for_dev");
        assertThat(ParentStatusPolicy.derive(List.of(mobile("ready_for_dev"), mobile("complete")), true))
                .contains("ready_for_dev");
        assertThat(ParentStatusPolicy.derive(List.of(mobile("ready_for_dev"), new Child("complete", false)), true))
                .contains("review");
    }

    @Test
    void inDevelopmentWhenAllInDevelopmentOrComplete() {
        assertThat(derive("in development", "in development")).isEqualTo("in development");
        assertThat(derive("in development", "complete")).isEqualTo("in development");
    }

    @Test
    void allCompleteMeansReadyForTestingOrCompleteForWrappers() {
        assertThat(derive("complete", "complete")).isEqualTo("in testing");
        assertThat(ParentStatusPolicy.derive(List.of(new Child("complete", false)), false)).contains("complete");
        // A nested Bug already in testing counts as complete.
        assertThat(derive("in testing", "complete")).isEqualTo("in testing");
    }

    @Test
    void anySubtaskWaitingForInfoLeavesTheParentToPeople() {
        assertThat(ParentStatusPolicy.derive(List.of(new Child("waiting info", false), new Child("complete", false)),
                true)).isEmpty();
        assertThat(ParentStatusPolicy.derive(List.of(new Child("Waiting Info", false),
                new Child("in progress", false)), true)).isEmpty();
    }

    @Test
    void unknownStatusesOrNoSubtasksDecideNothing() {
        assertThat(ParentStatusPolicy.derive(List.of(new Child("something new", false)), true)).isEmpty();
        assertThat(ParentStatusPolicy.derive(List.of(), true)).isEmpty();
    }

    @Test
    void movesForwardsAndBackwards() {
        assertThat(ParentStatusPolicy.move("in testing", "review")).contains("review");
        assertThat(ParentStatusPolicy.move("in progress", "review")).contains("review");
        assertThat(ParentStatusPolicy.move("complete", "in progress")).contains("in progress");
        assertThat(ParentStatusPolicy.move("in progress", "to do")).contains("to do");
        assertThat(ParentStatusPolicy.move("in lobby", "in progress")).contains("in progress");
        assertThat(ParentStatusPolicy.move("Review", "review")).isEmpty();
    }

    @Test
    void leavesTestersStagesAlone() {
        assertThat(ParentStatusPolicy.move("in lobby", "to do")).isEmpty();
        assertThat(ParentStatusPolicy.move("waiting info", "pending")).isEmpty();
        assertThat(ParentStatusPolicy.move("approved", "in testing")).isEmpty();
        assertThat(ParentStatusPolicy.move("complete", "in testing")).isEmpty();
    }

    @Test
    void parentAheadOfSubtasks() {
        assertThat(ParentStatusPolicy.isAhead("in testing", "review")).isTrue();
        assertThat(ParentStatusPolicy.isAhead("in progress", "to do")).isTrue();
        assertThat(ParentStatusPolicy.isAhead("in progress", "review")).isFalse();
        assertThat(ParentStatusPolicy.isAhead("to do", "pending")).isFalse();
        assertThat(ParentStatusPolicy.isAhead("approved", "in testing")).isFalse();
        assertThat(ParentStatusPolicy.isAhead("complete", "in testing")).isFalse();
    }

    private static String derive(String... statuses) {
        return ParentStatusPolicy.derive(Arrays.stream(statuses).map(s -> new Child(s, false)).toList(), true)
                .orElseThrow();
    }

    private static Child mobile(String status) {
        return new Child(status, true);
    }
}

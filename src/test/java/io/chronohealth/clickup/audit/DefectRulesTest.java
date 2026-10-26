package io.chronohealth.clickup.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.chronohealth.clickup.client.ClickUpClient;
import io.chronohealth.clickup.client.dto.CustomTaskType;
import io.chronohealth.clickup.client.dto.IdRef;
import io.chronohealth.clickup.client.dto.Task;
import io.chronohealth.clickup.workflow.ParentStatusTestSupport;
import io.chronohealth.clickup.workflow.TaskTypeResolver;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DefectRulesTest {

    private static final long BUG = 1003L;
    private static final long STORY = 1004L;
    private static final long FEATURE = 1007L;
    private static final long DEFECT = 1010L;

    private final ClickUpClient client = mock(ClickUpClient.class);
    private final AuditContext context = new AuditContext(client, "ws", new TaskTypeResolver(), Duration.ZERO);
    private final DefectPlacementRule placement = new DefectPlacementRule(ParentStatusTestSupport.auditProperties());
    private final DefectPointsRule points = new DefectPointsRule(ParentStatusTestSupport.auditProperties());

    @BeforeEach
    void setUp() {
        when(client.getCustomTaskTypes("ws")).thenReturn(List.of(new CustomTaskType(BUG, "Bug"),
                new CustomTaskType(STORY, "Story"), new CustomTaskType(FEATURE, "Feature"),
                new CustomTaskType(DEFECT, "Defect")));
        when(client.getTask("feature")).thenReturn(task("feature", null, FEATURE, null));
        when(client.getTask("story")).thenReturn(task("story", null, STORY, null));
    }

    @Test
    void defectsBelongInsideAFeatureBugOrChange() {
        assertThat(placement.check(task("d1", "feature", DEFECT, null), context)).isEmpty();
        assertThat(placement.check(task("d2", "story", DEFECT, null), context)).get().asString()
                .contains("inside a Story");
        assertThat(placement.check(task("d3", null, DEFECT, null), context)).get().asString()
                .contains("isn't inside any");
        assertThat(placement.check(task("bug", null, BUG, null), context)).isEmpty();
    }

    @Test
    void sprintPointsAreRemovedFromDefectsOnly() {
        assertThat(points.check(task("d1", "feature", DEFECT, 0.5), context)).get().asString()
                .contains("Had 0.5 sprint point(s)");
        verify(client).clearPoints("d1");

        assertThat(points.check(task("bug", null, BUG, 2.0), context)).isEmpty();
        assertThat(points.check(task("d2", "feature", DEFECT, null), context)).isEmpty();
        verify(client, never()).clearPoints("bug");
        verify(client, never()).clearPoints("d2");
    }

    private static Task task(String id, String parent, Long type, Double points) {
        return new Task(id, "name", parent, type, null, null, new IdRef("list"), List.of(), List.of(), List.of(),
                List.of(), points);
    }
}

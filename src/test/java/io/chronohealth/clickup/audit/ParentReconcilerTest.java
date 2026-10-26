package io.chronohealth.clickup.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.chronohealth.clickup.client.ClickUpClient;
import io.chronohealth.clickup.client.ClickUpClient.TaskPage;
import io.chronohealth.clickup.client.ClickUpClientFactory;
import io.chronohealth.clickup.client.dto.CustomTaskType;
import io.chronohealth.clickup.client.dto.IdRef;
import io.chronohealth.clickup.client.dto.Task;
import io.chronohealth.clickup.client.dto.TaskStatus;
import io.chronohealth.clickup.client.dto.TaskUpdate;
import io.chronohealth.clickup.workflow.ListStatusResolver;
import io.chronohealth.clickup.workflow.ParentStatusTestSupport;
import io.chronohealth.clickup.workflow.ParentStatuses;
import io.chronohealth.clickup.workflow.TaskTypeResolver;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ParentReconcilerTest {

    private static final long BUG = 1003L;
    private static final long DEFECT = 1010L;

    private final ClickUpClientFactory factory = mock(ClickUpClientFactory.class);
    private final ClickUpClient client = mock(ClickUpClient.class);
    private ParentReconciler reconciler;

    @BeforeEach
    void setUp() {
        AuditProperties properties = ParentStatusTestSupport.auditProperties();
        reconciler = new ParentReconciler(factory, new TaskTypeResolver(), new ListStatusResolver(Clock.systemUTC()),
                new ParentStatuses(new TaskKinds(properties), ParentStatusTestSupport.clickUpProperties()),
                ParentStatusTestSupport.clickUpProperties(), properties);
        when(factory.forWorkspace("ws")).thenReturn(client);
        when(client.getCustomTaskTypes("ws")).thenReturn(List.of(new CustomTaskType(BUG, "Bug"),
                new CustomTaskType(DEFECT, "Defect")));
        List<String> order = List.of("to do", "in progress", "review", "in testing", "complete");
        when(client.getListStatuses("list")).thenReturn(order.stream()
                .map(s -> new TaskStatus(s, null, order.indexOf(s))).toList());
        // Bug > wrapper Task (in progress) > sub-subtasks both in review; Defect > stream in review.
        when(client.getTasksUpdatedSince(anyString(), any(), anyList(), anyInt())).thenReturn(new TaskPage(List.of(
                task("bug", null, BUG, "in progress"),
                task("wrapper", "bug", null, "in progress"),
                task("s1", "wrapper", null, "review"),
                task("s2", "wrapper", null, "review"),
                task("defect", "bug", DEFECT, "in progress"),
                task("s3", "defect", null, "review")), true));
    }

    @Test
    void dryRunListsMovesDeepestFirstWithoutMovingAnything() {
        ParentReconciler.Result result = reconciler.reconcile(false);

        assertThat(result.moves()).extracting(ParentReconciler.Move::taskId, ParentReconciler.Move::to)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("wrapper", "review"),
                        org.assertj.core.groups.Tuple.tuple("defect", "review"),
                        // Sees the wrapper's new status; the Defect doesn't count for it.
                        org.assertj.core.groups.Tuple.tuple("bug", "review"));
        assertThat(result.moves()).allMatch(m -> m.outcome().equals("would move"));
        verify(client, never()).updateTask(anyString(), any());
    }

    @Test
    void applyMovesThem() {
        reconciler.reconcile(true);

        verify(client).updateTask("wrapper", TaskUpdate.status("review"));
        verify(client).updateTask("defect", TaskUpdate.status("review"));
        verify(client).updateTask("bug", TaskUpdate.status("review"));
    }

    private static Task task(String id, String parent, Long type, String status) {
        return new Task(id, "name", parent, type, new TaskStatus(status, null, null), null, new IdRef("list"),
                List.of(), List.of(), List.of(), List.of());
    }
}

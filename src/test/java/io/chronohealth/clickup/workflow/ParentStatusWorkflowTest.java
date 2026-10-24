package io.chronohealth.clickup.workflow;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.chronohealth.clickup.audit.TaskKinds;
import io.chronohealth.clickup.client.ClickUpClient;
import io.chronohealth.clickup.client.ClickUpClientFactory;
import io.chronohealth.clickup.client.dto.CustomTaskType;
import io.chronohealth.clickup.client.dto.IdRef;
import io.chronohealth.clickup.client.dto.Tag;
import io.chronohealth.clickup.client.dto.Task;
import io.chronohealth.clickup.client.dto.TaskStatus;
import io.chronohealth.clickup.client.dto.TaskUpdate;
import io.chronohealth.clickup.webhook.ClickUpEvent;
import java.time.Clock;
import java.util.Arrays;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ParentStatusWorkflowTest {

    /**
     * The Product list's statuses, in board order.
     */
    private static final List<String> ORDER = List.of("pending", "in lobby", "waiting info", "to do", "in progress",
            "blocked", "review", "ready_for_dev", "in development", "ready for testing", "approved", "complete");
    private static final long BUG = 1001L;
    private static final long EPIC = 1003L;

    private final ClickUpClientFactory factory = mock(ClickUpClientFactory.class);
    private final ClickUpClient client = mock(ClickUpClient.class);
    private final ParentStatusWorkflow workflow = new ParentStatusWorkflow(factory, new TaskTypeResolver(),
            new ListStatusResolver(Clock.systemUTC()),
            new ParentStatuses(new TaskKinds(ParentStatusTestSupport.auditProperties()),
                    ParentStatusTestSupport.clickUpProperties()),
            ParentStatusTestSupport.clickUpProperties());

    @BeforeEach
    void setUp() {
        when(factory.forWorkspace("ws")).thenReturn(client);
        when(client.getCustomTaskTypes("ws")).thenReturn(List.of(
                new CustomTaskType(BUG, "Bug"), new CustomTaskType(EPIC, "Epic")));
        when(client.getListStatuses("list")).thenReturn(IntStream.range(0, ORDER.size())
                .mapToObj(i -> new TaskStatus(ORDER.get(i), null, i)).toList());
        when(client.getTask("child")).thenReturn(task("child", "parent", null, "in progress"));
    }

    @Test
    void firstStartedSubtaskMovesParentToInProgress() {
        parent(BUG, "to do", "in progress", "to do");

        workflow.handle(event());

        verify(client).updateTask("parent", TaskUpdate.status("in progress"));
    }

    @Test
    void subtaskInReviewPullsParentBackFromReadyForTesting() {
        parent(BUG, "ready for testing", "review", "complete");

        workflow.handle(event());

        verify(client).updateTask("parent", TaskUpdate.status("review"));
    }

    @Test
    void allCompleteMovesParentToReadyForTesting() {
        parent(BUG, "review", "complete", "complete");

        workflow.handle(event());

        verify(client).updateTask("parent", TaskUpdate.status("ready for testing"));
    }

    @Test
    void taskWrappingTasksGoesToCompleteNotReadyForTesting() {
        parent(null, "in progress", "complete", "complete");

        workflow.handle(event());

        verify(client).updateTask("parent", TaskUpdate.status("complete"));
    }

    @Test
    void readyForDevOnlyWhenAllSubtasksAreMobile() {
        parentWith(BUG, "review", sub("ready_for_dev", "mobile"), sub("ready_for_dev", "mobile"));
        workflow.handle(event());
        verify(client).updateTask("parent", TaskUpdate.status("ready_for_dev"));

        parentWith(BUG, "in progress", sub("ready_for_dev", "mobile"), sub("complete", "web"));
        workflow.handle(event());
        verify(client).updateTask("parent", TaskUpdate.status("review"));
    }

    @Test
    void reopenedSubtaskPullsCompleteParentBack() {
        parent(BUG, "complete", "in progress", "complete");

        workflow.handle(event());

        verify(client).updateTask("parent", TaskUpdate.status("in progress"));
    }

    @Test
    void subtaskWaitingForInfoLeavesTheParentAlone() {
        // E.g. after testing rejected it: the parent stays where the tester put it.
        parent(BUG, "to do", "waiting info", "complete");
        workflow.handle(event());
        parent(BUG, "ready for testing", "waiting info", "in progress");
        workflow.handle(event());

        verify(client, never()).updateTask(anyString(), any());
    }

    @Test
    void newSubtaskPullsParentBack() {
        parent(BUG, "review", "review", "to do");

        workflow.handle(new ClickUpEvent("key", "wh", "ws", "taskCreated", "child", List.of()));

        verify(client).updateTask("parent", TaskUpdate.status("in progress"));
    }

    @Test
    void allSubtasksBackToToDoMovesParentBack() {
        parent(BUG, "in progress", "to do", "pending");

        workflow.handle(event());

        verify(client).updateTask("parent", TaskUpdate.status("to do"));
    }

    @Test
    void leavesTestersStagesAlone() {
        parent(BUG, "in lobby", "to do", "to do");
        workflow.handle(event());
        parent(BUG, "approved", "complete", "complete");
        workflow.handle(event());

        verify(client, never()).updateTask(anyString(), any());
    }

    @Test
    void ignoresEpicsAndTopLevelTasks() {
        parent(EPIC, "to do", "in progress");
        workflow.handle(event());

        when(client.getTask("child")).thenReturn(task("child", null, null, "in progress"));
        workflow.handle(event());

        verify(client, never()).updateTask(anyString(), any());
    }

    private void parent(Long type, String status, String... subtaskStatuses) {
        parentWith(type, status, Arrays.stream(subtaskStatuses).map(s -> sub(s, null)).toArray(Task[]::new));
    }

    private void parentWith(Long type, String status, Task... subtasks) {
        Task parent = new Task("parent", "name", null, type, new TaskStatus(status, null, ORDER.indexOf(status)),
                null, new IdRef("list"), List.of(), List.of(), List.of(), List.of(subtasks));
        when(client.getTask("parent", true)).thenReturn(parent);
    }

    private static Task sub(String status, String stream) {
        return new Task("s-" + status, "name", "parent", null, new TaskStatus(status, null, ORDER.indexOf(status)),
                null, new IdRef("list"), List.of(), stream == null ? List.of() : List.of(new Tag(stream)), List.of(),
                List.of());
    }

    private static Task task(String id, String parent, Long type, String status) {
        return new Task(id, "name", parent, type, new TaskStatus(status, null, ORDER.indexOf(status)), null,
                new IdRef("list"), List.of(), List.of(), List.of(), List.of());
    }

    private static ClickUpEvent event() {
        return new ClickUpEvent("key", "wh", "ws", "taskStatusUpdated", "child", List.of());
    }
}

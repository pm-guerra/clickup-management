package io.chronohealth.clickup.workflow;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.chronohealth.clickup.client.ClickUpClient;
import io.chronohealth.clickup.client.ClickUpClientFactory;
import io.chronohealth.clickup.client.dto.CustomTaskType;
import io.chronohealth.clickup.client.dto.IdRef;
import io.chronohealth.clickup.client.dto.Task;
import io.chronohealth.clickup.client.dto.TaskStatus;
import io.chronohealth.clickup.client.dto.TaskUpdate;
import io.chronohealth.clickup.config.ClickUpProperties;
import io.chronohealth.clickup.webhook.ClickUpEvent;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ParentStatusWorkflowTest {

    /**
     * The Product list's statuses, in board order.
     */
    private static final List<String> ORDER = List.of("pending", "waiting info", "in lobby", "to do", "in progress",
            "blocked", "review", "ready for testing", "complete mobile", "complete web", "complete");
    private static final long BUG = 1001L;
    private static final long EPIC = 1003L;

    private final ClickUpClientFactory factory = mock(ClickUpClientFactory.class);
    private final ClickUpClient client = mock(ClickUpClient.class);
    private final ParentStatusWorkflow workflow = new ParentStatusWorkflow(factory, new TaskTypeResolver(),
            new ListStatusResolver(Clock.systemUTC()), properties());

    @BeforeEach
    void setUp() {
        when(factory.forWorkspace("ws")).thenReturn(client);
        when(client.getCustomTaskTypes("ws")).thenReturn(List.of(
                new CustomTaskType(BUG, "Bug"), new CustomTaskType(EPIC, "Epic")));
        when(client.getListStatuses("list")).thenReturn(IntStream.range(0, ORDER.size())
                .mapToObj(i -> new TaskStatus(ORDER.get(i), null, i)).toList());
        when(client.getTask("child")).thenReturn(task("child", "parent", null, "in progress", List.of()));
    }

    @Test
    void firstStartedSubtaskMovesParentToInProgress() {
        parent(BUG, "to do", "in progress", "to do");

        workflow.handle(event());

        verify(client).updateTask("parent", TaskUpdate.status("in progress"));
    }

    @Test
    void anyStartedStatusCounts() {
        for (String started : List.of("review", "blocked", "ready for testing", "complete")) {
            parent(BUG, "pending", started, "to do");
            workflow.handle(event());
        }

        verify(client, org.mockito.Mockito.times(4)).updateTask("parent", TaskUpdate.status("in progress"));
    }

    @Test
    void allCompleteMovesParentToReadyForTesting() {
        parent(BUG, "in progress", "complete", "complete", "complete");

        workflow.handle(event());

        verify(client).updateTask("parent", TaskUpdate.status("ready for testing"));
    }

    @Test
    void completeMobileAndWebAreNeitherStartedNorDone() {
        parent(BUG, "to do", "complete mobile", "complete web");

        workflow.handle(event());

        verify(client, never()).updateTask(anyString(), any());
    }

    @Test
    void notAllCompleteStaysInProgress() {
        parent(BUG, "in progress", "complete", "complete mobile");

        workflow.handle(event());

        verify(client, never()).updateTask(anyString(), any());
    }

    @Test
    void neverMovesParentBackwards() {
        // Parent already in review; a subtask being in progress must not pull it back.
        parent(BUG, "review", "in progress", "to do");
        workflow.handle(event());
        // Parent already complete; all subtasks complete must not move it back to ready for testing.
        parent(BUG, "complete", "complete", "complete");
        workflow.handle(event());

        verify(client, never()).updateTask(anyString(), any());
    }

    @Test
    void movesForwardFromBlockedOrReviewWhenAllComplete() {
        parent(BUG, "review", "complete", "complete");

        workflow.handle(event());

        verify(client).updateTask("parent", TaskUpdate.status("ready for testing"));
    }

    @Test
    void ignoresParentsOfOtherTypesAndTopLevelTasks() {
        parent(EPIC, "to do", "in progress");
        workflow.handle(event());

        when(client.getTask("child")).thenReturn(task("child", null, null, "in progress", List.of()));
        workflow.handle(event());

        verify(client, never()).updateTask(anyString(), any());
    }

    @Test
    void nothingStartedLeavesParentAlone() {
        parent(BUG, "pending", "to do", "pending");

        workflow.handle(event());

        verify(client, never()).updateTask(anyString(), any());
    }

    private void parent(Long type, String status, String... subtaskStatuses) {
        List<Task> subtasks = java.util.Arrays.stream(subtaskStatuses)
                .map(s -> task("s-" + s, "parent", null, s, List.of()))
                .toList();
        when(client.getTask("parent", true)).thenReturn(task("parent", null, type, status, subtasks));
    }

    private static Task task(String id, String parent, Long type, String status, List<Task> subtasks) {
        return new Task(id, "name", parent, type, new TaskStatus(status, null, ORDER.indexOf(status)), null,
                new IdRef("list"), List.of(), List.of(), List.of(), subtasks);
    }

    private static ClickUpEvent event() {
        return new ClickUpEvent("key", "wh", "ws", "taskStatusUpdated", "child", List.of());
    }

    private static ClickUpProperties properties() {
        return new ClickUpProperties("id", "secret", "https://x/cb", "ws", "https://api", "https://auth",
                Duration.ofSeconds(1), Duration.ofSeconds(1), new ClickUpProperties.RateLimit(0, Duration.ofSeconds(1)),
                new ClickUpProperties.Workflows(new ClickUpProperties.CopyParentFields(false, "{}", ""),
                        new ClickUpProperties.TagSubtasks(false, List.of()),
                        new ClickUpProperties.ParentStatus(true, List.of("Story", "Bug", "Change"),
                                List.of("in progress", "review", "blocked", "ready for testing", "complete"),
                                "in progress", List.of("complete"), "ready for testing"),
                        new ClickUpProperties.CreateDefaults(false, List.of())));
    }
}

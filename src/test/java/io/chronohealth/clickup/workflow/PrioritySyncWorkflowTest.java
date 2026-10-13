package io.chronohealth.clickup.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.chronohealth.clickup.client.ClickUpClient;
import io.chronohealth.clickup.client.ClickUpClientFactory;
import io.chronohealth.clickup.client.dto.CustomTaskType;
import io.chronohealth.clickup.client.dto.IdRef;
import io.chronohealth.clickup.client.dto.Priority;
import io.chronohealth.clickup.client.dto.Task;
import io.chronohealth.clickup.client.dto.TaskStatus;
import io.chronohealth.clickup.config.ClickUpProperties;
import io.chronohealth.clickup.webhook.ClickUpEvent;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PrioritySyncWorkflowTest {

    private static final long BUG = 1001L;
    private static final long EPIC = 1003L;

    private final ClickUpClientFactory factory = mock(ClickUpClientFactory.class);
    private final ClickUpClient client = mock(ClickUpClient.class);
    private final PrioritySyncWorkflow workflow = new PrioritySyncWorkflow(factory, new TaskTypeResolver(), properties());

    @BeforeEach
    void setUp() {
        when(factory.forWorkspace("ws")).thenReturn(client);
        when(client.getCustomTaskTypes("ws")).thenReturn(List.of(
                new CustomTaskType(BUG, "Bug"), new CustomTaskType(EPIC, "Epic")));
    }

    @Test
    void copiesTheNewPriorityToStreamTasksWhileTheParentIsInToDo() {
        parent(BUG, "to do", "1",
                task("web", null, "2"), task("mobile", null, "1"), task("nested-bug", BUG, "3"));

        workflow.handle(event());

        verify(client).setPriority("web", 1);
        verify(client, never()).setPriority("mobile", 1);      // already the same
        verify(client, never()).setPriority(eq("nested-bug"), any()); // a nested Bug keeps its own
    }

    @Test
    void clearingTheParentsPriorityClearsTheStreamTasks() {
        parent(BUG, "to do", null, task("web", null, "2"));

        workflow.handle(event());

        verify(client).setPriority("web", null);
    }

    @Test
    void onlyWhileTheParentIsInToDoAndOnlyForStoriesBugsAndChanges() {
        parent(BUG, "in progress", "1", task("web", null, "2"));
        workflow.handle(event());
        parent(EPIC, "to do", "1", task("web", null, "2"));
        workflow.handle(event());

        verify(client, never()).setPriority(anyString(), any());
    }

    @Test
    void subscribesToPriorityChanges() {
        assertThat(workflow.eventTypes()).containsExactly("taskPriorityUpdated");
    }

    private void parent(Long type, String status, String priority, Task... subtasks) {
        when(client.getTask("parent", true)).thenReturn(new Task("parent", "Parent", null, type,
                new TaskStatus(status, null, null), priority == null ? null : new Priority(priority, null),
                new IdRef("list"), List.of(), List.of(), List.of(), List.of(subtasks)));
    }

    private static Task task(String id, Long type, String priority) {
        return new Task(id, id, "parent", type, new TaskStatus("to do", null, null), new Priority(priority, null),
                new IdRef("list"), List.of(), List.of(), List.of(), List.of());
    }

    private static ClickUpEvent event() {
        return new ClickUpEvent("key", "wh", "ws", "taskPriorityUpdated", "parent", List.of());
    }

    private static ClickUpProperties properties() {
        return new ClickUpProperties("id", "secret", "https://x/cb", "ws", "https://api", "https://auth",
                Duration.ofSeconds(1), Duration.ofSeconds(1), new ClickUpProperties.RateLimit(0, Duration.ofSeconds(1)),
                new ClickUpProperties.Workflows(new ClickUpProperties.CopyParentFields(false, "{}", ""),
                        new ClickUpProperties.TagSubtasks(false, List.of()),
                        new ClickUpProperties.ParentStatus(false, List.of(), List.of(), "in progress", List.of(),
                                "ready for testing"),
                        new ClickUpProperties.CreateDefaults(false, List.of()),
                        new ClickUpProperties.RejectionReopen(false, List.of(), List.of(), List.of(), List.of(),
                                "waiting info"),
                        new ClickUpProperties.PrioritySync(true, List.of("Story", "Bug", "Change"), List.of("to do"),
                                List.of("Task"))));
    }
}

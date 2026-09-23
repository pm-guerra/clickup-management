package io.chronohealth.clickup.workflow;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
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
import io.chronohealth.clickup.webhook.ClickUpWebhookPayload.HistoryItem;
import java.time.Duration;
import java.util.stream.IntStream;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class RejectionReopenWorkflowTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final long STORY = 1004L;
    private static final long EPIC = 1003L;

    private final ClickUpClientFactory factory = mock(ClickUpClientFactory.class);
    private final ClickUpClient client = mock(ClickUpClient.class);
    private final RejectionReopenWorkflow workflow = new RejectionReopenWorkflow(factory, new TaskTypeResolver(),
            properties());

    @BeforeEach
    void setUp() {
        when(factory.forWorkspace("ws")).thenReturn(client);
        when(client.getCustomTaskTypes("ws")).thenReturn(List.of(
                new CustomTaskType(STORY, "Story"), new CustomTaskType(EPIC, "Epic")));
    }

    @Test
    void reopensOnlyCompletedSubtasksWhenTestingRejects() {
        task(STORY, "complete", "complete mobile", "in progress", "complete", "to do");

        workflow.handle(statusChange("ready for testing", "in progress"));

        verify(client).updateTask("s0-complete", TaskUpdate.status("waiting info"));
        verify(client).updateTask("s3-complete", TaskUpdate.status("waiting info"));
        verify(client, never()).updateTask("s1-complete mobile", TaskUpdate.status("waiting info"));
        verify(client, never()).updateTask("s2-in progress", TaskUpdate.status("waiting info"));
        verify(client, never()).updateTask("s4-to do", TaskUpdate.status("waiting info"));
    }

    @Test
    void readyForTestingToToDoAlsoCounts() {
        task(STORY, "complete");

        workflow.handle(statusChange("Ready for Testing", "TO DO"));

        verify(client).updateTask("s0-complete", TaskUpdate.status("waiting info"));
    }

    @Test
    void otherTransitionsDoNothing() {
        task(STORY, "complete");

        workflow.handle(statusChange("in progress", "to do"));
        workflow.handle(statusChange("ready for testing", "review"));
        workflow.handle(statusChange("ready for testing", "complete"));
        workflow.handle(new ClickUpEvent("key", "wh", "ws", "taskStatusUpdated", "t", List.of()));

        verify(client, never()).getTask(anyString(), anyBoolean());
        verify(client, never()).updateTask(anyString(), any());
    }

    @Test
    void otherTaskTypesDoNothing() {
        task(EPIC, "complete");

        workflow.handle(statusChange("ready for testing", "in progress"));

        verify(client, never()).updateTask(anyString(), any());
    }

    private void task(Long type, String... subtaskStatuses) {
        List<Task> subtasks = IntStream.range(0, subtaskStatuses.length)
                .mapToObj(i -> new Task("s" + i + "-" + subtaskStatuses[i], "sub", "t", null,
                        new TaskStatus(subtaskStatuses[i], null, null), null, new IdRef("list"), List.of(), List.of(),
                        List.of(), List.of()))
                .toList();
        when(client.getTask("t", true)).thenReturn(new Task("t", "name", null, type,
                new TaskStatus("in progress", null, null), null, new IdRef("list"), List.of(), List.of(), List.of(),
                subtasks));
    }

    private static ClickUpEvent statusChange(String before, String after) {
        HistoryItem item = new HistoryItem("h", "1", null, "status", null, null,
                MAPPER.readTree("{\"status\":\"" + before + "\",\"type\":\"custom\"}"),
                MAPPER.readTree("{\"status\":\"" + after + "\",\"type\":\"custom\"}"));
        return new ClickUpEvent("key", "wh", "ws", "taskStatusUpdated", "t", List.of(item));
    }

    private static ClickUpProperties properties() {
        return new ClickUpProperties("id", "secret", "https://x/cb", "ws", "https://api", "https://auth",
                Duration.ofSeconds(1), Duration.ofSeconds(1), new ClickUpProperties.RateLimit(0, Duration.ofSeconds(1)),
                new ClickUpProperties.Workflows(new ClickUpProperties.CopyParentFields(false, "{}", ""),
                        new ClickUpProperties.TagSubtasks(false, List.of()),
                        new ClickUpProperties.ParentStatus(false, List.of(), List.of(), "in progress", List.of(),
                                "ready for testing"),
                        new ClickUpProperties.CreateDefaults(false, List.of()),
                        new ClickUpProperties.RejectionReopen(true, List.of("Story", "Bug", "Change"),
                                List.of("ready for testing"), List.of("in progress", "to do"), List.of("complete"),
                                "waiting info")));
    }
}

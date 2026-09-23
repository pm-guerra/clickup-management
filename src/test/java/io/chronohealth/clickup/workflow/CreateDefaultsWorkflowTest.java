package io.chronohealth.clickup.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.chronohealth.clickup.client.ClickUpClient;
import io.chronohealth.clickup.client.ClickUpClientFactory;
import io.chronohealth.clickup.client.dto.CustomField;
import io.chronohealth.clickup.client.dto.CustomTaskType;
import io.chronohealth.clickup.client.dto.IdRef;
import io.chronohealth.clickup.client.dto.Task;
import io.chronohealth.clickup.config.ClickUpProperties;
import io.chronohealth.clickup.webhook.ClickUpEvent;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class CreateDefaultsWorkflowTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final long BUG = 1001L;
    private static final long CHANGE = 1002L;
    private static final long STORY = 1004L;

    private final ClickUpClientFactory factory = mock(ClickUpClientFactory.class);
    private final ClickUpClient client = mock(ClickUpClient.class);
    private final CreateDefaultsWorkflow workflow = new CreateDefaultsWorkflow(factory, new TaskTypeResolver(),
            new CustomFieldValueMapper(), properties());

    @BeforeEach
    void setUp() {
        when(factory.forWorkspace("ws")).thenReturn(client);
        when(client.getCustomTaskTypes("ws")).thenReturn(List.of(
                new CustomTaskType(BUG, "Bug"), new CustomTaskType(CHANGE, "Change"), new CustomTaskType(STORY, "Story")));
    }

    @Test
    void setsMaintenanceOnNewBugsAndChanges() {
        for (long type : new long[]{BUG, CHANGE}) {
            when(client.getTask("t" + type)).thenReturn(task("t" + type, type, maintenance(null)));
            workflow.handle(created("t" + type));
            verify(client).setCustomField("t" + type, "f-maint", true);
        }
    }

    @Test
    void leavesOtherTypesAlone() {
        when(client.getTask("story")).thenReturn(task("story", STORY, maintenance(null)));
        when(client.getTask("task")).thenReturn(task("task", null, maintenance(null)));

        workflow.handle(created("story"));
        workflow.handle(created("task"));

        verify(client, never()).setCustomField(anyString(), anyString(), any());
    }

    @Test
    void skipsWhenAlreadyCheckedOrFieldMissing() {
        when(client.getTask("checked")).thenReturn(task("checked", BUG, maintenance("true")));
        when(client.getTask("nofield")).thenReturn(task("nofield", BUG));

        workflow.handle(created("checked"));
        workflow.handle(created("nofield"));

        verify(client, never()).setCustomField(anyString(), anyString(), any());
    }

    @Test
    void subscribesToTaskCreated() {
        assertThat(workflow.eventTypes()).containsExactly("taskCreated");
    }

    private static CustomField maintenance(String value) {
        return new CustomField("f-maint", "maintenance", "checkbox", null, value == null ? null : MAPPER.valueToTree(value));
    }

    private static Task task(String id, Long type, CustomField... fields) {
        return new Task(id, "name", null, type, null, null, new IdRef("list"), List.of(), List.of(), List.of(fields),
                List.of());
    }

    private static ClickUpEvent created(String taskId) {
        return new ClickUpEvent("key-" + taskId, "wh", "ws", "taskCreated", taskId, List.of());
    }

    private static ClickUpProperties properties() {
        return new ClickUpProperties("id", "secret", "https://x/cb", "ws", "https://api", "https://auth",
                Duration.ofSeconds(1), Duration.ofSeconds(1), new ClickUpProperties.RateLimit(0, Duration.ofSeconds(1)),
                new ClickUpProperties.Workflows(new ClickUpProperties.CopyParentFields(false, "{}", ""),
                        new ClickUpProperties.TagSubtasks(false, List.of()),
                        new ClickUpProperties.ParentStatus(false, List.of(), List.of(), "in progress", List.of(),
                                "ready for testing"),
                        new ClickUpProperties.CreateDefaults(true, List.of(new ClickUpProperties.CreateDefault(
                                List.of("Bug", "Change"), "maintenance", "true")))));
    }
}

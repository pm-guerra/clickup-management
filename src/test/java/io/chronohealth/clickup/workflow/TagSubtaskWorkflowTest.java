package io.chronohealth.clickup.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.chronohealth.clickup.client.ClickUpClient;
import io.chronohealth.clickup.client.ClickUpClientFactory;
import io.chronohealth.clickup.client.dto.CustomField;
import io.chronohealth.clickup.client.dto.CustomFieldValue;
import io.chronohealth.clickup.client.dto.IdRef;
import io.chronohealth.clickup.client.dto.NewSubtask;
import io.chronohealth.clickup.client.dto.Priority;
import io.chronohealth.clickup.client.dto.Tag;
import io.chronohealth.clickup.client.dto.Task;
import io.chronohealth.clickup.config.ClickUpProperties;
import io.chronohealth.clickup.webhook.ClickUpEvent;
import io.chronohealth.clickup.webhook.ClickUpWebhookPayload.HistoryItem;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class TagSubtaskWorkflowTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final ClickUpProperties.TagSubtaskRule BACKEND = new ClickUpProperties.TagSubtaskRule(
            "backend", "Backend | ", "to do", List.of("backend"), Map.of("maintenance", "true"),
            List.of("Pre Go Live"), true);

    private final ClickUpClientFactory factory = mock(ClickUpClientFactory.class);
    private final ClickUpClient client = mock(ClickUpClient.class);
    private final TagSubtaskWorkflow workflow = workflow(true);

    @BeforeEach
    void setUp() {
        when(factory.forWorkspace("ws")).thenReturn(client);
        when(client.createSubtask(any(Task.class), any())).thenReturn(task("new", "x", null, List.of()));
    }

    @Test
    void createsBackendSubtaskWithFieldsPriorityStatusAndTag() {
        Task parent = task("p1", "Fix login", new Priority("2", "high"), List.of(),
                field("f-maint", "maintenance", null), field("f-pgl", "Pre Go Live", "true"));
        when(client.getTask("p1", true)).thenReturn(parent);

        workflow.handle(tagAdded("p1", "backend"));

        ArgumentCaptor<NewSubtask> captor = ArgumentCaptor.forClass(NewSubtask.class);
        verify(client).createSubtask(eq(parent), captor.capture());
        NewSubtask subtask = captor.getValue();
        assertThat(subtask.name()).isEqualTo("Backend | Fix login");
        assertThat(subtask.status()).isEqualTo("to do");
        assertThat(subtask.priority()).isEqualTo(2);
        assertThat(subtask.tags()).containsExactly("backend");
        assertThat(subtask.customFields()).containsExactlyInAnyOrder(
                new CustomFieldValue("f-maint", true),
                new CustomFieldValue("f-pgl", true));
    }

    @Test
    void omitsEmptyParentValuesAndMissingFields() {
        // "Pre Go Live" unset on the parent, "maintenance" not on this list at all, no priority.
        Task parent = task("p1", "Fix login", null, List.of(), field("f-pgl", "Pre Go Live", null));
        when(client.getTask("p1", true)).thenReturn(parent);

        workflow.handle(tagAdded("p1", "Backend"));

        ArgumentCaptor<NewSubtask> captor = ArgumentCaptor.forClass(NewSubtask.class);
        verify(client).createSubtask(eq(parent), captor.capture());
        assertThat(captor.getValue().customFields()).isNull();
        assertThat(captor.getValue().priority()).isNull();
    }

    @Test
    void doesNotCreateWhenASubtaskAlreadyHasTheTag() {
        // Tag re-added after the parent was renamed: the existing backend subtask has a different title.
        Task existing = task("s1", "Backend | Old title", null, List.of(), List.of(new Tag("Backend")));
        when(client.getTask("p1", true)).thenReturn(task("p1", "Fix login", null, List.of(existing)));

        workflow.handle(tagAdded("p1", "backend"));

        verify(client, never()).createSubtask(any(Task.class), any());
    }

    @Test
    void doesNotCreateWhenASubtaskAlreadyHasTheTitle() {
        Task existing = task("s1", "Backend | Fix login", null, List.of(), List.of());
        when(client.getTask("p1", true)).thenReturn(task("p1", "Fix login", null, List.of(existing)));

        workflow.handle(tagAdded("p1", "backend"));

        verify(client, never()).createSubtask(any(Task.class), any());
    }

    @Test
    void createsWhenOtherSubtasksDoNotHaveTheTag() {
        Task other = task("s1", "Design review", null, List.of(), List.of(new Tag("frontend")));
        Task parent = task("p1", "Fix login", null, List.of(other));
        when(client.getTask("p1", true)).thenReturn(parent);

        workflow.handle(tagAdded("p1", "backend"));

        verify(client).createSubtask(eq(parent), any());
    }

    @Test
    void doesNotChainOnGeneratedSubtasks() {
        when(client.getTask("s1", true)).thenReturn(task("s1", "Backend | Fix login", null, List.of()));

        workflow.handle(tagAdded("s1", "backend"));

        verify(client, never()).createSubtask(any(Task.class), any());
    }

    @Test
    void ignoresOtherTagsAndRemovals() {
        workflow.handle(tagAdded("p1", "frontend"));
        workflow.handle(event("p1", new HistoryItem("h", "1", null, "tag_removed", null, null,
                MAPPER.readTree("[{\"name\":\"backend\"}]"), null)));

        verify(client, never()).getTask(anyString(), anyBoolean());
        verify(client, never()).createSubtask(any(Task.class), any());
    }

    @Test
    void readsAddedTagsInEitherShape() {
        JsonNode array = MAPPER.readTree("[{\"name\":\"Backend\"},{\"name\":\"ops\"}]");
        JsonNode single = MAPPER.readTree("{\"name\":\"qa\"}");

        assertThat(TagSubtaskWorkflow.addedTags(List.of(
                new HistoryItem("1", null, null, "tag", null, null, null, array),
                new HistoryItem("2", null, null, "tag", null, null, null, single))))
                .containsExactlyInAnyOrder("backend", "ops", "qa");
    }

    @Test
    void subscribesOnlyWhenEnabled() {
        assertThat(workflow.eventTypes()).containsExactly("taskTagUpdated");
        assertThat(workflow(false).eventTypes()).isEmpty();
    }

    private TagSubtaskWorkflow workflow(boolean enabled) {
        ClickUpProperties properties = new ClickUpProperties("id", "secret", "https://x/cb", "ws", "https://api",
                "https://auth", Duration.ofSeconds(1), Duration.ofSeconds(1),
                new ClickUpProperties.RateLimit(0, Duration.ofSeconds(1)),
                new ClickUpProperties.Workflows(new ClickUpProperties.CopyParentFields(false, "{}", ""),
                        new ClickUpProperties.TagSubtasks(enabled, List.of(BACKEND))));
        return new TagSubtaskWorkflow(factory, new CustomFieldValueMapper(), properties);
    }

    private static ClickUpEvent tagAdded(String taskId, String tag) {
        return event(taskId, new HistoryItem("h-" + taskId, "1", null, "tag", null, null, null,
                MAPPER.readTree("[{\"name\":\"" + tag + "\",\"tag_fg\":\"#000\"}]")));
    }

    private static ClickUpEvent event(String taskId, HistoryItem item) {
        return new ClickUpEvent("key", "wh", "ws", "taskTagUpdated", taskId, List.of(item));
    }

    private static Task task(String id, String name, Priority priority, List<Task> subtasks, CustomField... fields) {
        return task(id, name, priority, subtasks, List.of(new Tag("backend")), fields);
    }

    private static Task task(String id, String name, Priority priority, List<Task> subtasks, List<Tag> tags,
                             CustomField... fields) {
        return new Task(id, name, null, null, null, priority, new IdRef("list"), List.of(), tags, List.of(fields),
                subtasks);
    }

    /**
     * Checkbox values come back from ClickUp as text.
     */
    private static CustomField field(String id, String name, String value) {
        return new CustomField(id, name, "checkbox", null, value == null ? null : MAPPER.valueToTree(value));
    }
}

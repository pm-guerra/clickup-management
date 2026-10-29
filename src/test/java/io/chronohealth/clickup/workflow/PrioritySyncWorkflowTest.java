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
import io.chronohealth.clickup.client.dto.CustomField;
import io.chronohealth.clickup.client.dto.CustomTaskType;
import io.chronohealth.clickup.client.dto.IdRef;
import io.chronohealth.clickup.client.dto.Priority;
import io.chronohealth.clickup.client.dto.Task;
import io.chronohealth.clickup.client.dto.TaskStatus;
import io.chronohealth.clickup.client.dto.User;
import io.chronohealth.clickup.config.ClickUpProperties;
import io.chronohealth.clickup.webhook.ClickUpEvent;
import io.chronohealth.clickup.webhook.ClickUpWebhookPayload.HistoryItem;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class PrioritySyncWorkflowTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final long BUG = 1001L;
    private static final long EPIC = 1003L;
    private static final long DEFECT = 1010L;
    private static final String OPTIONS =
            "{\"options\":[{\"id\":\"opt-5\",\"orderindex\":0},{\"id\":\"opt-4\",\"orderindex\":1}]}";

    private final ClickUpClientFactory factory = mock(ClickUpClientFactory.class);
    private final ClickUpClient client = mock(ClickUpClient.class);
    private final PrioritySyncWorkflow workflow = new PrioritySyncWorkflow(factory, new TaskTypeResolver(),
            new CustomFieldValueMapper(), properties());

    @BeforeEach
    void setUp() {
        when(factory.forWorkspace("ws")).thenReturn(client);
        when(client.getCustomTaskTypes("ws")).thenReturn(List.of(new CustomTaskType(BUG, "Bug"),
                new CustomTaskType(EPIC, "Epic"), new CustomTaskType(DEFECT, "Defect")));
    }

    @Test
    void pushesThePriorityDownToChildrenAndGrandchildrenWhateverTheirStatus() {
        stub(task("parent", null, BUG, "in progress", "1", null, task("web", "parent", null, "review", "2", null),
                task("mobile", "parent", null, "to do", "1", null)));
        stub(task("web", "parent", null, "review", "2", null, task("subsub", "web", null, "in progress", "3", null)));
        stub(task("mobile", "parent", null, "to do", "1", null));
        stub(task("subsub", "web", null, "in progress", "3", null));

        workflow.handle(priorityEvent());

        verify(client).setPriority("web", 1);
        verify(client).setPriority("subsub", 1);
        verify(client, never()).setPriority(eq("mobile"), any()); // already the same
    }

    @Test
    void defectsAndEverythingInsideThemKeepTheirOwn() {
        stub(task("parent", null, BUG, "to do", "1", null, task("defect", "parent", DEFECT, "to do", "3", null)));

        workflow.handle(priorityEvent());

        verify(client, never()).setPriority(anyString(), any());
        verify(client, never()).getTask("defect", true);
    }

    @Test
    void clearingTheParentsPriorityClearsTheChildren() {
        stub(task("parent", null, BUG, "to do", null, null, task("web", "parent", null, "to do", "2", null)));
        stub(task("web", "parent", null, "to do", "2", null));

        workflow.handle(priorityEvent());

        verify(client).setPriority("web", null);
    }

    @Test
    void pushesProductPriorityDownWhenItChanges() {
        stub(task("parent", null, BUG, "to do", "1", "0", task("web", "parent", null, "to do", "2", "1")));
        stub(task("web", "parent", null, "to do", "2", "1"));

        workflow.handle(fieldEvent("Product Priority"));

        verify(client).setCustomField("web", "f-pp", "opt-5");
        verify(client, never()).setPriority(anyString(), any()); // only the field changed
    }

    @Test
    void otherFieldChangesAndEpicsAreIgnored() {
        workflow.handle(fieldEvent("Notes"));
        stub(task("parent", null, EPIC, "to do", "1", null, task("story", "parent", null, "to do", "2", null)));
        workflow.handle(priorityEvent());

        verify(client, never()).setPriority(anyString(), any());
        verify(client, never()).setCustomField(anyString(), anyString(), any());
    }

    @Test
    void subscribesToPriorityAndFieldChanges() {
        assertThat(workflow.eventTypes()).containsExactlyInAnyOrder("taskPriorityUpdated", "taskUpdated");
    }

    private void stub(Task task) {
        when(client.getTask(task.id(), true)).thenReturn(task);
    }

    private static Task task(String id, String parent, Long type, String status, String priority,
                             String productPriorityIndex, Task... subtasks) {
        List<CustomField> fields = List.of(new CustomField("f-pp", "Product Priority", "drop_down",
                MAPPER.readTree(OPTIONS), productPriorityIndex == null ? null : MAPPER.readTree(productPriorityIndex)));
        return new Task(id, id, parent, type, new TaskStatus(status, null, null),
                priority == null ? null : new Priority(priority, null), new IdRef("list"), List.of(), List.of(),
                fields, List.of(subtasks));
    }

    private static ClickUpEvent priorityEvent() {
        return new ClickUpEvent("key", "wh", "ws", "taskPriorityUpdated", "parent", List.of());
    }

    private static ClickUpEvent fieldEvent(String fieldName) {
        HistoryItem item = new HistoryItem("h", "1", null, "custom_field", null, new User(1L), null, null, null,
                null, null, MAPPER.readTree("{\"name\":\"" + fieldName + "\"}"));
        return new ClickUpEvent("key", "wh", "ws", "taskUpdated", "parent", List.of(item));
    }

    private static ClickUpProperties properties() {
        return new ClickUpProperties("id", "secret", "https://x/cb", "ws", "https://api", "https://auth",
                Duration.ofSeconds(1), Duration.ofSeconds(1), new ClickUpProperties.RateLimit(0, Duration.ofSeconds(1)),
                new ClickUpProperties.Workflows(new ClickUpProperties.CopyParentFields(false, "{}", ""),
                        new ClickUpProperties.TagSubtasks(false, List.of()),
                        new ClickUpProperties.ParentStatus(false, List.of(), List.of(), "mobile", List.of("Defect")),
                        new ClickUpProperties.CreateDefaults(false, List.of()),
                        new ClickUpProperties.RejectionReopen(false, List.of(), List.of(), List.of(), List.of(),
                                "waiting info", List.of()),
                        new ClickUpProperties.PrioritySync(true, List.of("Story", "Bug", "Change", "Task"),
                                List.of("Defect"), List.of("Product Priority"))));
    }
}

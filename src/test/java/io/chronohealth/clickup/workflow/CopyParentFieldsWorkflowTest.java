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
import io.chronohealth.clickup.client.dto.IdRef;
import io.chronohealth.clickup.client.dto.Task;
import io.chronohealth.clickup.config.ClickUpProperties;
import io.chronohealth.clickup.webhook.ClickUpEvent;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class CopyParentFieldsWorkflowTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private final ClickUpClientFactory factory = mock(ClickUpClientFactory.class);
    private final ClickUpClient client = mock(ClickUpClient.class);

    @BeforeEach
    void setUp() {
        when(factory.forWorkspace("ws")).thenReturn(client);
    }

    @Test
    void copiesMappedFieldsFromParentBugToNewSubtask() {
        when(client.getTask("child")).thenReturn(task("child", "bug", null, field("sev", null)));
        when(client.getTask("bug")).thenReturn(task("bug", null, 1001L, field("sev", "high")));

        workflow("{\"sev\":\"sev\"}", "1001").handle(event("child"));

        verify(client).setCustomField("child", "sev", MAPPER.valueToTree("high"));
    }

    @Test
    void skipsWhenValueAlreadyMatches() {
        when(client.getTask("child")).thenReturn(task("child", "bug", null, field("sev", "high")));
        when(client.getTask("bug")).thenReturn(task("bug", null, null, field("sev", "high")));

        workflow("{\"sev\":\"sev\"}", "").handle(event("child"));

        verify(client, never()).setCustomField(anyString(), anyString(), any());
    }

    @Test
    void skipsTopLevelTasksAndIneligibleParents() {
        when(client.getTask("top")).thenReturn(task("top", null, null));
        when(client.getTask("child")).thenReturn(task("child", "epic", null, field("sev", null)));
        when(client.getTask("epic")).thenReturn(task("epic", null, 2002L, field("sev", "high")));

        CopyParentFieldsWorkflow workflow = workflow("{\"sev\":\"sev\"}", "1001");
        workflow.handle(event("top"));
        workflow.handle(event("child"));

        verify(client, never()).setCustomField(anyString(), anyString(), any());
    }

    @Test
    void mapsDropDownOrderIndexToOptionId() {
        JsonNode typeConfig = MAPPER.readTree(
                "{\"options\":[{\"id\":\"opt-a\",\"orderindex\":0},{\"id\":\"opt-b\",\"orderindex\":1}]}");
        CustomField parentField = new CustomField("prio", "Priority", "drop_down", typeConfig, MAPPER.valueToTree(1));
        CustomField childField = new CustomField("prio", "Priority", "drop_down", typeConfig, null);
        when(client.getTask("child")).thenReturn(task("child", "bug", null, childField));
        when(client.getTask("bug")).thenReturn(task("bug", null, null, parentField));

        workflow("{\"prio\":\"prio\"}", "").handle(event("child"));

        verify(client).setCustomField("child", "prio", "opt-b");
    }

    @Test
    void onlySubscribesToTaskCreated() {
        assertThat(workflow("{\"sev\":\"sev\"}", "").eventTypes()).containsExactly("taskCreated");
        assertThat(workflow("{}", "").eventTypes()).isEmpty();
    }

    private CopyParentFieldsWorkflow workflow(String mappings, String parentType) {
        ClickUpProperties properties = new ClickUpProperties("id", "secret", "https://x/cb", "ws", "https://api",
                "https://auth", Duration.ofSeconds(1), Duration.ofSeconds(1),
                new ClickUpProperties.RateLimit(0, Duration.ofSeconds(1)),
                new ClickUpProperties.Workflows(new ClickUpProperties.CopyParentFields(true, mappings, parentType),
                        new ClickUpProperties.TagSubtasks(false, List.of()),
                        new ClickUpProperties.ParentStatus(false, List.of(), List.of(), "in progress", List.of(),
                                "ready for testing"),
                        new ClickUpProperties.CreateDefaults(false, List.of()),
                        new ClickUpProperties.RejectionReopen(false, List.of(), List.of(), List.of(), List.of(),
                                "waiting info")));
        return new CopyParentFieldsWorkflow(factory, new CustomFieldValueMapper(), properties, MAPPER);
    }

    private static ClickUpEvent event(String taskId) {
        return new ClickUpEvent("key", "wh", "ws", "taskCreated", taskId, List.of());
    }

    private static Task task(String id, String parent, Long customItemId, CustomField... fields) {
        return new Task(id, "name", parent, customItemId, null, null, new IdRef("list"), List.of(), List.of(),
                List.of(fields), List.of());
    }

    private static CustomField field(String id, String value) {
        return new CustomField(id, id, "short_text", null, value == null ? null : MAPPER.valueToTree(value));
    }
}

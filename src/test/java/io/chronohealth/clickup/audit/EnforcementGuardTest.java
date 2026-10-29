package io.chronohealth.clickup.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.chronohealth.clickup.client.ClickUpClient;
import io.chronohealth.clickup.client.ClickUpClientFactory;
import io.chronohealth.clickup.client.dto.CustomTaskType;
import io.chronohealth.clickup.client.dto.CustomField;
import io.chronohealth.clickup.client.dto.IdRef;
import io.chronohealth.clickup.client.dto.Priority;
import io.chronohealth.clickup.client.dto.Tag;
import io.chronohealth.clickup.client.dto.Task;
import io.chronohealth.clickup.client.dto.TaskStatus;
import io.chronohealth.clickup.client.dto.TaskUpdate;
import io.chronohealth.clickup.client.dto.User;
import io.chronohealth.clickup.webhook.ClickUpEvent;
import io.chronohealth.clickup.webhook.ClickUpWebhookPayload.HistoryItem;
import io.chronohealth.clickup.workflow.CustomFieldValueMapper;
import io.chronohealth.clickup.workflow.TaskTypeResolver;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class EnforcementGuardTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final long BUG = 1001L;
    /**
     * Backend dev, not enforced in these test properties.
     */
    private static final long LUIS = 112510284L;
    /**
     * Mobile dev, enforced.
     */
    private static final long VIVEK = 278564675L;

    private final ClickUpClientFactory factory = mock(ClickUpClientFactory.class);
    private final ClickUpClient client = mock(ClickUpClient.class);
    private EnforcementGuard guard;

    @BeforeEach
    void setUp() {
        AuditProperties properties = PermissionRulesTest.properties();
        People people = new People(properties);
        TaskKinds kinds = new TaskKinds(properties);
        Permissions permissions = new Permissions(people);
        guard = new EnforcementGuard(factory, new TaskTypeResolver(), kinds, people, permissions,
                new StreamTagChangeRule(people, kinds, permissions, properties),
                new PriorityLockRule(people, permissions, properties), new CustomFieldValueMapper(), properties);
        when(factory.forWorkspace("ws")).thenReturn(client);
        when(client.getCustomTaskTypes("ws")).thenReturn(List.of(new CustomTaskType(BUG, "Bug")));
        when(client.getTask("mobile")).thenReturn(task("mobile", "Mobile | Login", "bug", null, "review"));
    }

    @Test
    void undoesAForbiddenMoveByAnEnforcedPersonAndBlocksTheEvent() {
        when(client.getTask("bug")).thenReturn(task("bug", "Login bug", null, BUG, "in progress"));

        boolean blocked = guard.blocks(move("bug", VIVEK, "ready for testing", "in progress"));

        assertThat(blocked).isTrue();
        verify(client).updateTask("bug", TaskUpdate.status("ready for testing"));
        verify(client).addCommentMentioning(eq("bug"), eq(VIVEK), contains("I moved it back to 'ready for testing'"));
        verify(client).addCommentMentioning(eq("bug"), eq(VIVEK), contains("See the rules: https://docs.example/rules"));
    }

    @Test
    void allowedMovesAndNonEnforcedPeopleAreLeftAlone() {
        when(client.getTask("bug")).thenReturn(task("bug", "Login bug", null, BUG, "in progress"));

        assertThat(guard.blocks(move("mobile", VIVEK, "in progress", "review"))).isFalse();
        assertThat(guard.blocks(move("bug", LUIS, "to do", "in progress"))).isFalse();
        verify(client, never()).updateTask(anyString(), any());
    }

    @Test
    void doesNotUndoWhenTheTaskMovedOnSince() {
        when(client.getTask("bug")).thenReturn(task("bug", "Login bug", null, BUG, "complete"));

        assertThat(guard.blocks(move("bug", VIVEK, "ready for testing", "in progress"))).isFalse();
        verify(client, never()).updateTask(anyString(), any());
    }

    @Test
    void undoesForbiddenStreamTagChanges() {
        when(client.getTask("bug")).thenReturn(task("bug", "Login bug", null, BUG, "to do", "web"));
        assertThat(guard.blocks(tag("bug", "tag", VIVEK, "web"))).isTrue();
        verify(client).removeTag("bug", "web");

        when(client.getTask("bug")).thenReturn(task("bug", "Login bug", null, BUG, "to do"));
        assertThat(guard.blocks(tag("bug", "tag_removed", VIVEK, "mobile"))).isTrue();
        verify(client).addTag("bug", "mobile");
    }

    @Test
    void tagChangeAlreadyFixedIsLeftAlone() {
        when(client.getTask("bug")).thenReturn(task("bug", "Login bug", null, BUG, "to do"));

        assertThat(guard.blocks(tag("bug", "tag", VIVEK, "web"))).isFalse();
        verify(client, never()).removeTag(anyString(), anyString());
    }

    @Test
    void removesStreamTagsSetOnCreationWithoutBlockingIt() {
        when(client.getTask("feature")).thenReturn(task("feature", "Feature", null, BUG, "in lobby",
                "backend", "mobile", "2.0.0"));

        assertThat(guard.blocks(created("feature", VIVEK))).isFalse();
        verify(client).removeTag("feature", "backend");
        verify(client).removeTag("feature", "mobile");
        verify(client, never()).removeTag("feature", "2.0.0");
        verify(client).addCommentMentioning(eq("feature"), eq(VIVEK),
                contains("you created this with the backend, mobile tag"));
    }

    @Test
    void creationWithoutStreamTagsIsLeftAlone() {
        when(client.getTask("feature")).thenReturn(task("feature", "Feature", null, BUG, "in lobby", "2.0.0"));

        assertThat(guard.blocks(created("feature", VIVEK))).isFalse();
        verify(client, never()).removeTag(anyString(), anyString());
        verify(client, never()).addCommentMentioning(anyString(), anyLong(), anyString());
    }

    @Test
    void undoesAPriorityChangeOnAStartedTask() {
        when(client.getTask("bug")).thenReturn(withPriority(task("bug", "Login bug", null, BUG, "in progress"), "1"));

        boolean blocked = guard.blocks(priorityChange("bug", VIVEK, "3", "1"));

        assertThat(blocked).isTrue();
        verify(client).setPriority("bug", 3);
        verify(client).addCommentMentioning(eq("bug"), eq(VIVEK), contains("you changed the priority while work had started"));
    }

    @Test
    void priorityChangesBeforeWorkStartsOrByAdminsAreLeftAlone() {
        when(client.getTask("bug")).thenReturn(withPriority(task("bug", "Login bug", null, BUG, "to do"), "1"));
        assertThat(guard.blocks(priorityChange("bug", VIVEK, "3", "1"))).isFalse();

        when(client.getTask("bug")).thenReturn(withPriority(task("bug", "Login bug", null, BUG, "in progress"), "1"));
        assertThat(guard.blocks(priorityChange("bug", LUIS, "3", "1"))).isFalse(); // not enforced

        verify(client, never()).setPriority(anyString(), any());
    }

    @Test
    void undoesAProductPriorityChangeOnAStartedTask() {
        CustomField field = new CustomField("f-pp", "Product Priority", "drop_down",
                MAPPER.readTree("{\"options\":[{\"id\":\"opt-5\",\"orderindex\":0},{\"id\":\"opt-4\",\"orderindex\":1}]}"),
                MAPPER.readTree("1"));
        Task bug = task("bug", "Login bug", null, BUG, "review");
        when(client.getTask("bug")).thenReturn(new Task(bug.id(), bug.name(), null, BUG, bug.status(), null,
                bug.list(), List.of(), List.of(), List.of(field), List.of()));
        HistoryItem item = new HistoryItem("h", "1", null, "custom_field", null, new User(VIVEK),
                MAPPER.readTree("\"opt-5\""), MAPPER.readTree("\"opt-4\""), null, null, null,
                MAPPER.readTree("{\"id\":\"f-pp\",\"name\":\"Product Priority\",\"type\":\"drop_down\"}"));

        assertThat(guard.blocks(new ClickUpEvent("k", "wh", "ws", "taskUpdated", "bug", List.of(item)))).isTrue();
        verify(client).setCustomField("bug", "f-pp", "opt-5");
    }

    private static ClickUpEvent priorityChange(String taskId, long userId, String from, String to) {
        HistoryItem item = new HistoryItem("h", "1", null, "priority", null, new User(userId),
                MAPPER.readTree("{\"id\":\"" + from + "\"}"), MAPPER.readTree("{\"id\":\"" + to + "\"}"));
        return new ClickUpEvent("k", "wh", "ws", "taskPriorityUpdated", taskId, List.of(item));
    }

    private static Task withPriority(Task t, String priority) {
        return new Task(t.id(), t.name(), t.parent(), t.customItemId(), t.status(), new Priority(priority, null),
                t.list(), t.assignees(), t.tags(), t.customFields(), t.subtasks());
    }

    private static ClickUpEvent created(String taskId, long userId) {
        HistoryItem item = new HistoryItem("h", "1", null, "task_creation", null, new User(userId), null, null);
        return new ClickUpEvent("k", "wh", "ws", "taskCreated", taskId, List.of(item));
    }

    private static ClickUpEvent move(String taskId, long userId, String from, String to) {
        HistoryItem item = new HistoryItem("h", "1", null, "status", null, new User(userId),
                MAPPER.readTree("{\"status\":\"" + from + "\"}"), MAPPER.readTree("{\"status\":\"" + to + "\"}"));
        return new ClickUpEvent("k", "wh", "ws", "taskStatusUpdated", taskId, List.of(item));
    }

    private static ClickUpEvent tag(String taskId, String field, long userId, String tag) {
        HistoryItem item = new HistoryItem("h", "1", null, field, null, new User(userId), null,
                MAPPER.readTree("[{\"name\":\"" + tag + "\"}]"));
        return new ClickUpEvent("k", "wh", "ws", "taskTagUpdated", taskId, List.of(item));
    }

    private static Task task(String id, String name, String parent, Long type, String status, String... tags) {
        return new Task(id, name, parent, type, new TaskStatus(status, null, null), null, new IdRef("list"),
                List.of(), Arrays.stream(tags).map(Tag::new).toList(), List.of(), List.of());
    }
}

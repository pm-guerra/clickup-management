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
import io.chronohealth.clickup.client.dto.IdRef;
import io.chronohealth.clickup.client.dto.Tag;
import io.chronohealth.clickup.client.dto.Task;
import io.chronohealth.clickup.client.dto.TaskStatus;
import io.chronohealth.clickup.client.dto.TaskUpdate;
import io.chronohealth.clickup.client.dto.User;
import io.chronohealth.clickup.webhook.ClickUpEvent;
import io.chronohealth.clickup.webhook.ClickUpWebhookPayload.HistoryItem;
import io.chronohealth.clickup.workflow.ParentStatusTestSupport;
import io.chronohealth.clickup.workflow.ParentStatuses;
import io.chronohealth.clickup.workflow.TaskTypeResolver;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class StatusRulesGuardTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final long BUG = 1001L;
    private static final long EPIC = 1003L;
    private static final long PEDRO = 100796657L;
    private static final long MARTIM = 106791322L;

    private final ClickUpClientFactory factory = mock(ClickUpClientFactory.class);
    private final ClickUpClient client = mock(ClickUpClient.class);
    private StatusRulesGuard guard;

    @BeforeEach
    void setUp() {
        AuditProperties properties = ParentStatusTestSupport.auditProperties();
        TaskKinds kinds = new TaskKinds(properties);
        ParentStatuses parentStatuses = new ParentStatuses(kinds, ParentStatusTestSupport.clickUpProperties());
        guard = new StatusRulesGuard(factory, new TaskTypeResolver(), new People(properties),
                new StatusPlacement(kinds, parentStatuses), parentStatuses, properties);
        when(factory.forWorkspace("ws")).thenReturn(client);
        when(client.getCustomTaskTypes("ws")).thenReturn(List.of(
                new CustomTaskType(BUG, "Bug"), new CustomTaskType(EPIC, "Epic")));
    }

    @Test
    void undoesParentMovedAheadOfItsSubtasks() {
        bug("in testing", task("s1", "bug", null, "review"), task("s2", "bug", null, "complete"));

        assertThat(guard.blocks(move("bug", MARTIM, "review", "in testing"))).isTrue();

        verify(client).updateTask("bug", TaskUpdate.status("review"));
        verify(client).addCommentMentioning(eq("bug"), eq(MARTIM), contains("can't be ahead of its subtasks"));
    }

    @Test
    void allowsMovingAParentBack() {
        bug("in progress", task("s1", "bug", null, "complete"));

        assertThat(guard.blocks(move("bug", MARTIM, "in testing", "in progress"))).isFalse();

        verify(client, never()).updateTask(anyString(), any());
    }

    @Test
    void undoesStreamTaskMovedToReadyForTesting() {
        bug("in progress");
        stream("web", "in testing");

        assertThat(guard.blocks(move("web", MARTIM, "complete", "in testing"))).isTrue();

        verify(client).updateTask("web", TaskUpdate.status("complete"));
        verify(client).addCommentMentioning(eq("web"), eq(MARTIM), contains("can be in 'in testing'"));
    }

    @Test
    void undoesEpicMovedToReadyForTesting() {
        when(client.getTask("epic")).thenReturn(task("epic", null, EPIC, "in testing"));

        assertThat(guard.blocks(move("epic", MARTIM, "in progress", "in testing"))).isTrue();

        verify(client).updateTask("epic", TaskUpdate.status("in progress"));
    }

    @Test
    void readyForDevIsOnlyForMobile() {
        bug("in progress");
        stream("mobile", "ready_for_dev");
        assertThat(guard.blocks(move("mobile", MARTIM, "review", "ready_for_dev"))).isFalse();

        stream("web", "ready_for_dev");
        assertThat(guard.blocks(move("web", MARTIM, "review", "ready_for_dev"))).isTrue();
        verify(client).updateTask("web", TaskUpdate.status("review"));
        verify(client, never()).updateTask(eq("mobile"), any());
    }

    @Test
    void pedroIsNotRestricted() {
        bug("in testing", task("s1", "bug", null, "review"));
        when(client.getTask("epic")).thenReturn(task("epic", null, EPIC, "in testing"));

        assertThat(guard.blocks(move("bug", PEDRO, "review", "in testing"))).isFalse();
        assertThat(guard.blocks(move("epic", PEDRO, "in progress", "in testing"))).isFalse();

        verify(client, never()).updateTask(anyString(), any());
        verify(client, never()).addCommentMentioning(anyString(), anyLong(), anyString());
    }

    private void bug(String status, Task... subtasks) {
        Task bug = new Task("bug", "name", null, BUG, new TaskStatus(status, null, null), null, new IdRef("list"),
                List.of(), List.of(), List.of(), List.of(subtasks));
        when(client.getTask("bug")).thenReturn(bug);
        when(client.getTask("bug", true)).thenReturn(bug);
    }

    private void stream(String stream, String status) {
        Task task = new Task(stream, "name", "bug", null, new TaskStatus(status, null, null), null,
                new IdRef("list"), List.of(), List.of(new Tag(stream)), List.of(), List.of());
        when(client.getTask(stream)).thenReturn(task);
        when(client.getTask(stream, true)).thenReturn(task);
    }

    private static Task task(String id, String parent, Long type, String status) {
        return new Task(id, "name", parent, type, new TaskStatus(status, null, null), null, new IdRef("list"),
                List.of(), List.of(), List.of(), List.of());
    }

    private static ClickUpEvent move(String taskId, long userId, String from, String to) {
        HistoryItem item = new HistoryItem("h-" + taskId + userId + to, "1", "0", "status", null, new User(userId),
                MAPPER.valueToTree(Map.of("status", from)), MAPPER.valueToTree(Map.of("status", to)));
        return new ClickUpEvent("key", "wh", "ws", "taskStatusUpdated", taskId, List.of(item));
    }
}

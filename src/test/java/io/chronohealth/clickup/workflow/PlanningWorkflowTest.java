package io.chronohealth.clickup.workflow;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.chronohealth.clickup.audit.AuditProperties;
import io.chronohealth.clickup.audit.People;
import io.chronohealth.clickup.client.ClickUpClient;
import io.chronohealth.clickup.client.ClickUpClientFactory;
import io.chronohealth.clickup.client.dto.AssigneesUpdate;
import io.chronohealth.clickup.client.dto.CustomField;
import io.chronohealth.clickup.client.dto.CustomTaskType;
import io.chronohealth.clickup.client.dto.IdRef;
import io.chronohealth.clickup.client.dto.Task;
import io.chronohealth.clickup.client.dto.TaskStatus;
import io.chronohealth.clickup.client.dto.TaskUpdate;
import io.chronohealth.clickup.client.dto.User;
import io.chronohealth.clickup.webhook.ClickUpEvent;
import io.chronohealth.clickup.webhook.ClickUpWebhookPayload.HistoryItem;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class PlanningWorkflowTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final long FEATURE = 1005L;
    private static final long BUG = 1001L;
    private static final long DEFECT = 1010L;
    private static final long PEDRO = 100796657L;
    private static final long JOSE = 106501885L;
    private static final long SEBASTIAN = 308507562L;
    private static final long MARTIM = 106791322L;
    private static final long LUIS = 112510284L;

    private final ClickUpClientFactory factory = mock(ClickUpClientFactory.class);
    private final ClickUpClient client = mock(ClickUpClient.class);
    private final PlanningWorkflow workflow = new PlanningWorkflow(factory, new TaskTypeResolver(),
            new People(auditProperties()), ParentStatusTestSupport.planning(),
            ParentStatusTestSupport.clickUpProperties());

    @BeforeEach
    void setUp() {
        when(factory.forWorkspace("ws")).thenReturn(client);
        when(client.getCustomTaskTypes("ws")).thenReturn(List.of(new CustomTaskType(FEATURE, "Feature"),
                new CustomTaskType(BUG, "Bug"), new CustomTaskType(DEFECT, "Defect")));
    }

    @Test
    void movedToLobbyEveryoneIsUnassigned() {
        stub(task("f", FEATURE, "in lobby", List.of(MARTIM, JOSE)));

        workflow.handle(move(MARTIM, "pending", "in lobby"));

        verify(client).updateTask("f", TaskUpdate.assignees(new AssigneesUpdate(List.of(), List.of(MARTIM, JOSE))));
        verify(client, never()).addCommentMentioning(anyString(), anyLong(), anyString());
    }

    @Test
    void inLobbyOnlyProductAndPlanningMayBeAssigned() {
        stub(task("f", FEATURE, "in lobby", List.of(MARTIM, SEBASTIAN, LUIS)));

        workflow.handle(assigneeChanged(MARTIM));

        verify(client).updateTask("f", TaskUpdate.assignees(new AssigneesUpdate(List.of(), List.of(LUIS))));
        verify(client).addCommentMentioning(eq("f"), eq(MARTIM), contains("only the Product and Planning teams"));
    }

    @Test
    void anAdminMayAssignAnyoneAndAnEmptyLobbyIsFine() {
        stub(task("f", FEATURE, "in lobby", List.of(LUIS)));
        workflow.handle(assigneeChanged(PEDRO));
        stub(task("f", FEATURE, "in lobby", List.of()));
        workflow.handle(assigneeChanged(SEBASTIAN));

        verify(client, never()).updateTask(anyString(), any());
    }

    @Test
    void movedToToDoItsSubtasksWaitingInLobbyFollowButNotDefects() {
        stub(task("f", FEATURE, "to do", List.of(), sub("web", null, "in lobby"), sub("mobile", null, "pending"),
                sub("done", null, "in progress"), sub("defect", DEFECT, "in lobby")));

        workflow.handle(move(SEBASTIAN, "in lobby", "to do"));

        verify(client).updateTask("web", TaskUpdate.status("to do"));
        verify(client).updateTask("mobile", TaskUpdate.status("to do"));
        verify(client, never()).updateTask(eq("done"), any());
        verify(client, never()).updateTask(eq("defect"), any());
    }

    @Test
    void thePlannerWhoStartsTheWorkIsRecorded() {
        stub(withPlanner(task("f", FEATURE, "to do", List.of()), "[{\"id\":" + PEDRO + "}]"));

        workflow.handle(move(SEBASTIAN, "in lobby", "to do"));

        verify(client).setCustomField("f", "f-planner", Map.of("add", List.of(SEBASTIAN), "rem", List.of(PEDRO)));
    }

    @Test
    void movesByOthersOrAlreadyRecordedPlannersChangeNothing() {
        stub(withPlanner(task("f", FEATURE, "to do", List.of()), "[{\"id\":" + SEBASTIAN + "}]"));
        workflow.handle(move(SEBASTIAN, "in lobby", "to do"));
        stub(withPlanner(task("f", FEATURE, "to do", List.of()), null));
        workflow.handle(move(MARTIM, "in lobby", "to do"));

        verify(client, never()).setCustomField(anyString(), anyString(), any());
    }

    @Test
    void aBugTakesItsSubtasksWaitingInLobbyAlongToToDo() {
        stub(task("f", BUG, "to do", List.of(MARTIM), sub("web", null, "in lobby"), sub("done", null, "review")));

        workflow.handle(move(MARTIM, "in lobby", "to do"));

        verify(client).updateTask("web", TaskUpdate.status("to do"));
        verify(client, never()).updateTask(eq("done"), any());
        verify(client, never()).setCustomField(anyString(), anyString(), any());
    }

    @Test
    void bugsAndTasksThatMovedOnAreLeftAlone() {
        stub(task("f", BUG, "in lobby", List.of(MARTIM)));
        workflow.handle(move(MARTIM, "pending", "in lobby"));
        stub(task("f", FEATURE, "pending", List.of(MARTIM)));
        workflow.handle(move(MARTIM, "pending", "in lobby"));

        verify(client, never()).updateTask(anyString(), any());
    }

    private void stub(Task task) {
        when(client.getTask(task.id(), true)).thenReturn(task);
    }

    private static Task task(String id, long type, String status, List<Long> assignees, Task... subtasks) {
        return new Task(id, id, null, type, new TaskStatus(status, null, null), null, new IdRef("list"),
                assignees.stream().map(User::new).toList(), List.of(), List.of(), Arrays.asList(subtasks));
    }

    private static Task withPlanner(Task t, String value) {
        CustomField planner = new CustomField("f-planner", "Planner", "users", null,
                value == null ? null : MAPPER.readTree(value));
        return new Task(t.id(), t.name(), t.parent(), t.customItemId(), t.status(), t.priority(), t.list(),
                t.assignees(), t.tags(), List.of(planner), t.subtasks());
    }

    private static Task sub(String id, Long type, String status) {
        return new Task(id, id, "f", type, new TaskStatus(status, null, null), null, new IdRef("list"), List.of(),
                List.of(), List.of(), List.of());
    }

    private static ClickUpEvent move(long userId, String from, String to) {
        HistoryItem item = new HistoryItem("h", "1", null, "status", null, new User(userId),
                MAPPER.readTree("{\"status\":\"" + from + "\"}"), MAPPER.readTree("{\"status\":\"" + to + "\"}"));
        return new ClickUpEvent("k", "wh", "ws", "taskStatusUpdated", "f", List.of(item));
    }

    private static ClickUpEvent assigneeChanged(long userId) {
        HistoryItem item = new HistoryItem("h", "1", null, "assignee_add", null, new User(userId), null, null);
        return new ClickUpEvent("k", "wh", "ws", "taskAssigneeUpdated", "f", List.of(item));
    }

    private static AuditProperties auditProperties() {
        AuditProperties base = ParentStatusTestSupport.auditProperties();
        return new AuditProperties(base.enabled(), base.initialLookback(), base.overlap(), base.apiPacing(),
                base.maxPages(), base.listIds(), base.notifyChannelId(), base.notifyUserId(), base.rulesDocUrl(),
                List.of(new AuditProperties.Person("100796657", "Pedro", "admin", null, null, true,
                                List.of("planning", "tech")),
                        new AuditProperties.Person("106501885", "José", "admin", null, null, null,
                                List.of("planning", "tech")),
                        new AuditProperties.Person("308507562", "Sebastian", "member", "mobile", true, null,
                                List.of("planning", "tech")),
                        new AuditProperties.Person("106791322", "Martim", "member", null, true, null,
                                List.of("product", "testers")),
                        new AuditProperties.Person("112510284", "Luís", "member", "backend", true, null,
                                List.of("tech"))),
                base.mainTaskTypes(), base.streams(), base.scheduler(), base.rules());
    }
}

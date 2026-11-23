package io.chronohealth.clickup.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.chronohealth.clickup.client.ClickUpClient;
import io.chronohealth.clickup.client.dto.CustomTaskType;
import io.chronohealth.clickup.client.dto.IdRef;
import io.chronohealth.clickup.client.dto.Tag;
import io.chronohealth.clickup.client.dto.Task;
import io.chronohealth.clickup.client.dto.TaskStatus;
import io.chronohealth.clickup.client.dto.User;
import io.chronohealth.clickup.webhook.ClickUpEvent;
import io.chronohealth.clickup.webhook.ClickUpWebhookPayload.HistoryItem;
import io.chronohealth.clickup.workflow.ParentStatusTestSupport;
import io.chronohealth.clickup.workflow.TaskTypeResolver;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class PermissionRulesTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final long BUG = 1001L;
    private static final long STORY = 1004L;
    private static final long FEATURE = 1005L;
    private static final long PEDRO = 100796657L;
    private static final long MARTIM = 106791322L;
    private static final long SEBASTIAN = 308507562L;
    private static final long NUBELSON = 106773419L;
    private static final long LUIS = 112510284L;
    private static final long VIVEK = 278564675L;
    private static final long STRANGER = 999L;

    private final ClickUpClient client = mock(ClickUpClient.class);
    private final AuditProperties properties = properties();
    private final People people = new People(properties);
    private final TaskKinds kinds = new TaskKinds(properties);
    private final Permissions permissions = new Permissions(people, ParentStatusTestSupport.planning());
    private final StatusMoveRule statusRule = new StatusMoveRule(people, kinds, permissions, properties);
    private final StreamTagChangeRule tagRule = new StreamTagChangeRule(people, kinds, permissions, properties);
    private AuditContext context;

    @BeforeEach
    void setUp() {
        when(client.getCustomTaskTypes("ws")).thenReturn(List.of(new CustomTaskType(BUG, "Bug"),
                new CustomTaskType(STORY, "Story"), new CustomTaskType(FEATURE, "Feature")));
        context = new AuditContext(client, "ws", new TaskTypeResolver(), Duration.ZERO);
        context.remember(task("bug", "Login bug", null, BUG));
        context.remember(task("feature", "Sign up", null, FEATURE));
        context.remember(task("mobile", "Mobile | Login bug", "bug", null, "mobile"));
        context.remember(task("legacy", "Backend", "bug", null));
        context.remember(task("web", "Web | Login bug", "bug", null, "web"));
        context.remember(task("subsub", "Fix the API call", "mobile", null));
        context.remember(task("nested", "Nested bug", "story", BUG));
        context.remember(task("story", "Story", null, STORY));
        context.remember(task("plain", "Some task", null, null));
    }

    @Test
    void testersAndAdminsMoveBugs() {
        assertThat(statusRule.check(move("bug", MARTIM), context)).isEmpty();
        assertThat(statusRule.check(move("bug", PEDRO), context)).isEmpty();
        assertThat(statusRule.check(move("nested", MARTIM), context)).isEmpty();

        assertThat(statusRule.check(move("bug", LUIS, "in progress", "waiting info"), context)).singleElement()
                .asString().contains("Luís (backend dev) moved it from 'in progress' to 'waiting info'")
                .contains("Only testers and admins may move Bugs, Defects and Epics.");
    }

    @Test
    void productAndPlanningTeamsMoveAFeatureBetweenPendingAndInLobby() {
        assertThat(statusRule.check(move("feature", MARTIM, "pending", "in lobby"), context)).isEmpty();
        assertThat(statusRule.check(move("feature", SEBASTIAN, "in lobby", "pending"), context)).isEmpty();
        assertThat(statusRule.check(move("feature", NUBELSON, "pending", "in lobby"), context)).isEmpty();

        assertThat(statusRule.check(move("feature", LUIS, "pending", "in lobby"), context)).singleElement()
                .asString().contains("Only the Product and Planning teams and admins");
    }

    @Test
    void onlyThePlanningTeamMovesAFeatureToToDo() {
        assertThat(statusRule.check(move("feature", SEBASTIAN, "in lobby", "to do"), context)).isEmpty();
        assertThat(statusRule.check(move("feature", PEDRO, "pending", "to do"), context)).isEmpty();

        assertThat(statusRule.check(move("feature", MARTIM, "in lobby", "to do"), context)).singleElement()
                .asString().contains("Only the Planning team and admins");
        // Skipping in lobby isn't a planner's move either.
        assertThat(statusRule.check(move("feature", SEBASTIAN, "pending", "to do"), context)).singleElement()
                .asString().contains("Only admins");
    }

    @Test
    void testersApproveAFeatureAndNobodyElseMovesItOnceStarted() {
        assertThat(statusRule.check(move("feature", MARTIM, "in testing", "approved"), context)).isEmpty();
        assertThat(statusRule.check(move("feature", MARTIM, "approved", "complete"), context)).isEmpty();

        assertThat(statusRule.check(move("feature", MARTIM, "to do", "in progress"), context)).singleElement()
                .asString().contains("Only admins").contains("follows its subtasks");
        assertThat(statusRule.check(move("feature", SEBASTIAN, "in testing", "approved"), context)).hasSize(1);
    }

    @Test
    void theTechTeamMovesAnyStreamTask() {
        assertThat(statusRule.check(move("mobile", VIVEK), context)).isEmpty();
        assertThat(statusRule.check(move("web", VIVEK), context)).isEmpty();
        assertThat(statusRule.check(move("mobile", LUIS), context)).isEmpty();
        assertThat(statusRule.check(move("subsub", NUBELSON), context)).isEmpty();
        assertThat(statusRule.check(move("legacy", SEBASTIAN, "in progress", "waiting info"), context)).isEmpty();

        assertThat(statusRule.check(move("subsub", MARTIM), context)).singleElement().asString()
                .contains("Only the Tech team and admins may move stream tasks.").doesNotContain("Undone");
        // Vivek is enforced: the report says it was undone.
        assertThat(statusRule.check(move("bug", VIVEK), context)).singleElement().asString()
                .endsWith("Undone automatically.");
    }

    @Test
    void initialStatusOnCreationIsNotAMove() {
        HistoryItem created = new HistoryItem("h", "1", null, "status", null, new User(VIVEK), null,
                MAPPER.readTree("{\"status\":\"pending\"}"));
        ClickUpEvent event = new ClickUpEvent("k", "wh", "ws", "taskStatusUpdated", "legacy", List.of(created));

        assertThat(statusRule.check(event, context)).isEmpty();
    }

    @Test
    void otherTasksAndUnknownPeople() {
        assertThat(statusRule.check(move("plain", LUIS), context)).isEmpty();
        assertThat(statusRule.check(move("bug", STRANGER), context)).singleElement().asString().contains("(no role)");
    }

    @Test
    void streamTagsStillThereFromACreationAreReported() {
        context.remember(task("tagged", "New feature", null, STORY, "backend", "2.0.0"));
        HistoryItem item = new HistoryItem("h", "1", null, "task_creation", null, new User(STRANGER), null, null);
        ClickUpEvent created = new ClickUpEvent("k", "wh", "ws", "taskCreated", "tagged", List.of(item));

        assertThat(tagRule.check(created, context)).singleElement().asString()
                .contains("created it with the 'backend' tag");
        HistoryItem byAdmin = new HistoryItem("h", "1", null, "task_creation", null, new User(PEDRO), null, null);
        assertThat(tagRule.check(new ClickUpEvent("k", "wh", "ws", "taskCreated", "tagged", List.of(byAdmin)),
                context)).isEmpty();
    }

    @Test
    void onlyThePlanningTeamAndAdminsChangeStreamTags() {
        assertThat(tagRule.check(tag("bug", "tag", PEDRO, "web"), context)).isEmpty();
        assertThat(tagRule.check(tag("bug", "tag", SEBASTIAN, "mobile"), context)).isEmpty();
        assertThat(tagRule.check(tag("bug", "tag", MARTIM, "urgent"), context)).isEmpty();

        assertThat(tagRule.check(tag("bug", "tag", MARTIM, "web"), context)).singleElement().asString()
                .isEqualTo("Martim (tester) added the 'web' tag. Only the Planning team and admins may add or remove"
                        + " stream tags.");
        assertThat(tagRule.check(tag("bug", "tag_removed", VIVEK, "Mobile"), context)).singleElement().asString()
                .contains("removed the 'mobile' tag");
    }

    private static ClickUpEvent move(String taskId, long userId) {
        return move(taskId, userId, "to do", "in progress");
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

    private static Task task(String id, String name, String parent, Long type, String... tags) {
        return new Task(id, name, parent, type, new TaskStatus("to do", null, null), null, new IdRef("list"),
                List.of(), java.util.Arrays.stream(tags).map(Tag::new).toList(), List.of(), List.of());
    }

    static AuditProperties properties() {
        return new AuditProperties(true, Duration.ofDays(7), Duration.ofMinutes(5), Duration.ZERO, 5, List.of(),
                "", "", "https://docs.example/rules",
                List.of(new AuditProperties.Person("100796657", "Pedro", "admin", null, null, null, null),
                        new AuditProperties.Person("106791322", "Martim", "tester", null, null, null,
                                List.of("product")),
                        new AuditProperties.Person("308507562", "Sebastian", "member", "mobile", true, null,
                                List.of("planning", "tech")),
                        new AuditProperties.Person("106773419", "Nubelson", "member", "web", true, null,
                                List.of("product", "tech")),
                        new AuditProperties.Person("112510284", "Luís", "dev", "backend", null, null, null),
                        new AuditProperties.Person("278564675", "Vivek", "dev", "mobile", true, null, null)),
                List.of("Story", "Bug", "Change", "Feature", "Epic"), List.of("backend", "web", "mobile"),
                new AuditProperties.Scheduler(false),
                new AuditProperties.Rules(
                        new AuditProperties.StreamTaskStatus(true, List.of("Story", "Bug", "Change", "Feature"), List.of("Task"),
                                List.of("to do", "in progress", "review", "complete", "waiting info")),
                        new AuditProperties.Toggle(true), new AuditProperties.Toggle(true),
                        new AuditProperties.Toggle(true), new AuditProperties.Toggle(true),
                        new AuditProperties.Defects(true, List.of("Defect"), List.of("Feature", "Bug", "Change"), true),
                        new AuditProperties.PriorityLock(true, List.of("in progress", "review"),
                                List.of("Product Priority"))));
    }
}

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
    private static final long PEDRO = 100796657L;
    private static final long MARTIM = 106791322L;
    private static final long LUIS = 112510284L;
    private static final long VIVEK = 278564675L;
    private static final long STRANGER = 999L;

    private final ClickUpClient client = mock(ClickUpClient.class);
    private final AuditProperties properties = properties();
    private final People people = new People(properties);
    private final TaskKinds kinds = new TaskKinds(properties);
    private final StatusMoveRule statusRule = new StatusMoveRule(people, kinds, properties);
    private final StreamTagChangeRule tagRule = new StreamTagChangeRule(people, kinds, properties);
    private AuditContext context;

    @BeforeEach
    void setUp() {
        when(client.getCustomTaskTypes("ws")).thenReturn(List.of(
                new CustomTaskType(BUG, "Bug"), new CustomTaskType(STORY, "Story")));
        context = new AuditContext(client, "ws", new TaskTypeResolver(), Duration.ZERO);
        context.remember(task("bug", "Login bug", null, BUG));
        context.remember(task("mobile", "Mobile | Login bug", "bug", null, "mobile"));
        context.remember(task("legacy", "Backend", "bug", null));
        context.remember(task("subsub", "Fix the API call", "mobile", null));
        context.remember(task("nested", "Nested bug", "story", BUG));
        context.remember(task("story", "Story", null, STORY));
        context.remember(task("plain", "Some task", null, null));
    }

    @Test
    void testersAndAdminsMoveMainTasks() {
        assertThat(statusRule.check(move("bug", MARTIM), context)).isEmpty();
        assertThat(statusRule.check(move("bug", PEDRO), context)).isEmpty();
        assertThat(statusRule.check(move("nested", MARTIM), context)).isEmpty();

        assertThat(statusRule.check(move("bug", LUIS), context)).singleElement().asString()
                .contains("Luís (backend dev) moved it from 'to do' to 'in progress'")
                .contains("Only testers and admins");
    }

    @Test
    void devsMoveOnlyTheirStream() {
        assertThat(statusRule.check(move("mobile", VIVEK), context)).isEmpty();
        assertThat(statusRule.check(move("subsub", VIVEK), context)).isEmpty();
        assertThat(statusRule.check(move("legacy", LUIS), context)).isEmpty();

        assertThat(statusRule.check(move("mobile", LUIS), context)).singleElement().asString()
                .contains("Only mobile devs and admins");
        assertThat(statusRule.check(move("subsub", MARTIM), context)).hasSize(1);
        assertThat(statusRule.check(move("legacy", VIVEK), context)).hasSize(1);
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
    void onlyAdminsChangeStreamTags() {
        assertThat(tagRule.check(tag("bug", "tag", PEDRO, "web"), context)).isEmpty();
        assertThat(tagRule.check(tag("bug", "tag", MARTIM, "urgent"), context)).isEmpty();

        assertThat(tagRule.check(tag("bug", "tag", MARTIM, "web"), context)).singleElement().asString()
                .isEqualTo("Martim (tester) added the 'web' tag. Only admins may add or remove stream tags.");
        assertThat(tagRule.check(tag("bug", "tag_removed", VIVEK, "Mobile"), context)).singleElement().asString()
                .contains("removed the 'mobile' tag");
    }

    private static ClickUpEvent move(String taskId, long userId) {
        HistoryItem item = new HistoryItem("h", "1", null, "status", null, new User(userId),
                MAPPER.readTree("{\"status\":\"to do\"}"), MAPPER.readTree("{\"status\":\"in progress\"}"));
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
                "", "",
                List.of(new AuditProperties.Person("100796657", "Pedro", "admin", null),
                        new AuditProperties.Person("106791322", "Martim", "tester", null),
                        new AuditProperties.Person("112510284", "Luís", "dev", "backend"),
                        new AuditProperties.Person("278564675", "Vivek", "dev", "mobile")),
                List.of("Story", "Bug", "Change", "Epic"), List.of("backend", "web", "mobile"),
                new AuditProperties.Scheduler(false),
                new AuditProperties.Rules(
                        new AuditProperties.StreamTaskStatus(true, List.of("Story", "Bug", "Change"), List.of("Task"),
                                List.of("to do", "in progress", "review", "complete", "waiting info")),
                        new AuditProperties.Toggle(true), new AuditProperties.Toggle(true)));
    }
}

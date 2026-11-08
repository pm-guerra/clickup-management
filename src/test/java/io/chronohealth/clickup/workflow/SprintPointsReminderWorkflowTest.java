package io.chronohealth.clickup.workflow;

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
import io.chronohealth.clickup.client.dto.Task;
import io.chronohealth.clickup.client.dto.TaskStatus;
import io.chronohealth.clickup.client.dto.User;
import io.chronohealth.clickup.config.ClickUpProperties;
import io.chronohealth.clickup.mention.MentionProperties;
import io.chronohealth.clickup.webhook.ClickUpEvent;
import io.chronohealth.clickup.webhook.ClickUpWebhookPayload.HistoryItem;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class SprintPointsReminderWorkflowTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final long BUG = 1001L;
    private static final long STORY = 1002L;
    private static final long MARTIM = 7L;
    private static final long BOT = 106723346L;

    private final ClickUpClientFactory factory = mock(ClickUpClientFactory.class);
    private final ClickUpClient client = mock(ClickUpClient.class);
    private final SprintPointsReminderWorkflow workflow = new SprintPointsReminderWorkflow(factory,
            new TaskTypeResolver(), properties(), new MentionProperties(true, BOT, List.of(), 50, Duration.ofDays(7),
                    "eyes", ""));

    @BeforeEach
    void setUp() {
        when(factory.forWorkspace("ws")).thenReturn(client);
        when(client.getCustomTaskTypes("ws")).thenReturn(List.of(new CustomTaskType(BUG, "Bug"),
                new CustomTaskType(STORY, "Story")));
    }

    @Test
    void asksWhoeverMovedABugToToDoWithoutPoints() {
        stub(BUG, "to do", null);

        workflow.handle(move(MARTIM, "to do"));

        verify(client).addCommentMentioning(eq("t"), eq(MARTIM), contains("no sprint points"));
    }

    @Test
    void zeroPointsCountAsNone() {
        stub(BUG, "to do", 0.0);

        workflow.handle(move(MARTIM, "to do"));

        verify(client).addCommentMentioning(eq("t"), eq(MARTIM), anyString());
    }

    @Test
    void asksNothingWhenItHasPointsOrIsNotAFeatureBugOrChange() {
        stub(BUG, "to do", 3.0);
        workflow.handle(move(MARTIM, "to do"));
        stub(STORY, "to do", null);
        workflow.handle(move(MARTIM, "to do"));

        verify(client, never()).addCommentMentioning(anyString(), anyLong(), anyString());
    }

    @Test
    void asksNothingForOtherStatusesTheBotsOwnMovesOrATaskThatMovedOn() {
        stub(BUG, "in progress", null);
        workflow.handle(move(MARTIM, "in progress"));
        workflow.handle(move(MARTIM, "to do")); // already moved on
        stub(BUG, "to do", null);
        workflow.handle(move(BOT, "to do"));

        verify(client, never()).addCommentMentioning(anyString(), anyLong(), anyString());
    }

    private void stub(long type, String status, Double points) {
        when(client.getTask("t")).thenReturn(new Task("t", "t", null, type, new TaskStatus(status, null, null), null,
                new IdRef("list"), List.of(), List.of(), List.of(), List.of(), points));
    }

    private static ClickUpEvent move(long userId, String to) {
        HistoryItem item = new HistoryItem("h", "1", null, "status", null, new User(userId),
                MAPPER.readTree("{\"status\":\"in lobby\"}"), MAPPER.readTree("{\"status\":\"" + to + "\"}"), null,
                null, null);
        return new ClickUpEvent("key", "wh", "ws", "taskStatusUpdated", "t", List.of(item));
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
                        new ClickUpProperties.PrioritySync(false, List.of(), List.of(), List.of()),
                        new ClickUpProperties.PointsReminder(true, List.of("Feature", "Bug", "Change"),
                                List.of("to do"))));
    }
}

package io.chronohealth.clickup.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.chronohealth.clickup.client.ClickUpClient;
import io.chronohealth.clickup.client.ClickUpClient.TaskPage;
import io.chronohealth.clickup.client.ClickUpClientFactory;
import io.chronohealth.clickup.client.dto.CustomTaskType;
import io.chronohealth.clickup.client.dto.IdRef;
import io.chronohealth.clickup.client.dto.Task;
import io.chronohealth.clickup.client.dto.TaskStatus;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = "app.audit.notify-user-id=42")
class AuditJobIntegrationTest {

    private static final long BUG = 1001L;

    @Autowired
    private AuditJob job;
    @Autowired
    private AuditRepository repository;
    @Autowired
    private JdbcClient jdbc;
    @MockitoBean
    private ClickUpClientFactory factory;

    private final ClickUpClient client = mock(ClickUpClient.class);

    @BeforeEach
    void setUp() {
        jdbc.sql("delete from audit_violation").update();
        jdbc.sql("delete from audit_state").update();
        when(factory.forWorkspace(anyString())).thenReturn(client);
        when(client.getCustomTaskTypes(anyString())).thenReturn(List.of(new CustomTaskType(BUG, "Bug")));
        when(client.getOrCreateDirectMessage(anyString(), eq("42"))).thenReturn("dm-1");
    }

    @Test
    void reportsANewViolationOnceAndResolvesItWhenFixed() {
        Task parent = task("bug", "Login bug", null, BUG, "in progress");
        Task wrong = task("s1", "Mobile | Login bug", "bug", null, "ready for testing");
        changed(parent, wrong);

        assertThat(job.run().newIssues()).isEqualTo(1);
        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        verify(client).sendChatMessage(anyString(), eq("dm-1"), message.capture());
        assertThat(message.getValue())
                .contains("1 new issue")
                .contains("[Mobile | Login bug](https://app.clickup.com/t/s1)")
                .contains("'ready for testing'");

        // Still wrong on the next run: not reported again.
        changed(parent, wrong);
        assertThat(job.run().newIssues()).isZero();
        verify(client, times(1)).sendChatMessage(anyString(), anyString(), anyString());

        // Fixed: resolved, nothing open.
        changed(parent, task("s1", "Mobile | Login bug", "bug", null, "review"));
        job.run();
        assertThat(repository.findOpen()).isEmpty();
    }

    @Test
    void sendsNothingWhenEverythingIsAligned() {
        changed(task("bug", "Login bug", null, BUG, "in progress"),
                task("s1", "Mobile | Login bug", "bug", null, "in progress"),
                task("other", "Top-level task", null, null, "ready for testing"));

        assertThat(job.run().tasksChecked()).isEqualTo(3);
        verify(client, never()).sendChatMessage(anyString(), anyString(), anyString());
    }

    @Test
    void subtasksOfOtherTypesAreNotStreamTasks() {
        changed(task("epic", "Epic", null, 2002L, "in progress"),
                task("s1", "Sub", "epic", null, "ready for testing"));

        assertThat(job.run().newIssues()).isZero();
    }

    @Test
    void secondRunOnlyAsksForChangesSinceTheFirst() {
        changed();
        job.run();
        job.run();

        ArgumentCaptor<java.time.Instant> since = ArgumentCaptor.forClass(java.time.Instant.class);
        verify(client, times(2)).getTasksUpdatedSince(anyString(), since.capture(), anyList(), anyInt());
        assertThat(since.getAllValues().get(1)).isAfter(since.getAllValues().get(0));
    }

    private void changed(Task... tasks) {
        when(client.getTasksUpdatedSince(anyString(), any(), anyList(), anyInt()))
                .thenReturn(new TaskPage(List.of(tasks), true));
    }

    private static Task task(String id, String name, String parent, Long type, String status) {
        return new Task(id, name, parent, type, new TaskStatus(status, null, null), null, new IdRef("list"),
                List.of(), List.of(), List.of(), List.of());
    }
}

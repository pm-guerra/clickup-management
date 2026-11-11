package io.chronohealth.clickup.webhook;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.chronohealth.clickup.audit.AuditProperties;
import io.chronohealth.clickup.client.ClickUpClient;
import io.chronohealth.clickup.client.ClickUpClientFactory;
import io.chronohealth.clickup.client.dto.IdRef;
import io.chronohealth.clickup.client.dto.Task;
import io.chronohealth.clickup.client.dto.TaskStatus;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class EventDispatcherTest {

    private static final String PRODUCT = "901519246658";

    private final ClickUpClientFactory factory = mock(ClickUpClientFactory.class);
    private final ClickUpClient client = mock(ClickUpClient.class);
    private final EventGuard guard = mock(EventGuard.class);
    private final EventHandler workflow = mock(EventHandler.class);
    private final EventHandler mentions = mock(EventHandler.class);

    @BeforeEach
    void setUp() {
        when(factory.forWorkspace("ws")).thenReturn(client);
        when(workflow.eventTypes()).thenReturn(Set.of("taskCommentPosted"));
        when(mentions.eventTypes()).thenReturn(Set.of("taskCommentPosted"));
        when(mentions.everyList()).thenReturn(true);
        task("product-task", PRODUCT);
        task("tech-task", "tech");
    }

    @Test
    void tasksOnTheManagedListsReachGuardsAndWorkflows() {
        dispatcher(List.of(PRODUCT)).dispatch(event("product-task"));

        verify(guard).blocks(any());
        verify(workflow).handle(any());
        verify(mentions).handle(any());
    }

    @Test
    void tasksElsewhereOnlyReachTheHandlersForEveryList() {
        dispatcher(List.of(PRODUCT)).dispatch(event("tech-task"));

        verify(guard, never()).blocks(any());
        verify(workflow, never()).handle(any());
        verify(mentions).handle(any());
    }

    @Test
    void readsATasksListOnce() {
        EventDispatcher dispatcher = dispatcher(List.of(PRODUCT));

        dispatcher.dispatch(event("tech-task"));
        dispatcher.dispatch(event("tech-task"));

        verify(client, times(1)).getTask("tech-task");
    }

    @Test
    void noListsMeansTheWholeWorkspace() {
        dispatcher(List.of()).dispatch(event("tech-task"));

        verify(workflow).handle(any());
        verify(client, never()).getTask("tech-task");
    }

    private EventDispatcher dispatcher(List<String> listIds) {
        AuditProperties properties = mock(AuditProperties.class);
        when(properties.listIdsOrEmpty()).thenReturn(listIds);
        return new EventDispatcher(List.of(workflow, mentions), List.of(guard), new ListScope(factory, properties));
    }

    private void task(String id, String listId) {
        when(client.getTask(id)).thenReturn(new Task(id, id, null, null, new TaskStatus("to do", null, null), null,
                new IdRef(listId), List.of(), List.of(), List.of(), List.of()));
    }

    private static ClickUpEvent event(String taskId) {
        return new ClickUpEvent("key", "wh", "ws", "taskCommentPosted", taskId, List.of());
    }
}

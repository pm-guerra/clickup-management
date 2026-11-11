package io.chronohealth.clickup.webhook;

import io.chronohealth.clickup.audit.AuditProperties;
import io.chronohealth.clickup.client.ClickUpClientFactory;
import io.chronohealth.clickup.client.dto.Task;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * The lists the app manages ({@code app.audit.list-ids}, the Product list): events for tasks elsewhere reach no rule
 * or workflow. Empty means the whole Workspace.
 * <p>
 * A task's list is read from ClickUp once and remembered (tasks rarely move lists), so the many events that concern
 * no rule don't each cost a call.
 */
@Component
public class ListScope {

    private static final int MAX_REMEMBERED = 5_000;

    private final ClickUpClientFactory clientFactory;
    private final List<String> listIds;
    private final Map<String, String> listOfTask = Collections.synchronizedMap(
            new LinkedHashMap<>(256, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                    return size() > MAX_REMEMBERED;
                }
            });

    public ListScope(ClickUpClientFactory clientFactory, AuditProperties properties) {
        this.clientFactory = clientFactory;
        this.listIds = properties.listIdsOrEmpty();
    }

    public boolean isRestricted() {
        return !listIds.isEmpty();
    }

    public boolean covers(ClickUpEvent event) {
        if (!isRestricted() || event.taskId() == null) {
            return true;
        }
        String listId = listOfTask.get(event.taskId());
        if (listId == null) {
            Task task = clientFactory.forWorkspace(event.workspaceId()).getTask(event.taskId());
            listId = task.list() == null ? "" : task.list().id();
            listOfTask.put(event.taskId(), listId);
        }
        return listIds.contains(listId);
    }
}

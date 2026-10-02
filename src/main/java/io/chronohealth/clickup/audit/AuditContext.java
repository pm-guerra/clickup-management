package io.chronohealth.clickup.audit;

import io.chronohealth.clickup.client.ClickUpClient;
import io.chronohealth.clickup.client.dto.Task;
import io.chronohealth.clickup.workflow.TaskTypeResolver;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Shared, per-run helpers for audit rules: cached task lookups (paced to respect ClickUp's rate limit) and type
 * names. Tasks fetched by the search are seeded into the cache, so a parent that also changed costs no extra call.
 */
public class AuditContext {

    private final ClickUpClient client;
    private final String workspaceId;
    private final TaskTypeResolver taskTypes;
    private final Duration pacing;
    private final Map<String, Task> tasks = new HashMap<>();
    private long lastCallNanos;

    public AuditContext(ClickUpClient client, String workspaceId, TaskTypeResolver taskTypes, Duration pacing) {
        this.client = client;
        this.workspaceId = workspaceId;
        this.taskTypes = taskTypes;
        this.pacing = pacing;
    }

    public void remember(Task task) {
        tasks.putIfAbsent(task.id(), task);
    }

    public Task task(String taskId) {
        Task cached = tasks.get(taskId);
        if (cached != null) {
            return cached;
        }
        pace();
        Task fetched = client.getTask(taskId);
        tasks.put(taskId, fetched);
        return fetched;
    }

    public Optional<String> typeName(Task task) {
        return taskTypes.typeName(client, workspaceId, task);
    }

    private void pace() {
        long wait = pacing.toNanos() - (System.nanoTime() - lastCallNanos);
        if (lastCallNanos != 0 && wait > 0) {
            try {
                Thread.sleep(Duration.ofNanos(wait));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while pacing ClickUp calls", e);
            }
        }
        lastCallNanos = System.nanoTime();
    }
}

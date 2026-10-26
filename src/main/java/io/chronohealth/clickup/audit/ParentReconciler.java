package io.chronohealth.clickup.audit;

import io.chronohealth.clickup.client.ClickUpClient;
import io.chronohealth.clickup.client.ClickUpClient.TaskPage;
import io.chronohealth.clickup.client.ClickUpClientFactory;
import io.chronohealth.clickup.client.dto.Task;
import io.chronohealth.clickup.client.dto.TaskStatus;
import io.chronohealth.clickup.client.dto.TaskUpdate;
import io.chronohealth.clickup.config.ClickUpProperties;
import io.chronohealth.clickup.workflow.ListStatusResolver;
import io.chronohealth.clickup.workflow.ParentStatusPolicy;
import io.chronohealth.clickup.workflow.ParentStatuses;
import io.chronohealth.clickup.workflow.TaskTypeResolver;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Catch-up for parents whose subtasks last changed before a rule existed: the workflow and the hourly audit only look
 * at recent changes, so such parents stay out of step. Checks every parent in the Workspace (closed ones included)
 * against its subtasks (see {@link ParentStatusPolicy}) and, when asked, moves the ones out of step.
 * <p>
 * All tasks are read in one pass and parents are checked deepest first, so a wrapper Task's new status already counts
 * when its own parent is checked.
 */
@Service
public class ParentReconciler {

    private static final Logger log = LoggerFactory.getLogger(ParentReconciler.class);
    private static final int MAX_PAGES = 200;

    private final ClickUpClientFactory clientFactory;
    private final TaskTypeResolver taskTypes;
    private final ListStatusResolver listStatuses;
    private final ParentStatuses parentStatuses;
    private final ClickUpProperties clickUpProperties;
    private final AuditProperties properties;

    public ParentReconciler(ClickUpClientFactory clientFactory, TaskTypeResolver taskTypes,
                            ListStatusResolver listStatuses, ParentStatuses parentStatuses,
                            ClickUpProperties clickUpProperties, AuditProperties properties) {
        this.clientFactory = clientFactory;
        this.taskTypes = taskTypes;
        this.listStatuses = listStatuses;
        this.parentStatuses = parentStatuses;
        this.clickUpProperties = clickUpProperties;
        this.properties = properties;
    }

    /**
     * @param apply false for a dry run: only lists what would move
     */
    public Result reconcile(boolean apply) {
        String workspaceId = clickUpProperties.workspaceId();
        ClickUpClient client = clientFactory.forWorkspace(workspaceId);
        AuditContext context = new AuditContext(client, workspaceId, taskTypes, properties.apiPacing());

        Map<String, Task> tasks = new LinkedHashMap<>();
        for (int page = 0; page < MAX_PAGES; page++) {
            TaskPage result = client.getTasksUpdatedSince(workspaceId, Instant.EPOCH, List.of(), page);
            result.tasksOrEmpty().forEach(t -> tasks.put(t.id(), t));
            if (result.isLastPage() || result.tasksOrEmpty().isEmpty()) {
                break;
            }
        }
        tasks.values().forEach(context::remember);

        Map<String, List<String>> children = new HashMap<>();
        tasks.values().stream()
                .filter(t -> t.parent() != null && tasks.containsKey(t.parent()))
                .forEach(t -> children.computeIfAbsent(t.parent(), _ -> new ArrayList<>()).add(t.id()));
        List<String> deepestFirst = children.keySet().stream()
                .sorted(Comparator.comparingInt((String id) -> depth(id, tasks)).reversed())
                .toList();

        int checked = 0;
        List<Move> moves = new ArrayList<>();
        for (String id : deepestFirst) {
            Task parent = withSubtasks(tasks.get(id), children.get(id).stream().map(tasks::get).toList());
            if (!parentStatuses.manages(parent, context)) {
                continue;
            }
            checked++;
            Optional<String> derived = parentStatuses.derivedStatus(parent, context);
            String current = parent.status() == null ? "" : parent.status().status();
            Optional<String> target = derived.flatMap(d -> ParentStatusPolicy.move(current, d));
            if (target.isEmpty()) {
                continue;
            }
            String type = context.typeName(parent).orElse("?");
            String url = "https://app.clickup.com/t/" + id;
            if (parent.list() == null || listStatuses.orderOf(client, parent.list().id(), target.get()).isEmpty()) {
                moves.add(new Move(id, url, type, current, target.get(), "skipped: status not on its list"));
                continue;
            }
            moves.add(new Move(id, url, type, current, target.get(), apply ? move(client, id, target.get()) : "would move"));
            // Its parent is checked later and must see the new status.
            tasks.put(id, withStatus(tasks.get(id), target.get()));
        }
        log.info("Parent reconcile ({}): {} task(s) read, {} parent(s) checked, {} out of step", apply ? "applied" : "dry run",
                tasks.size(), checked, moves.size());
        return new Result(apply, tasks.size(), checked, moves);
    }

    private String move(ClickUpClient client, String taskId, String status) {
        try {
            client.updateTask(taskId, TaskUpdate.status(status));
            Thread.sleep(properties.apiPacing());
            return "moved";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "moved";
        } catch (RuntimeException e) {
            log.warn("Couldn't move parent {} to '{}': {}", taskId, status, e.getClass().getSimpleName());
            return "failed: " + e.getClass().getSimpleName();
        }
    }

    private static int depth(String id, Map<String, Task> tasks) {
        int depth = 0;
        Task current = tasks.get(id);
        while (current != null && current.parent() != null && depth < 10) {
            current = tasks.get(current.parent());
            depth++;
        }
        return depth;
    }

    private static Task withSubtasks(Task t, List<Task> subtasks) {
        return new Task(t.id(), t.name(), t.parent(), t.customItemId(), t.status(), t.priority(), t.list(),
                t.assignees(), t.tags(), t.customFields(), subtasks, t.points());
    }

    private static Task withStatus(Task t, String status) {
        return new Task(t.id(), t.name(), t.parent(), t.customItemId(), new TaskStatus(status, null, null),
                t.priority(), t.list(), t.assignees(), t.tags(), t.customFields(), t.subtasks(), t.points());
    }

    /**
     * Task ids and links only, no names.
     */
    public record Result(boolean applied, int tasksRead, int parentsChecked, List<Move> moves) {
    }

    public record Move(String taskId, String url, String type, String from, String to, String outcome) {
    }
}

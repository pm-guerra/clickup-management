package io.chronohealth.clickup.workflow;

import io.chronohealth.clickup.audit.People;
import io.chronohealth.clickup.client.ClickUpClient;
import io.chronohealth.clickup.client.ClickUpClientFactory;
import io.chronohealth.clickup.client.dto.AssigneesUpdate;
import io.chronohealth.clickup.client.dto.CustomField;
import io.chronohealth.clickup.client.dto.Task;
import io.chronohealth.clickup.client.dto.TaskUpdate;
import io.chronohealth.clickup.client.dto.User;
import io.chronohealth.clickup.config.ClickUpProperties;
import io.chronohealth.clickup.config.PlanningProperties;
import io.chronohealth.clickup.webhook.ClickUpEvent;
import io.chronohealth.clickup.webhook.ClickUpWebhookPayload.HistoryItem;
import io.chronohealth.clickup.webhook.EventHandler;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * The planning flow of Features and Changes ({@link PlanningProperties}):
 * <ul>
 *   <li>moved from pending to in lobby: everyone is unassigned (whoever picks it up, from Product or Planning,
 *       assigns themselves);</li>
 *   <li>in lobby, only Product and Planning team members may be assigned: anyone else assigned by someone who isn't
 *       an admin is unassigned again, with a comment;</li>
 *   <li>moved to to do: its stream subtasks still in pending or in lobby go to to do too, and the planner who moved it
 *       is recorded in the Planner field (Octo bot asks them its questions).</li>
 * </ul>
 * Who may make these moves is checked before this runs (see EnforcementGuard), and so is the priority (see
 * StatusRulesGuard): an undone move never gets here.
 */
@Component
public class PlanningWorkflow implements EventHandler {

    static final String STATUS_EVENT = "taskStatusUpdated";
    static final String ASSIGNEE_EVENT = "taskAssigneeUpdated";
    private static final String STATUS_FIELD = "status";

    private static final Logger log = LoggerFactory.getLogger(PlanningWorkflow.class);

    private final ClickUpClientFactory clientFactory;
    private final TaskTypeResolver taskTypes;
    private final People people;
    private final PlanningProperties planning;
    private final List<String> ignoredSubtaskTypes;

    public PlanningWorkflow(ClickUpClientFactory clientFactory, TaskTypeResolver taskTypes, People people,
                            PlanningProperties planning, ClickUpProperties properties) {
        this.clientFactory = clientFactory;
        this.taskTypes = taskTypes;
        this.people = people;
        this.planning = planning;
        this.ignoredSubtaskTypes = properties.workflows().parentStatus().ignoredChildTypesOrEmpty();
    }

    @Override
    public Set<String> eventTypes() {
        return planning.enabled() ? Set.of(STATUS_EVENT, ASSIGNEE_EVENT) : Set.of();
    }

    @Override
    public void handle(ClickUpEvent event) {
        Optional<HistoryItem> move = event.isType(STATUS_EVENT)
                ? event.historyItems().stream().filter(i -> STATUS_FIELD.equals(i.field())).findFirst()
                : Optional.empty();
        if (event.isType(STATUS_EVENT) && move.isEmpty()) {
            return;
        }
        ClickUpClient client = clientFactory.forWorkspace(event.workspaceId());
        Task task = client.getTask(event.taskId(), true);
        Optional<String> type = taskTypes.typeName(client, event.workspaceId(), task);
        if (type.filter(planning::isPlanned).isEmpty()) {
            // A Bug only takes its subtasks still waiting in lobby along to to do.
            if (move.isPresent() && type.filter(planning::subtasksFollow).isPresent()
                    && planning.isToDo(statusName(move.get().after()))
                    && Objects.equals(normalize(statusName(move.get().after())),
                            normalize(task.status() == null ? null : task.status().status()))) {
                startSubtasks(client, event.workspaceId(), task);
            }
            return;
        }
        String current = task.status() == null ? null : task.status().status();
        if (move.isPresent()) {
            String from = statusName(move.get().before());
            String to = statusName(move.get().after());
            if (!Objects.equals(normalize(to), normalize(current))) {
                log.info("Task {} moved on since the move to '{}'; nothing to do", task.id(), to);
            } else if (isPending(from) && planning.isLobby(to)) {
                unassignEveryone(client, task);
            } else if (planning.isToDo(to)) {
                startSubtasks(client, event.workspaceId(), task);
                recordPlanner(client, task, move.get().user());
            }
        } else if (planning.isLobby(current)) {
            removeOutsideAssignees(client, task, lastAssigneeChangeBy(event));
        }
    }

    private void unassignEveryone(ClickUpClient client, Task task) {
        List<Long> assigned = assignees(task);
        if (assigned.isEmpty()) {
            return;
        }
        client.updateTask(task.id(), TaskUpdate.assignees(new AssigneesUpdate(List.of(), assigned)));
        log.info("Unassigned {} people from {} on its way to {}", assigned.size(), task.id(), planning.lobbyStatus());
    }

    /**
     * @param assignedBy who changed the assignees; their changes are left alone if they're an admin
     */
    private void removeOutsideAssignees(ClickUpClient client, Task task, Long assignedBy) {
        if (assignedBy == null || people.isAdmin(assignedBy)) {
            return;
        }
        List<Long> outside = assignees(task).stream()
                .filter(a -> !people.inTeam(a, People.PRODUCT) && !people.inTeam(a, People.PLANNING))
                .toList();
        if (outside.isEmpty()) {
            return;
        }
        client.updateTask(task.id(), TaskUpdate.assignees(new AssigneesUpdate(List.of(), outside)));
        client.addCommentMentioning(task.id(), assignedBy, "in " + planning.lobbyStatus()
                + " only the Product and Planning teams work on a task, so I unassigned whoever else was assigned."
                + " Assign the stream tasks instead once they exist.");
        log.info("Unassigned {} people outside Product and Planning from {} in {}", outside.size(), task.id(),
                planning.lobbyStatus());
    }

    private static List<Long> assignees(Task task) {
        return task.assignees() == null ? List.of()
                : task.assignees().stream().map(User::id).filter(Objects::nonNull).toList();
    }

    private void startSubtasks(ClickUpClient client, String workspaceId, Task task) {
        List<Task> waiting = task.subtasksOrEmpty().stream()
                .filter(s -> task.id().equals(s.parent()))
                .filter(s -> s.status() != null && planning.isBeforePlanningDone(s.status().status()))
                .filter(s -> taskTypes.typeName(client, workspaceId, s)
                        .map(t -> ignoredSubtaskTypes.stream().noneMatch(i -> i.equalsIgnoreCase(t.trim())))
                        .orElse(true))
                .toList();
        for (Task subtask : waiting) {
            client.updateTask(subtask.id(), TaskUpdate.status(planning.toDoStatus()));
        }
        if (!waiting.isEmpty()) {
            log.info("Moved {} subtask(s) of {} to '{}' with it", waiting.size(), task.id(), planning.toDoStatus());
        }
    }

    /**
     * Only a planner moving it counts (admins Pedro and José are planners too); Octo bot's own moves don't.
     */
    private void recordPlanner(ClickUpClient client, Task task, User mover) {
        if (!planning.hasPlannerField() || mover == null || mover.id() == null
                || !people.inTeam(mover.id(), People.PLANNING)) {
            return;
        }
        Optional<CustomField> field = task.customFieldNamed(planning.plannerField());
        if (field.isEmpty()) {
            log.warn("Task {} has no '{}' field; the planner isn't recorded", task.id(), planning.plannerField());
            return;
        }
        List<Long> current = new java.util.ArrayList<>();
        if (field.get().hasValue() && field.get().value().isArray()) {
            field.get().value().forEach(u -> current.add(u.path("id").asLong()));
        }
        if (current.equals(List.of(mover.id()))) {
            return;
        }
        List<Long> remove = current.stream().filter(id -> !id.equals(mover.id())).toList();
        client.setCustomField(task.id(), field.get().id(),
                java.util.Map.of("add", List.of(mover.id()), "rem", remove));
        log.info("Recorded user {} as the planner of {}", mover.id(), task.id());
    }

    private boolean isPending(String status) {
        return status != null && planning.isBeforePlanningDone(status) && !planning.isLobby(status);
    }

    private static Long lastAssigneeChangeBy(ClickUpEvent event) {
        return event.historyItems().stream()
                .map(HistoryItem::user)
                .filter(Objects::nonNull)
                .map(User::id)
                .filter(Objects::nonNull)
                .reduce((first, second) -> second)
                .orElse(null);
    }

    /**
     * ClickUp sends a status as an object ({@code {"status": "to do", ...}}) or, defensively, a string.
     */
    private static String statusName(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        return node.isString() ? node.asString() : node.path("status").asString(null);
    }

    private static String normalize(String status) {
        return status == null ? null : status.trim().toLowerCase(Locale.ROOT);
    }
}

package io.chronohealth.clickup.audit;

import io.chronohealth.clickup.client.ClickUpClient;
import io.chronohealth.clickup.client.ClickUpClientFactory;
import io.chronohealth.clickup.client.dto.Task;
import io.chronohealth.clickup.client.dto.TaskUpdate;
import io.chronohealth.clickup.config.PlanningProperties;
import io.chronohealth.clickup.webhook.ClickUpEvent;
import io.chronohealth.clickup.webhook.ClickUpWebhookPayload.HistoryItem;
import io.chronohealth.clickup.webhook.EventGuard;
import io.chronohealth.clickup.workflow.ParentStatusPolicy;
import io.chronohealth.clickup.workflow.ParentStatuses;
import io.chronohealth.clickup.workflow.TaskTypeResolver;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Undoes status moves that break the status rules, whoever made them (except people marked {@code unrestricted}:
 * Pedro and the app itself):
 * <ul>
 *   <li>a task moved to a status it may not be in ({@link StatusPlacement}): moved back to where it was;</li>
 *   <li>a Feature, Change or Bug moved to to do without a priority ({@link PlanningProperties}): moved back to where
 *       it was;</li>
 *   <li>a parent moved out of step with its subtasks, ahead or back ({@link ParentStatusPolicy}): moved to where
 *       its subtasks put it. A rejectable parent (a Defect) may be moved back: that's testing rejecting it. A
 *       Feature, Change or Bug whose subtasks all wait in waiting info may be moved back to to do: its questions are
 *       answered, and its subtasks follow it (see PlanningWorkflow).</li>
 * </ul>
 * The person is mentioned in a comment and the event is blocked, so no workflow reacts to the undone move. Runs after
 * {@link EnforcementGuard}, which handles who may move what.
 */
@Component
@Order(2)
public class StatusRulesGuard implements EventGuard {

    private static final String STATUS_FIELD = "status";
    private static final String STATUS_EVENT = "taskStatusUpdated";

    private static final Logger log = LoggerFactory.getLogger(StatusRulesGuard.class);

    private final ClickUpClientFactory clientFactory;
    private final TaskTypeResolver taskTypes;
    private final People people;
    private final StatusPlacement placement;
    private final ParentStatuses parentStatuses;
    private final AuditProperties properties;
    private final PlanningProperties planning;

    public StatusRulesGuard(ClickUpClientFactory clientFactory, TaskTypeResolver taskTypes, People people,
                            StatusPlacement placement, ParentStatuses parentStatuses, AuditProperties properties,
                            PlanningProperties planning) {
        this.clientFactory = clientFactory;
        this.taskTypes = taskTypes;
        this.people = people;
        this.placement = placement;
        this.parentStatuses = parentStatuses;
        this.properties = properties;
        this.planning = planning;
    }

    @Override
    public boolean blocks(ClickUpEvent event) {
        if (!event.isType(STATUS_EVENT)) {
            return false;
        }
        List<HistoryItem> moves = event.historyItems().stream()
                .filter(i -> STATUS_FIELD.equals(i.field()) && StatusMoveRule.isPresent(i.before()))
                .filter(i -> i.user() != null && i.user().id() != null && !people.isUnrestricted(i.user().id()))
                .toList();
        if (moves.isEmpty()) {
            return false;
        }
        ClickUpClient client = clientFactory.forWorkspace(event.workspaceId());
        AuditContext context = new AuditContext(client, event.workspaceId(), taskTypes, Duration.ZERO);
        boolean undone = false;
        for (HistoryItem move : moves) {
            undone |= check(context, event.taskId(), move);
        }
        return undone;
    }

    private boolean check(AuditContext context, String taskId, HistoryItem move) {
        String previous = StatusMoveRule.status(move.before());
        String attempted = StatusMoveRule.status(move.after());
        Task task = context.task(taskId);
        String current = task.status() == null || task.status().status() == null ? "" : task.status().status();
        if (!current.equalsIgnoreCase(attempted)) {
            log.info("Task {} moved on since the move to '{}' (now '{}'); not checking it", taskId, attempted, current);
            return false;
        }

        Optional<String> notAllowed = placement.violation(task, attempted, context);
        if (notAllowed.isPresent()) {
            return undo(context, task, move, previous,
                    "you moved this to '" + attempted + "', but " + notAllowed.get() + " I moved it back to '"
                            + previous + "'.");
        }

        Optional<String> type = context.typeName(task);
        boolean noPriority = task.priority() == null || task.priority().level() == null;
        if (planning.isToDo(attempted) && noPriority && type.filter(planning::needsPriority).isPresent()) {
            return undo(context, task, move, previous,
                    "you moved this " + type.get() + " to '" + attempted + "', but it has no priority, and every "
                            + type.get() + " needs one before work starts. Set the priority and move it again. I moved"
                            + " it back to '" + previous + "'.");
        }

        if (!parentStatuses.manages(task, context)) {
            return false;
        }
        Optional<String> derived = parentStatuses.derivedStatus(context.taskWithSubtasks(taskId), context);
        boolean questionsAnswered = ParentStatusPolicy.WAITING_INFO.equalsIgnoreCase(previous.trim())
                && planning.isToDo(attempted) && type.filter(planning::subtasksFollow).isPresent()
                && derived.filter(ParentStatusPolicy.WAITING_INFO::equals).isPresent();
        if (questionsAnswered) {
            return false;
        }
        boolean outOfStep = derived.isPresent() && (parentStatuses.isRejectable(task, context)
                ? ParentStatusPolicy.isAhead(attempted, derived.get())
                : ParentStatusPolicy.isOutOfStep(attempted, derived.get()));
        if (!outOfStep) {
            return false;
        }
        String target = ParentStatusPolicy.move(previous, derived.get()).orElse(previous);
        return undo(context, task, move, target,
                "you moved this to '" + attempted + "', but its subtasks are at '" + derived.get()
                        + "' and a parent always follows its subtasks. I moved it to '" + target + "'.");
    }

    private boolean undo(AuditContext context, Task task, HistoryItem move, String target, String message) {
        Long userId = move.user().id();
        ClickUpClient client = context.client();
        client.updateTask(task.id(), TaskUpdate.status(target));
        String withLink = properties.hasRulesDoc() ? message + " See the rules: " + properties.rulesDocUrl() : message;
        try {
            client.addCommentMentioning(task.id(), userId, withLink);
        } catch (RuntimeException e) {
            // The undo already happened; a missing comment isn't worth failing the event over.
            log.warn("Couldn't comment on task {} after undoing a move: {}", task.id(), e.getClass().getSimpleName());
        }
        log.info("Undid status move on task {} by user {} ({} -> {}); moved it to '{}'", task.id(), userId,
                StatusMoveRule.status(move.before()), StatusMoveRule.status(move.after()), target);
        return true;
    }
}

package io.chronohealth.clickup.audit;

import io.chronohealth.clickup.audit.TaskKinds.Classification;
import io.chronohealth.clickup.client.ClickUpClient;
import io.chronohealth.clickup.client.ClickUpClientFactory;
import io.chronohealth.clickup.client.dto.Task;
import io.chronohealth.clickup.client.dto.TaskUpdate;
import io.chronohealth.clickup.webhook.ClickUpEvent;
import io.chronohealth.clickup.webhook.ClickUpWebhookPayload.HistoryItem;
import io.chronohealth.clickup.webhook.EventGuard;
import io.chronohealth.clickup.workflow.TaskTypeResolver;
import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Undoes forbidden changes made by people marked {@code enforce} (see {@link Permissions}), as the events come in:
 * a forbidden status move is moved back, a forbidden stream tag change is reverted, and the person is mentioned in
 * a comment on the task. The event is then blocked, so no workflow reacts to the undone change.
 * <p>
 * The hourly report still lists the attempt. The undo is done by the app's own (admin) user, so it never triggers
 * itself.
 */
@Component
public class EnforcementGuard implements EventGuard {

    private static final String STATUS_FIELD = "status";

    private static final Logger log = LoggerFactory.getLogger(EnforcementGuard.class);

    private final ClickUpClientFactory clientFactory;
    private final TaskTypeResolver taskTypes;
    private final TaskKinds kinds;
    private final People people;
    private final Permissions permissions;
    private final StreamTagChangeRule tagRule;

    public EnforcementGuard(ClickUpClientFactory clientFactory, TaskTypeResolver taskTypes, TaskKinds kinds,
                            People people, Permissions permissions, StreamTagChangeRule tagRule) {
        this.clientFactory = clientFactory;
        this.taskTypes = taskTypes;
        this.kinds = kinds;
        this.people = people;
        this.permissions = permissions;
        this.tagRule = tagRule;
    }

    @Override
    public boolean blocks(ClickUpEvent event) {
        List<HistoryItem> enforced = event.historyItems().stream()
                .filter(i -> i.user() != null && people.isEnforced(i.user().id()))
                .toList();
        if (enforced.isEmpty()) {
            return false;
        }
        ClickUpClient client = clientFactory.forWorkspace(event.workspaceId());
        boolean undone = false;
        for (HistoryItem item : enforced) {
            if (STATUS_FIELD.equals(item.field()) && StatusMoveRule.isPresent(item.before())) {
                undone |= undoStatusMove(client, event, item);
            } else if (StreamTagChangeRule.ADDED.equals(item.field()) || StreamTagChangeRule.REMOVED.equals(item.field())) {
                undone |= undoTagChange(client, event, item);
            }
        }
        return undone;
    }

    private boolean undoStatusMove(ClickUpClient client, ClickUpEvent event, HistoryItem move) {
        Long userId = move.user().id();
        AuditContext context = new AuditContext(client, event.workspaceId(), taskTypes, Duration.ZERO);
        Task task = context.task(event.taskId());
        Classification kind = kinds.classify(task, context);
        if (permissions.canMoveStatus(userId, kind)) {
            return false;
        }
        String previous = StatusMoveRule.status(move.before());
        String attempted = StatusMoveRule.status(move.after());
        String current = task.status() == null || task.status().status() == null ? "" : task.status().status();
        if (!current.equalsIgnoreCase(attempted)) {
            log.info("Task {} moved on since the forbidden move (now '{}'); not undoing it", task.id(), current);
            return false;
        }
        client.updateTask(task.id(), TaskUpdate.status(previous));
        comment(client, task.id(), userId, "you moved this from '" + previous + "' to '" + attempted + "', but "
                + lowerFirst(permissions.whoMayMove(kind)) + " I moved it back to '" + previous + "'.");
        log.info("Undid status move on task {} by user {} ({} -> {})", task.id(), userId, previous, attempted);
        return true;
    }

    private boolean undoTagChange(ClickUpClient client, ClickUpEvent event, HistoryItem item) {
        Long userId = item.user().id();
        if (permissions.canChangeStreamTags(userId)) {
            return false;
        }
        List<String> tags = tagRule.changedStreamTags(item);
        if (tags.isEmpty()) {
            return false;
        }
        boolean added = StreamTagChangeRule.ADDED.equals(item.field());
        Task task = client.getTask(event.taskId());
        java.util.Set<String> current = task.tags() == null ? java.util.Set.of() : task.tags().stream()
                .map(t -> t.name().toLowerCase(java.util.Locale.ROOT)).collect(java.util.stream.Collectors.toSet());
        // Only undo what's still in effect (someone may have fixed it already).
        tags = tags.stream().filter(t -> added == current.contains(t)).toList();
        if (tags.isEmpty()) {
            return false;
        }
        for (String tag : tags) {
            if (added) {
                client.removeTag(event.taskId(), tag);
            } else {
                client.addTag(event.taskId(), tag);
            }
        }
        comment(client, event.taskId(), userId, "you " + (added ? "added" : "removed") + " the "
                + String.join(", ", tags) + " tag, but " + lowerFirst(permissions.whoMayTag()) + " I "
                + (added ? "removed it again" : "put it back") + ".");
        log.info("Undid stream tag change on task {} by user {} ({} {})", event.taskId(), userId,
                added ? "added" : "removed", tags);
        return true;
    }

    private void comment(ClickUpClient client, String taskId, Long userId, String text) {
        try {
            client.addCommentMentioning(taskId, userId, text);
        } catch (RuntimeException e) {
            // The undo already happened; a missing comment isn't worth failing the event over.
            log.warn("Couldn't comment on task {} after undoing a change: {}", taskId, e.getClass().getSimpleName());
        }
    }

    private static String lowerFirst(String text) {
        return text.isEmpty() ? text : Character.toLowerCase(text.charAt(0)) + text.substring(1);
    }
}

package io.chronohealth.clickup.audit;

import io.chronohealth.clickup.audit.TaskKinds.Classification;
import io.chronohealth.clickup.client.ClickUpClient;
import io.chronohealth.clickup.client.ClickUpClientFactory;
import io.chronohealth.clickup.client.dto.Tag;
import io.chronohealth.clickup.client.dto.Task;
import io.chronohealth.clickup.client.dto.TaskUpdate;
import io.chronohealth.clickup.webhook.ClickUpEvent;
import io.chronohealth.clickup.webhook.ClickUpWebhookPayload.HistoryItem;
import io.chronohealth.clickup.webhook.EventGuard;
import io.chronohealth.clickup.workflow.TaskTypeResolver;
import io.chronohealth.clickup.client.dto.CustomField;
import io.chronohealth.clickup.workflow.CustomFieldValueMapper;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import tools.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Undoes forbidden changes made by people marked {@code enforce} (see {@link Permissions}), as the events come in:
 * a forbidden status move is moved back, a forbidden stream tag change is reverted, and the person is mentioned in
 * a comment on the task. The event is then blocked, so no workflow reacts to the undone change.
 * <p>
 * Stream tags set while creating a task come with no tag event, only the creation, so they're removed when the
 * creation comes in. That event isn't blocked: the creation itself is allowed and its workflows still run.
 * <p>
 * A priority or Product Priority change on a task where work has started (see {@link PriorityLockRule}) is put
 * back to its previous value, so it isn't pushed down to the subtasks either.
 * <p>
 * The hourly report still lists the attempt. The undo is done by the app's own (admin) user, so it never triggers
 * itself.
 */
@Component
@Order(1)
public class EnforcementGuard implements EventGuard {

    private static final String STATUS_FIELD = "status";
    private static final String CREATION_FIELD = "task_creation";

    private static final Logger log = LoggerFactory.getLogger(EnforcementGuard.class);

    private final ClickUpClientFactory clientFactory;
    private final TaskTypeResolver taskTypes;
    private final TaskKinds kinds;
    private final People people;
    private final Permissions permissions;
    private final StreamTagChangeRule tagRule;
    private final PriorityLockRule priorityRule;
    private final CustomFieldValueMapper valueMapper;
    private final AuditProperties properties;

    public EnforcementGuard(ClickUpClientFactory clientFactory, TaskTypeResolver taskTypes, TaskKinds kinds,
                            People people, Permissions permissions, StreamTagChangeRule tagRule,
                            PriorityLockRule priorityRule, CustomFieldValueMapper valueMapper,
                            AuditProperties properties) {
        this.clientFactory = clientFactory;
        this.taskTypes = taskTypes;
        this.kinds = kinds;
        this.people = people;
        this.permissions = permissions;
        this.tagRule = tagRule;
        this.priorityRule = priorityRule;
        this.valueMapper = valueMapper;
        this.properties = properties;
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
        List<HistoryItem> lockedChanges = priorityRule.forbiddenChanges(event);
        boolean undone = false;
        for (HistoryItem item : enforced) {
            if (STATUS_FIELD.equals(item.field()) && StatusMoveRule.isPresent(item.before())) {
                undone |= undoStatusMove(client, event, item);
            } else if (StreamTagChangeRule.ADDED.equals(item.field()) || StreamTagChangeRule.REMOVED.equals(item.field())) {
                undone |= undoTagChange(client, event, item);
            } else if (CREATION_FIELD.equals(item.field())) {
                removeTagsSetOnCreation(client, event, item);
            } else if (lockedChanges.contains(item)) {
                undone |= undoPriorityChange(client, event, item);
            }
        }
        return undone;
    }

    private boolean undoStatusMove(ClickUpClient client, ClickUpEvent event, HistoryItem move) {
        Long userId = move.user().id();
        AuditContext context = new AuditContext(client, event.workspaceId(), taskTypes, Duration.ZERO);
        Task task = context.task(event.taskId());
        Classification kind = kinds.classify(task, context);
        String previous = StatusMoveRule.status(move.before());
        String attempted = StatusMoveRule.status(move.after());
        if (permissions.canMoveStatus(userId, kind)) {
            return false;
        }
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

    private void removeTagsSetOnCreation(ClickUpClient client, ClickUpEvent event, HistoryItem creation) {
        Long userId = creation.user().id();
        if (permissions.canChangeStreamTags(userId)) {
            return;
        }
        Task task = client.getTask(event.taskId());
        List<String> tags = task.tags() == null ? List.of() : task.tags().stream()
                .map(Tag::name)
                .filter(kinds::isStreamTag)
                .map(t -> t.trim().toLowerCase(java.util.Locale.ROOT))
                .distinct()
                .toList();
        if (tags.isEmpty()) {
            return;
        }
        tags.forEach(tag -> client.removeTag(event.taskId(), tag));
        comment(client, event.taskId(), userId, "you created this with the " + String.join(", ", tags) + " tag, but "
                + lowerFirst(permissions.whoMayTag()) + " I removed it.");
        log.info("Removed stream tags set on creation of task {} by user {} ({})", event.taskId(), userId, tags);
    }

    private boolean undoPriorityChange(ClickUpClient client, ClickUpEvent event, HistoryItem change) {
        Long userId = change.user().id();
        Task task = client.getTask(event.taskId());
        if (!priorityRule.isLocked(task)) {
            return false;
        }
        boolean reverted = PriorityLockRule.PRIORITY.equals(change.field())
                ? revertPriority(client, task, change)
                : revertField(client, task, change);
        if (!reverted) {
            log.info("Task {} changed again since the forbidden {} change; not undoing it", task.id(), change.field());
            return false;
        }
        String what = PriorityLockRule.what(change);
        comment(client, task.id(), userId, "you changed the " + what + " while work had started, but "
                + lowerFirst(priorityRule.whoMay()) + " I put it back.");
        log.info("Undid {} change on task {} by user {}", change.field(), task.id(), userId);
        return true;
    }

    private static boolean revertPriority(ClickUpClient client, Task task, HistoryItem change) {
        Integer current = task.priority() == null ? null : task.priority().level();
        if (!Objects.equals(current, priorityLevel(change.after()))) {
            return false;
        }
        client.setPriority(task.id(), priorityLevel(change.before()));
        return true;
    }

    private boolean revertField(ClickUpClient client, Task task, HistoryItem change) {
        Optional<CustomField> field = task.customFieldNamed(change.customFieldName());
        if (field.isEmpty()) {
            return false;
        }
        Optional<Object> current = field.filter(CustomField::hasValue).flatMap(valueMapper::toWriteValue);
        if (!current.equals(optionId(field.get(), change.after()))) {
            return false;
        }
        Optional<Object> previous = optionId(field.get(), change.before());
        if (previous.isPresent()) {
            client.setCustomField(task.id(), field.get().id(), previous.get());
        } else {
            client.removeCustomField(task.id(), field.get().id());
        }
        return true;
    }

    /**
     * A priority history value is a priority object ({@code id} "1" urgent .. "4" low), or null when cleared.
     */
    private static Integer priorityLevel(JsonNode value) {
        if (value == null || value.isNull() || value.isMissingNode()) {
            return null;
        }
        String id = value.isObject() ? value.path("id").asString(null) : value.asString(null);
        return id == null || id.isBlank() ? null : Integer.valueOf(id.trim());
    }

    /**
     * A drop-down history value is the option id, or its index; null when cleared.
     */
    private static Optional<Object> optionId(CustomField field, JsonNode value) {
        if (value == null || value.isNull() || value.isMissingNode()) {
            return Optional.empty();
        }
        String raw = value.asString("");
        for (JsonNode option : field.typeConfig().path("options")) {
            if (option.path("id").asString("").equals(raw)) {
                return Optional.of(raw);
            }
        }
        for (JsonNode option : field.typeConfig().path("options")) {
            if (option.path("orderindex").asString("").equals(raw)) {
                return Optional.of(option.path("id").asString());
            }
        }
        return Optional.empty();
    }

    private void comment(ClickUpClient client, String taskId, Long userId, String text) {
        String withLink = properties.hasRulesDoc() ? text + " See the rules: " + properties.rulesDocUrl() : text;
        try {
            client.addCommentMentioning(taskId, userId, withLink);
        } catch (RuntimeException e) {
            // The undo already happened; a missing comment isn't worth failing the event over.
            log.warn("Couldn't comment on task {} after undoing a change: {}", taskId, e.getClass().getSimpleName());
        }
    }

    private static String lowerFirst(String text) {
        return text.isEmpty() ? text : Character.toLowerCase(text.charAt(0)) + text.substring(1);
    }
}

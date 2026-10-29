package io.chronohealth.clickup.workflow;

import io.chronohealth.clickup.client.ClickUpClient;
import io.chronohealth.clickup.client.ClickUpClientFactory;
import io.chronohealth.clickup.client.dto.CustomField;
import io.chronohealth.clickup.client.dto.Task;
import io.chronohealth.clickup.config.ClickUpProperties;
import io.chronohealth.clickup.webhook.ClickUpEvent;
import io.chronohealth.clickup.webhook.ClickUpWebhookPayload.HistoryItem;
import io.chronohealth.clickup.webhook.EventHandler;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * When a parent's priority or one of the configured custom fields (Product Priority) changes, its children and
 * grandchildren get the same value, whatever their status. Subtasks of a skipped type (Defects) keep their own
 * values, and so does everything inside them.
 * <p>
 * Uses the parent's current values (cleared too), and only touches subtasks whose value differs. No loop: the
 * changes this makes come back as events for the subtasks, whose own subtasks already match by then.
 */
@Component
public class PrioritySyncWorkflow implements EventHandler {

    static final String PRIORITY_EVENT = "taskPriorityUpdated";
    static final String UPDATED_EVENT = "taskUpdated";
    private static final String CUSTOM_FIELD = "custom_field";
    private static final int MAX_DEPTH = 5;
    private static final int NO_PRIORITY = 0;

    private static final Logger log = LoggerFactory.getLogger(PrioritySyncWorkflow.class);

    private final ClickUpClientFactory clientFactory;
    private final TaskTypeResolver taskTypes;
    private final CustomFieldValueMapper valueMapper;
    private final ClickUpProperties.PrioritySync config;

    public PrioritySyncWorkflow(ClickUpClientFactory clientFactory, TaskTypeResolver taskTypes,
                                CustomFieldValueMapper valueMapper, ClickUpProperties properties) {
        this.clientFactory = clientFactory;
        this.taskTypes = taskTypes;
        this.valueMapper = valueMapper;
        this.config = properties.workflows().prioritySync();
    }

    @Override
    public Set<String> eventTypes() {
        if (!config.enabled()) {
            return Set.of();
        }
        return config.fieldsOrEmpty().isEmpty() ? Set.of(PRIORITY_EVENT) : Set.of(PRIORITY_EVENT, UPDATED_EVENT);
    }

    @Override
    public void handle(ClickUpEvent event) {
        boolean priority = PRIORITY_EVENT.equals(event.type());
        List<String> fields = config.fieldsOrEmpty().stream()
                .filter(f -> event.historyItems().stream().anyMatch(i -> isChangeOf(i, f)))
                .toList();
        if (!priority && fields.isEmpty()) {
            return;
        }
        ClickUpClient client = clientFactory.forWorkspace(event.workspaceId());
        Task parent = client.getTask(event.taskId(), true);
        Optional<String> type = taskTypes.typeName(client, event.workspaceId(), parent);
        if (!contains(config.parentTypesOrEmpty(), type.orElse(null))) {
            return;
        }
        Optional<Integer> wantedPriority = priority
                ? Optional.of(parent.priority() == null ? NO_PRIORITY : parent.priority().level())
                : Optional.empty();
        List<Wanted> wantedFields = fields.stream()
                .flatMap(name -> parent.customFieldNamed(name).stream())
                .map(field -> new Wanted(field,
                        Optional.of(field).filter(CustomField::hasValue).flatMap(valueMapper::toWriteValue)))
                .toList();
        int updated = pushDown(client, event.workspaceId(), parent, wantedPriority, wantedFields, 1);
        log.info("Pushed {} of {} {} down to {} subtask(s)", priority ? "the priority" : "a field", type.get(),
                parent.id(), updated);
    }

    /**
     * Every level gets the top parent's values: a child read before it was updated still has its old ones.
     */
    private int pushDown(ClickUpClient client, String workspaceId, Task parent, Optional<Integer> priority,
                         List<Wanted> fields, int depth) {
        int updated = 0;
        for (Task listed : parent.subtasksOrEmpty()) {
            if (!parent.id().equals(listed.parent())
                    || contains(config.skipTypesOrEmpty(), taskTypes.typeName(client, workspaceId, listed).orElse(null))) {
                continue;
            }
            // Re-read: the listed subtask may lack its fields and its own subtasks.
            Task child = client.getTask(listed.id(), true);
            boolean changed = priority.isPresent() && copyPriority(client, child, priority.get());
            for (Wanted field : fields) {
                changed |= copyField(client, child, field);
            }
            if (changed) {
                updated++;
            }
            if (depth < MAX_DEPTH) {
                updated += pushDown(client, workspaceId, child, priority, fields, depth + 1);
            }
        }
        return updated;
    }

    private static boolean copyPriority(ClickUpClient client, Task child, int wanted) {
        int current = child.priority() == null || child.priority().level() == null
                ? NO_PRIORITY : child.priority().level();
        if (current == wanted) {
            return false;
        }
        client.setPriority(child.id(), wanted == NO_PRIORITY ? null : wanted);
        return true;
    }

    private boolean copyField(ClickUpClient client, Task child, Wanted wanted) {
        Optional<Object> current = child.customFieldNamed(wanted.field().name()).filter(CustomField::hasValue)
                .flatMap(valueMapper::toWriteValue);
        if (wanted.value().equals(current)) {
            return false;
        }
        if (wanted.value().isPresent()) {
            client.setCustomField(child.id(), wanted.field().id(), wanted.value().get());
        } else {
            client.removeCustomField(child.id(), wanted.field().id());
        }
        return true;
    }

    private static boolean isChangeOf(HistoryItem item, String fieldName) {
        String changed = item.customFieldName();
        return CUSTOM_FIELD.equals(item.field()) && changed != null
                && changed.trim().equalsIgnoreCase(fieldName.trim());
    }

    private static boolean contains(List<String> values, String value) {
        if (value == null) {
            return false;
        }
        String wanted = value.trim().toLowerCase(Locale.ROOT);
        return values.stream().anyMatch(v -> v.trim().toLowerCase(Locale.ROOT).equals(wanted));
    }

    /**
     * A field to push down, with the parent's value (empty when it's cleared).
     */
    private record Wanted(CustomField field, Optional<Object> value) {
    }
}

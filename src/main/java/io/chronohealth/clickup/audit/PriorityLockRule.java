package io.chronohealth.clickup.audit;

import io.chronohealth.clickup.client.dto.Task;
import io.chronohealth.clickup.webhook.ClickUpEvent;
import io.chronohealth.clickup.webhook.ClickUpWebhookPayload.HistoryItem;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * Once work on a task has started (it's in one of the configured statuses), only admins change its priority or the
 * locked custom fields (Product Priority). Judged on the task's status as it is now.
 */
@Component
public class PriorityLockRule implements AuditEventRule {

    static final String PRIORITY = "priority";
    static final String CUSTOM_FIELD = "custom_field";

    private final People people;
    private final Permissions permissions;
    private final AuditProperties.PriorityLock config;

    public PriorityLockRule(People people, Permissions permissions, AuditProperties properties) {
        this.people = people;
        this.permissions = permissions;
        this.config = properties.rules().priorityLock();
    }

    @Override
    public String id() {
        return "priority-lock";
    }

    @Override
    public boolean enabled() {
        return config.enabled();
    }

    @Override
    public List<String> eventTypes() {
        return List.of("taskPriorityUpdated", "taskUpdated");
    }

    @Override
    public List<String> check(ClickUpEvent event, AuditContext context) {
        List<HistoryItem> changes = forbiddenChanges(event);
        if (changes.isEmpty() || !isLocked(context.task(event.taskId()))) {
            return List.of();
        }
        List<String> findings = new ArrayList<>();
        for (HistoryItem change : changes) {
            Long userId = change.user().id();
            findings.add(people.describe(userId) + " changed the " + what(change) + " while work had started. "
                    + whoMay()
                    + (people.isEnforced(userId) ? " Undone automatically." : ""));
        }
        return findings;
    }

    /**
     * Priority and locked field changes in the event by people who may not make them on a started task.
     */
    List<HistoryItem> forbiddenChanges(ClickUpEvent event) {
        if (!config.enabled()) {
            return List.of();
        }
        return event.historyItems().stream()
                .filter(i -> PRIORITY.equals(i.field()) || isLockedField(i))
                .filter(i -> i.user() != null && !permissions.canChangeLockedPriority(i.user().id()))
                .toList();
    }

    String whoMay() {
        return permissions.whoMayChangeLockedPriority();
    }

    boolean isLocked(Task task) {
        String status = task == null || task.status() == null ? null : task.status().status();
        return status != null && config.statusesOrEmpty().stream().anyMatch(s -> s.equalsIgnoreCase(status.trim()));
    }

    static String what(HistoryItem change) {
        return PRIORITY.equals(change.field()) ? "priority" : change.customFieldName();
    }

    private boolean isLockedField(HistoryItem item) {
        String name = item.customFieldName();
        return CUSTOM_FIELD.equals(item.field()) && name != null
                && config.fieldsOrEmpty().stream().anyMatch(f -> f.trim().toLowerCase(Locale.ROOT)
                        .equals(name.trim().toLowerCase(Locale.ROOT)));
    }
}

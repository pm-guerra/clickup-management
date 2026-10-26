package io.chronohealth.clickup.workflow;

import io.chronohealth.clickup.audit.AuditContext;
import io.chronohealth.clickup.audit.TaskKinds;
import io.chronohealth.clickup.audit.TaskKinds.Classification;
import io.chronohealth.clickup.audit.TaskKinds.Kind;
import io.chronohealth.clickup.client.dto.Task;
import io.chronohealth.clickup.config.ClickUpProperties;
import io.chronohealth.clickup.workflow.ParentStatusPolicy.Child;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Applies {@link ParentStatusPolicy} to real tasks: which parents are managed, which are tested as a whole and which
 * subtasks are mobile. Shared by the workflow, the real-time undo and the hourly audit so they never disagree.
 */
@Component
public class ParentStatuses {

    private final TaskKinds kinds;
    private final ClickUpProperties.ParentStatus config;

    public ParentStatuses(TaskKinds kinds, ClickUpProperties properties) {
        this.kinds = kinds;
        this.config = properties.workflows().parentStatus();
    }

    /**
     * Whether the task's status follows its subtasks (Story/Bug/Change/Feature/Defect/Task; not Epics). Subtasks
     * of the ignored types (Defects) don't count.
     */
    public boolean manages(Task task, AuditContext context) {
        return config.enabled() && isOneOf(context.typeName(task), config.parentTypesOrEmpty());
    }

    /**
     * Whether the task is tested as a whole, i.e. may be in in testing.
     */
    public boolean isTestingType(Task task, AuditContext context) {
        return isOneOf(context.typeName(task), config.testingTypesOrEmpty());
    }

    public List<String> testingTypes() {
        return config.testingTypesOrEmpty();
    }

    /**
     * @param parent the task, fetched with its subtasks
     * @return the status its subtasks call for; empty if it isn't managed or has no subtasks
     */
    public Optional<String> derivedStatus(Task parent, AuditContext context) {
        if (!manages(parent, context) || parent.subtasksOrEmpty().isEmpty()) {
            return Optional.empty();
        }
        return ParentStatusPolicy.derive(children(parent, context), isTestingType(parent, context));
    }

    /**
     * @param parent the task, fetched with its subtasks
     */
    public boolean allSubtasksMobile(Task parent, AuditContext context) {
        return !parent.subtasksOrEmpty().isEmpty() && children(parent, context).stream().allMatch(Child::mobile);
    }

    public boolean isMobileStream(Classification kind) {
        return kind.kind() == Kind.STREAM && kind.stream().filter(config.mobileStream()::equalsIgnoreCase).isPresent();
    }

    private List<Child> children(Task parent, AuditContext context) {
        // Sub-subtasks inside a mobile stream task are mobile too, tagged or not.
        boolean parentMobile = isMobileStream(kinds.classify(parent, context));
        return parent.subtasksOrEmpty().stream()
                .filter(child -> !isOneOf(context.typeName(child), config.ignoredChildTypesOrEmpty()))
                .map(child -> new Child(child.status() == null ? null : child.status().status(),
                        parentMobile || kinds.streamOf(child).filter(config.mobileStream()::equalsIgnoreCase)
                                .isPresent()))
                .toList();
    }

    private static boolean isOneOf(Optional<String> type, List<String> types) {
        return type.map(t -> types.stream().anyMatch(candidate -> candidate.trim().toLowerCase(Locale.ROOT)
                .equals(t.trim().toLowerCase(Locale.ROOT)))).orElse(false);
    }
}

package io.chronohealth.clickup.audit;

import io.chronohealth.clickup.client.dto.Task;
import io.chronohealth.clickup.workflow.ParentStatusPolicy;
import io.chronohealth.clickup.workflow.ParentStatuses;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Statuses only some tasks may be in:
 * <ul>
 *   <li>ready for testing: only Stories, Bugs, Changes and Features (tested as a whole);</li>
 *   <li>ready_for_dev: only mobile stream tasks (and the tasks inside them), or a task whose subtasks are all
 *       mobile.</li>
 * </ul>
 * Shared by the real-time undo ({@link StatusRulesGuard}) and the hourly report ({@link StatusPlacementRule}).
 */
@Component
public class StatusPlacement {

    private final TaskKinds kinds;
    private final ParentStatuses parentStatuses;

    public StatusPlacement(TaskKinds kinds, ParentStatuses parentStatuses) {
        this.kinds = kinds;
        this.parentStatuses = parentStatuses;
    }

    /**
     * @return why {@code task} may not be in {@code status} (a sentence), or empty if it may
     */
    public Optional<String> violation(Task task, String status, AuditContext context) {
        String normalized = ParentStatusPolicy.normalize(status);
        if (ParentStatusPolicy.READY_FOR_TESTING.equals(normalized) && !parentStatuses.isTestingType(task, context)) {
            return Optional.of("only " + String.join(", ", parentStatuses.testingTypes())
                    + " tasks can be in 'ready for testing'.");
        }
        if (ParentStatusPolicy.READY_FOR_DEV.equals(normalized) && !mayBeReadyForDev(task, context)) {
            return Optional.of("only mobile stream tasks, or tasks whose subtasks are all mobile, can be in '"
                    + ParentStatusPolicy.READY_FOR_DEV + "'.");
        }
        return Optional.empty();
    }

    private boolean mayBeReadyForDev(Task task, AuditContext context) {
        return parentStatuses.isMobileStream(kinds.classify(task, context))
                || parentStatuses.allSubtasksMobile(context.taskWithSubtasks(task.id()), context);
    }
}

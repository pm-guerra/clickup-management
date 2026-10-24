package io.chronohealth.clickup.audit;

import io.chronohealth.clickup.client.dto.Task;
import io.chronohealth.clickup.workflow.ParentStatusPolicy;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Reports tasks sitting in a status they may not be in (see {@link StatusPlacement}), e.g. moved there before the
 * rule existed. Stream tasks in ready for testing are already reported by {@link StreamTaskStatusRule}.
 */
@Component
public class StatusPlacementRule implements AuditRule {

    private final StatusPlacement placement;
    private final TaskKinds kinds;
    private final AuditProperties.Toggle config;

    public StatusPlacementRule(StatusPlacement placement, TaskKinds kinds, AuditProperties properties) {
        this.placement = placement;
        this.kinds = kinds;
        this.config = properties.rules().statusPlacement();
    }

    @Override
    public String id() {
        return "status-placement";
    }

    @Override
    public boolean enabled() {
        return config.enabled();
    }

    @Override
    public Optional<String> check(Task task, AuditContext context) {
        if (task.status() == null || task.status().status() == null) {
            return Optional.empty();
        }
        String status = task.status().status();
        String normalized = ParentStatusPolicy.normalize(status);
        if (!ParentStatusPolicy.READY_FOR_TESTING.equals(normalized)
                && !ParentStatusPolicy.READY_FOR_DEV.equals(normalized)) {
            return Optional.empty();
        }
        if (ParentStatusPolicy.READY_FOR_TESTING.equals(normalized)
                && kinds.classify(task, context).kind() == TaskKinds.Kind.STREAM) {
            return Optional.empty();
        }
        return placement.violation(task, status, context)
                .map(reason -> "Is in '" + status + "', but " + reason);
    }
}

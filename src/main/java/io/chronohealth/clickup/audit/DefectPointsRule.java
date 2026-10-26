package io.chronohealth.clickup.audit;

import io.chronohealth.clickup.client.dto.Task;
import java.math.BigDecimal;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Defects aren't estimated: sprint points set on one are removed, and the removal is reported.
 */
@Component
public class DefectPointsRule implements AuditRule {

    private final AuditProperties.Defects config;

    public DefectPointsRule(AuditProperties properties) {
        this.config = properties.rules().defects();
    }

    @Override
    public String id() {
        return "defect-points";
    }

    @Override
    public boolean enabled() {
        return config.enabled() && config.clearPoints();
    }

    @Override
    public Optional<String> check(Task task, AuditContext context) {
        if (task.points() == null || task.points() == 0
                || !DefectPlacementRule.isOneOf(context.typeName(task), config.typesOrEmpty())) {
            return Optional.empty();
        }
        context.client().clearPoints(task.id());
        return Optional.of("Had " + BigDecimal.valueOf(task.points()).stripTrailingZeros().toPlainString()
                + " sprint point(s); Defects aren't estimated, so they were removed.");
    }
}

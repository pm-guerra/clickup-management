package io.chronohealth.clickup.audit;

import io.chronohealth.clickup.client.dto.Task;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * A Defect must live inside a Feature, Bug or Change (it's a problem found in that work).
 */
@Component
public class DefectPlacementRule implements AuditRule {

    private final AuditProperties.Defects config;

    public DefectPlacementRule(AuditProperties properties) {
        this.config = properties.rules().defects();
    }

    @Override
    public String id() {
        return "defect-placement";
    }

    @Override
    public boolean enabled() {
        return config.enabled();
    }

    @Override
    public Optional<String> check(Task task, AuditContext context) {
        if (!isOneOf(context.typeName(task), config.typesOrEmpty())) {
            return Optional.empty();
        }
        String allowed = String.join(", ", config.parentTypesOrEmpty());
        if (task.parent() == null) {
            return Optional.of("A Defect must be inside a task of type " + allowed + ", but this one isn't inside any.");
        }
        Optional<String> parentType = context.typeName(context.task(task.parent()));
        if (isOneOf(parentType, config.parentTypesOrEmpty())) {
            return Optional.empty();
        }
        return Optional.of("A Defect must be inside a task of type " + allowed + ", but this one is inside a "
                + parentType.orElse("task of unknown type") + ".");
    }

    static boolean isOneOf(Optional<String> type, List<String> types) {
        return type.map(t -> types.stream().anyMatch(candidate -> candidate.trim().toLowerCase(Locale.ROOT)
                .equals(t.trim().toLowerCase(Locale.ROOT)))).orElse(false);
    }
}

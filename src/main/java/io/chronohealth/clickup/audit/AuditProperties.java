package io.chronohealth.clickup.audit;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * @param enabled         run the audit at all
 * @param initialLookback how far back the very first run looks
 * @param overlap         each run starts this much before the previous one ended, so nothing is missed at the edges
 * @param apiPacing       minimum time between ClickUp calls made by rules (task lookups)
 * @param maxPages        safety cap on result pages (100 tasks each) per run
 * @param listIds         only audit these lists; empty for the whole Workspace
 * @param notifyUserId    ClickUp user id that gets the report as a direct message; blank to only log
 * @param scheduler       in-process hourly trigger for local runs; on Cloud Run, Cloud Scheduler calls the endpoint
 * @param rules           per-rule settings
 */
@Validated
@ConfigurationProperties("app.audit")
public record AuditProperties(
        boolean enabled,
        @NotNull Duration initialLookback,
        @NotNull Duration overlap,
        @NotNull Duration apiPacing,
        int maxPages,
        List<String> listIds,
        String notifyUserId,
        @Valid @NotNull Scheduler scheduler,
        @Valid @NotNull Rules rules
) {

    public List<String> listIdsOrEmpty() {
        return listIds == null ? List.of() : listIds;
    }

    public record Scheduler(boolean enabled) {
    }

    public record Rules(@Valid @NotNull StreamTaskStatus streamTaskStatus) {
    }

    /**
     * @param parentTypes     subtasks of these task types are "stream tasks"
     * @param allowedStatuses statuses a stream task may be in
     */
    public record StreamTaskStatus(boolean enabled, List<String> parentTypes, List<String> allowedStatuses) {

        public List<String> parentTypesOrEmpty() {
            return parentTypes == null ? List.of() : parentTypes;
        }

        public List<String> allowedStatusesOrEmpty() {
            return allowedStatuses == null ? List.of() : allowedStatuses;
        }
    }
}

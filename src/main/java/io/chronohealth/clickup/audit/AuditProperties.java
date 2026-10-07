package io.chronohealth.clickup.audit;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
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
 * @param notifyChannelId ClickUp Chat channel the report is posted to; takes precedence over {@code notifyUserId}
 * @param notifyUserId    ClickUp user id that gets the report as a direct message when no channel is set
 * @param people          who's who, for the permission rules (anyone not listed has no permissions)
 * @param mainTaskTypes   task types testers may move (Story, Bug, Change, Epic)
 * @param streams         stream names, also the stream tags (backend, web, mobile)
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
        String notifyChannelId,
        String notifyUserId,
        List<@Valid Person> people,
        List<String> mainTaskTypes,
        List<String> streams,
        @Valid @NotNull Scheduler scheduler,
        @Valid @NotNull Rules rules
) {

    public List<String> listIdsOrEmpty() {
        return listIds == null ? List.of() : listIds;
    }

    public List<Person> peopleOrEmpty() {
        return people == null ? List.of() : people;
    }

    public List<String> mainTaskTypesOrEmpty() {
        return mainTaskTypes == null ? List.of() : mainTaskTypes;
    }

    public List<String> streamsOrEmpty() {
        return streams == null ? List.of() : streams;
    }

    /**
     * @param id     ClickUp user id
     * @param name   display name used in reports
     * @param role   admin, tester or dev
     * @param stream  for devs: backend, web or mobile
     * @param enforce their forbidden changes are undone automatically (otherwise only reported)
     */
    public record Person(@NotBlank String id, @NotBlank String name, @NotBlank String role, String stream,
                         Boolean enforce) {

        public boolean isEnforced() {
            return Boolean.TRUE.equals(enforce);
        }
    }

    public record Scheduler(boolean enabled) {
    }

    public record Rules(@Valid @NotNull StreamTaskStatus streamTaskStatus, @Valid @NotNull Toggle statusMove,
                        @Valid @NotNull Toggle streamTagChange) {
    }

    public record Toggle(boolean enabled) {
    }

    /**
     * A stream task is a subtask whose parent is one of {@code parentTypes} and whose own type is one of
     * {@code streamTypes} (the default "Task"). Bugs/Changes/Stories nested under a parent aren't stream tasks.
     *
     * @param parentTypes     task types that have stream tasks
     * @param streamTypes     task types a stream task has
     * @param allowedStatuses statuses a stream task may be in
     */
    public record StreamTaskStatus(boolean enabled, List<String> parentTypes, List<String> streamTypes,
                                   List<String> allowedStatuses) {

        public List<String> parentTypesOrEmpty() {
            return parentTypes == null ? List.of() : parentTypes;
        }

        public List<String> streamTypesOrEmpty() {
            return streamTypes == null ? List.of() : streamTypes;
        }

        public List<String> allowedStatusesOrEmpty() {
            return allowedStatuses == null ? List.of() : allowedStatuses;
        }
    }
}

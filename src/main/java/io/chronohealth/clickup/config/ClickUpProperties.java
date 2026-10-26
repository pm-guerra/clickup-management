package io.chronohealth.clickup.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("clickup")
public record ClickUpProperties(
        @NotBlank String clientId,
        @NotBlank String clientSecret,
        @NotBlank String redirectUri,
        @NotBlank String workspaceId,
        @NotBlank String apiBaseUrl,
        @NotBlank String authorizeUrl,
        @NotNull Duration connectTimeout,
        @NotNull Duration readTimeout,
        @Valid @NotNull RateLimit rateLimit,
        @Valid @NotNull Workflows workflows
) {

    public record RateLimit(int maxRetries, @NotNull Duration maxWait) {
    }

    public record Workflows(@Valid @NotNull CopyParentFields copyParentFields, @Valid @NotNull TagSubtasks tagSubtasks,
                            @Valid @NotNull ParentStatus parentStatus, @Valid @NotNull CreateDefaults createDefaults,
                            @Valid @NotNull RejectionReopen rejectionReopen,
                            @Valid @NotNull PrioritySync prioritySync) {
    }

    /**
     * Copies a parent's priority to its subtasks while the parent is still in one of {@code whenStatuses}
     * (see PrioritySyncWorkflow).
     *
     * @param parentTypes  task types whose priority is synced (Story, Bug, Change)
     * @param whenStatuses only while the parent is in one of these statuses (to do)
     * @param subtaskTypes subtasks of these types get the priority (Task = the stream tasks)
     */
    public record PrioritySync(boolean enabled, List<String> parentTypes, List<String> whenStatuses,
                               List<String> subtaskTypes) {

        public List<String> parentTypesOrEmpty() {
            return parentTypes == null ? List.of() : parentTypes;
        }

        public List<String> whenStatusesOrEmpty() {
            return whenStatuses == null ? List.of() : whenStatuses;
        }

        public List<String> subtaskTypesOrEmpty() {
            return subtaskTypes == null ? List.of() : subtaskTypes;
        }
    }

    /**
     * Reopens completed subtasks when testing rejects a task (see RejectionReopenWorkflow).
     *
     * @param taskTypes      task types this applies to
     * @param fromStatuses   the task left one of these ("in testing")...
     * @param toStatuses     ...for one of these ("in progress", "to do")
     * @param reopenStatuses subtasks in these statuses are reopened ("complete")
     * @param reopenTo       status the reopened subtasks get ("waiting info")
     * @param ignoredSubtaskTypes subtasks of these types are never reopened (Defects have their own lifecycle)
     */
    public record RejectionReopen(
            boolean enabled,
            List<String> taskTypes,
            List<String> fromStatuses,
            List<String> toStatuses,
            List<String> reopenStatuses,
            @NotBlank String reopenTo,
            List<String> ignoredSubtaskTypes
    ) {

        public List<String> taskTypesOrEmpty() {
            return taskTypes == null ? List.of() : taskTypes;
        }

        public List<String> fromStatusesOrEmpty() {
            return fromStatuses == null ? List.of() : fromStatuses;
        }

        public List<String> toStatusesOrEmpty() {
            return toStatuses == null ? List.of() : toStatuses;
        }

        public List<String> reopenStatusesOrEmpty() {
            return reopenStatuses == null ? List.of() : reopenStatuses;
        }

        public List<String> ignoredSubtaskTypesOrEmpty() {
            return ignoredSubtaskTypes == null ? List.of() : ignoredSubtaskTypes;
        }
    }

    /**
     * Field defaults applied when a task is created (see CreateDefaultsWorkflow).
     */
    public record CreateDefaults(boolean enabled, List<@Valid CreateDefault> rules) {

        public List<CreateDefault> rulesOrEmpty() {
            return rules == null ? List.of() : rules;
        }
    }

    /**
     * @param taskTypes task type names this default applies to (case-insensitive)
     * @param field     custom field name (matched case-insensitively on the task's list)
     * @param value     value as text, converted to the field's type (e.g. "true" for a checkbox)
     * @param fromParent instead of {@code value}, copy the parent task's value of the field (if it has one)
     */
    public record CreateDefault(List<String> taskTypes, @NotBlank String field, String value, Boolean fromParent) {

        public List<String> taskTypesOrEmpty() {
            return taskTypes == null ? List.of() : taskTypes;
        }

        public boolean isFromParent() {
            return Boolean.TRUE.equals(fromParent);
        }
    }

    /**
     * Keeps a parent's status in step with its subtasks' statuses (see ParentStatusPolicy).
     *
     * @param parentTypes  parents of these task types follow their subtasks (Epics don't)
     * @param testingTypes these are tested as a whole: all subtasks complete -> in testing (others: complete),
     *                     and only these may be in in testing
     * @param mobileStream the stream whose tasks may be in ready_for_dev
     * @param ignoredChildTypes subtasks of these types don't affect their parent (Defects have their own lifecycle)
     */
    public record ParentStatus(
            boolean enabled,
            List<String> parentTypes,
            List<String> testingTypes,
            @NotBlank String mobileStream,
            List<String> ignoredChildTypes
    ) {

        public List<String> parentTypesOrEmpty() {
            return parentTypes == null ? List.of() : parentTypes;
        }

        public List<String> testingTypesOrEmpty() {
            return testingTypes == null ? List.of() : testingTypes;
        }

        public List<String> ignoredChildTypesOrEmpty() {
            return ignoredChildTypes == null ? List.of() : ignoredChildTypes;
        }
    }

    /**
     * @param fieldMappings       JSON object of {@code {"childFieldId": "parentFieldId"}}.
     * @param parentCustomItemId  when set, only parents of this custom task type (e.g. "Bug") are considered.
     */
    public record CopyParentFields(boolean enabled, String fieldMappings, String parentCustomItemId) {
    }

    public record TagSubtasks(boolean enabled, List<@Valid TagSubtaskRule> rules) {

        public List<TagSubtaskRule> rulesOrEmpty() {
            return rules == null ? List.of() : rules;
        }
    }

    /**
     * When {@code tag} is added to a task, create a subtask under it.
     *
     * @param tag          tag that triggers the rule (case-insensitive)
     * @param taskTypes    when non-empty, only tasks of these types trigger the rule (case-insensitive type names;
     *                     the built-in type is "Task"). Generated subtasks have the built-in type, so restricting
     *                     this to e.g. Story/Bug/Change also rules out nesting.
     * @param titlePrefix  subtask title is {@code titlePrefix + parent name}
     * @param status       status of the new subtask (must exist in the parent's list)
     * @param tags         tags for the new subtask
     * @param setFields    custom fields set to a fixed value, optionally only for some parent task types
     * @param copyFields   custom field names copied from the parent
     * @param copyPriority copy the parent's priority
     */
    public record TagSubtaskRule(
            @NotBlank String tag,
            List<String> taskTypes,
            @NotBlank String titlePrefix,
            String status,
            List<String> tags,
            List<@Valid FixedField> setFields,
            List<String> copyFields,
            boolean copyPriority
    ) {

        public List<String> taskTypesOrEmpty() {
            return taskTypes == null ? List.of() : taskTypes;
        }

        public List<String> tagsOrEmpty() {
            return tags == null ? List.of() : tags;
        }

        public List<FixedField> setFieldsOrEmpty() {
            return setFields == null ? List.of() : setFields;
        }

        public List<String> copyFieldsOrEmpty() {
            return copyFields == null ? List.of() : copyFields;
        }
    }

    /**
     * @param field       custom field name (matched case-insensitively on the parent's list)
     * @param value       value as text, converted to the field's type (e.g. "true" for a checkbox)
     * @param parentTypes when non-empty, only set if the parent's task type name is one of these
     *                    (case-insensitive; the built-in type is "Task")
     * @param fromParent  instead of {@code value}, copy the parent's value of the field (if it has one)
     */
    public record FixedField(@NotBlank String field, String value, List<String> parentTypes, Boolean fromParent) {

        public List<String> parentTypesOrEmpty() {
            return parentTypes == null ? List.of() : parentTypes;
        }

        public boolean isFromParent() {
            return Boolean.TRUE.equals(fromParent);
        }
    }
}

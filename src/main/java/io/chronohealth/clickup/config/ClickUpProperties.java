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
                            @Valid @NotNull ParentStatus parentStatus, @Valid @NotNull CreateDefaults createDefaults) {
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
     */
    public record CreateDefault(List<String> taskTypes, @NotBlank String field, @NotNull String value) {

        public List<String> taskTypesOrEmpty() {
            return taskTypes == null ? List.of() : taskTypes;
        }
    }

    /**
     * Moves a parent's status forward based on its subtasks' statuses (see ParentStatusWorkflow).
     *
     * @param parentTypes     only parents of these task types are managed (empty = any)
     * @param startedStatuses any subtask in one of these -> parent to {@code startedTarget}
     * @param startedTarget   e.g. "in progress"
     * @param doneStatuses    all subtasks in one of these -> parent to {@code doneTarget}
     * @param doneTarget      e.g. "ready for testing"
     */
    public record ParentStatus(
            boolean enabled,
            List<String> parentTypes,
            List<String> startedStatuses,
            @NotBlank String startedTarget,
            List<String> doneStatuses,
            @NotBlank String doneTarget
    ) {

        public List<String> parentTypesOrEmpty() {
            return parentTypes == null ? List.of() : parentTypes;
        }

        public List<String> startedStatusesOrEmpty() {
            return startedStatuses == null ? List.of() : startedStatuses;
        }

        public List<String> doneStatusesOrEmpty() {
            return doneStatuses == null ? List.of() : doneStatuses;
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
     */
    public record FixedField(@NotBlank String field, @NotNull String value, List<String> parentTypes) {

        public List<String> parentTypesOrEmpty() {
            return parentTypes == null ? List.of() : parentTypes;
        }
    }
}

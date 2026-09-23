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

    public record Workflows(@Valid @NotNull CopyParentFields copyParentFields, @Valid @NotNull TagSubtasks tagSubtasks) {
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
     * @param titlePrefix  subtask title is {@code titlePrefix + parent name}
     * @param status       status of the new subtask (must exist in the parent's list)
     * @param tags         tags for the new subtask
     * @param setFields    custom fields set to a fixed value, optionally only for some parent task types
     * @param copyFields   custom field names copied from the parent
     * @param copyPriority copy the parent's priority
     */
    public record TagSubtaskRule(
            @NotBlank String tag,
            @NotBlank String titlePrefix,
            String status,
            List<String> tags,
            List<@Valid FixedField> setFields,
            List<String> copyFields,
            boolean copyPriority
    ) {

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

package io.chronohealth.clickup.client.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Partial task update; null fields are left untouched by ClickUp.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TaskUpdate(
        String name,
        String description,
        String status,
        Integer priority,
        Long dueDate,
        AssigneesUpdate assignees
) {

    public static TaskUpdate status(String status) {
        return new TaskUpdate(null, null, status, null, null, null);
    }

    public static TaskUpdate assignees(AssigneesUpdate assignees) {
        return new TaskUpdate(null, null, null, null, null, assignees);
    }
}

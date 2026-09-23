package io.chronohealth.clickup.client.dto;

import java.util.List;

/**
 * Subset of ClickUp's task representation. Contains patient-adjacent data (name, field values): never log it.
 */
public record Task(
        String id,
        String name,
        String parent,
        Long customItemId,
        TaskStatus status,
        IdRef list,
        List<User> assignees,
        List<CustomField> customFields
) {

    public List<CustomField> customFieldsOrEmpty() {
        return customFields == null ? List.of() : customFields;
    }
}

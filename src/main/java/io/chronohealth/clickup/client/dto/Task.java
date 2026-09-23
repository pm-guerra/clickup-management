package io.chronohealth.clickup.client.dto;

import java.util.List;
import java.util.Optional;

/**
 * Subset of ClickUp's task representation. Contains patient-adjacent data (name, field values): never log it.
 * {@code subtasks} is only populated when requested with {@code include_subtasks}.
 */
public record Task(
        String id,
        String name,
        String parent,
        Long customItemId,
        TaskStatus status,
        Priority priority,
        IdRef list,
        List<User> assignees,
        List<Tag> tags,
        List<CustomField> customFields,
        List<Task> subtasks
) {

    public List<CustomField> customFieldsOrEmpty() {
        return customFields == null ? List.of() : customFields;
    }

    public List<Task> subtasksOrEmpty() {
        return subtasks == null ? List.of() : subtasks;
    }

    public Optional<CustomField> customFieldNamed(String fieldName) {
        return customFieldsOrEmpty().stream()
                .filter(f -> f.name() != null && f.name().trim().equalsIgnoreCase(fieldName.trim()))
                .findFirst();
    }
}

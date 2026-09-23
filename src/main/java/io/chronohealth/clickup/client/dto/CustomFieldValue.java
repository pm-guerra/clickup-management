package io.chronohealth.clickup.client.dto;

/**
 * A custom field value to write, e.g. when creating a task.
 */
public record CustomFieldValue(String id, Object value) {
}

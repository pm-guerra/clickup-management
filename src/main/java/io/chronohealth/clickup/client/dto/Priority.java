package io.chronohealth.clickup.client.dto;

/**
 * Task priority as returned by ClickUp: {@code id} is "1" (urgent) to "4" (low).
 */
public record Priority(String id, String priority) {

    public Integer level() {
        return id == null || id.isBlank() ? null : Integer.valueOf(id);
    }
}

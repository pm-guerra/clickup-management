package io.chronohealth.clickup.client.dto;

/**
 * ClickUp user reference. Only the id is mapped on purpose, to avoid holding personal data.
 */
public record User(Long id) {
}

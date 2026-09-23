package io.chronohealth.clickup.client.dto;

/**
 * A Workspace's custom task type (e.g. "Bug"). Tasks reference it via {@code custom_item_id}.
 */
public record CustomTaskType(Long id, String name) {
}

package io.chronohealth.clickup.client.dto;

import tools.jackson.databind.JsonNode;

/**
 * A custom field as returned on a task. {@code value} is absent when the field is empty.
 */
public record CustomField(String id, String name, String type, JsonNode typeConfig, JsonNode value) {

    public boolean hasValue() {
        return value != null && !value.isNull() && !value.isMissingNode();
    }
}

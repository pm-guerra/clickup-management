package io.chronohealth.clickup.client.dto;

import java.util.List;

public record Webhook(String id, String teamId, String endpoint, List<String> events, String secret) {

    @Override
    public String toString() {
        return "Webhook[id=" + id + ", teamId=" + teamId + ", endpoint=" + endpoint + ", events=" + events + ", secret=***]";
    }
}

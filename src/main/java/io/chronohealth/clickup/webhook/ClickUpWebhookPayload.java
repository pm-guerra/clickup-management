package io.chronohealth.clickup.webhook;

import io.chronohealth.clickup.client.dto.User;
import java.util.List;
import tools.jackson.databind.JsonNode;

/**
 * Raw webhook body as sent by ClickUp. It does not contain the full task; fetch it when needed.
 */
public record ClickUpWebhookPayload(String webhookId, String event, String taskId, List<HistoryItem> historyItems) {

    /**
     * {@code before}/{@code after} carry the changed values. They can contain task content, so
     * {@link EventNormalizer} only keeps them for fields that are safe to store (tags).
     */
    public record HistoryItem(String id, String type, String date, String field, String parentId, User user,
                              JsonNode before, JsonNode after) {

        public HistoryItem withoutValues() {
            return new HistoryItem(id, type, date, field, parentId, user, null, null);
        }
    }
}

package io.chronohealth.clickup.webhook;

import io.chronohealth.clickup.client.dto.User;
import java.util.List;

/**
 * Raw webhook body as sent by ClickUp. It does not contain the full task; fetch it when needed.
 */
public record ClickUpWebhookPayload(String webhookId, String event, String taskId, List<HistoryItem> historyItems) {

    public record HistoryItem(String id, String type, String date, String field, String parentId, User user) {
    }
}

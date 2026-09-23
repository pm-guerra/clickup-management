package io.chronohealth.clickup.webhook;

import java.util.List;

/**
 * A verified, normalized webhook event, ready for dispatch to business workflows.
 */
public record ClickUpEvent(
        String idempotencyKey,
        String webhookId,
        String workspaceId,
        String type,
        String taskId,
        List<ClickUpWebhookPayload.HistoryItem> historyItems
) {

    public boolean isType(String eventType) {
        return eventType.equals(type);
    }
}

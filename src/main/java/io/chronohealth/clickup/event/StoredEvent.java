package io.chronohealth.clickup.event;

import java.time.OffsetDateTime;

/**
 * A row of {@code webhook_event}. {@code payload} is the normalized event as JSON.
 */
public record StoredEvent(
        long id,
        String idempotencyKey,
        String webhookId,
        String workspaceId,
        String eventType,
        String taskId,
        String payload,
        EventStatus status,
        int attempts,
        OffsetDateTime nextAttemptAt,
        String lastError,
        OffsetDateTime receivedAt,
        OffsetDateTime updatedAt
) {
}

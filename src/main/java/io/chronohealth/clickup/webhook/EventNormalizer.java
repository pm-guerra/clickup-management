package io.chronohealth.clickup.webhook;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class EventNormalizer {

    /**
     * History fields whose before/after values are kept: tag and status names are workspace metadata, not task
     * content.
     */
    private static final Set<String> FIELDS_WITH_SAFE_VALUES = Set.of("tag", "tag_removed", "status");

    public ClickUpEvent normalize(ClickUpWebhookPayload payload, WebhookRegistration registration, byte[] rawBody) {
        List<ClickUpWebhookPayload.HistoryItem> historyItems = payload.historyItems() == null
                ? List.of()
                : payload.historyItems().stream().map(EventNormalizer::stripUnsafeValues).toList();
        return new ClickUpEvent(
                idempotencyKey(payload.webhookId(), historyItems, rawBody),
                payload.webhookId(),
                registration.workspaceId(),
                payload.event(),
                payload.taskId(),
                historyItems);
    }

    /**
     * ClickUp recommends {@code webhook_id:history_item_id}. When a delivery carries several history items they're
     * joined in order. Without history items, falls back to a hash of the raw body, which is stable across
     * ClickUp's retries of the same delivery.
     */
    static String idempotencyKey(String webhookId, List<ClickUpWebhookPayload.HistoryItem> historyItems, byte[] rawBody) {
        List<String> ids = historyItems.stream()
                .map(ClickUpWebhookPayload.HistoryItem::id)
                .filter(Objects::nonNull)
                .toList();
        String suffix = ids.isEmpty() ? "body-" + sha256(rawBody) : String.join(",", ids);
        String key = webhookId + ":" + suffix;
        // Keep within the column size; hashing preserves determinism.
        return key.length() <= 255 ? key : webhookId + ":hash-" + sha256(suffix.getBytes());
    }

    private static ClickUpWebhookPayload.HistoryItem stripUnsafeValues(ClickUpWebhookPayload.HistoryItem item) {
        return item.field() != null && FIELDS_WITH_SAFE_VALUES.contains(item.field()) ? item : item.withoutValues();
    }

    private static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}

package io.chronohealth.clickup.webhook;

import io.chronohealth.clickup.mention.Mentions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

@Component
public class EventNormalizer {

    /**
     * History fields whose before/after values are kept: tag, status and priority names are workspace metadata, not
     * task content.
     */
    private static final Set<String> FIELDS_WITH_SAFE_VALUES = Set.of("tag", "tag_removed", "status", "priority");
    private static final String CUSTOM_FIELD = "custom_field";
    /**
     * Custom field types whose values are kept: a drop-down value is an option id (or index), never free text.
     */
    private static final Set<String> CUSTOM_FIELD_TYPES_WITH_SAFE_VALUES = Set.of("drop_down");
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    public ClickUpEvent normalize(ClickUpWebhookPayload payload, WebhookRegistration registration, byte[] rawBody) {
        List<ClickUpWebhookPayload.HistoryItem> historyItems = payload.historyItems() == null
                ? List.of()
                : payload.historyItems().stream()
                        .map(EventNormalizer::reduceComment)
                        .map(EventNormalizer::reduceCustomField)
                        .map(EventNormalizer::stripUnsafeValues)
                        .toList();
        return new ClickUpEvent(
                idempotencyKey(payload.webhookId(), payload.event(), historyItems, rawBody),
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
     * <p>
     * The event type is part of the key: ClickUp sends one change as several events sharing a history item (a
     * comment arrives as both taskCommentPosted and taskUpdated), and each must be kept.
     */
    static String idempotencyKey(String webhookId, String eventType,
                                 List<ClickUpWebhookPayload.HistoryItem> historyItems, byte[] rawBody) {
        List<String> ids = historyItems.stream()
                .map(ClickUpWebhookPayload.HistoryItem::id)
                .filter(Objects::nonNull)
                .toList();
        String suffix = ids.isEmpty() ? "body-" + sha256(rawBody) : String.join(",", ids);
        String prefix = webhookId + ":" + eventType + ":";
        String key = prefix + suffix;
        // Keep within the column size; hashing preserves determinism.
        return key.length() <= 255 ? key : prefix + "hash-" + sha256(suffix.getBytes());
    }

    private static ClickUpWebhookPayload.HistoryItem reduceComment(ClickUpWebhookPayload.HistoryItem item) {
        if (item.comment() == null || item.comment().isNull() || item.comment().isMissingNode()) {
            return item;
        }
        String commentId = item.comment().path("id").asString(item.id());
        return item.withCommentReduced(Mentions.inComment(item.comment()), commentId);
    }

    /**
     * Keeps only the custom field's id, name and type (its definition also lists every option, which isn't needed).
     */
    private static ClickUpWebhookPayload.HistoryItem reduceCustomField(ClickUpWebhookPayload.HistoryItem item) {
        JsonNode field = item.customField();
        if (field == null || field.isNull() || field.isMissingNode()) {
            return item;
        }
        ObjectNode reduced = MAPPER.createObjectNode();
        reduced.put("id", field.path("id").asString(null));
        reduced.put("name", field.path("name").asString(null));
        reduced.put("type", field.path("type").asString(null));
        return item.withCustomField(reduced);
    }

    private static ClickUpWebhookPayload.HistoryItem stripUnsafeValues(ClickUpWebhookPayload.HistoryItem item) {
        if (item.field() != null && FIELDS_WITH_SAFE_VALUES.contains(item.field())) {
            return item;
        }
        if (CUSTOM_FIELD.equals(item.field()) && item.customField() != null
                && CUSTOM_FIELD_TYPES_WITH_SAFE_VALUES.contains(item.customField().path("type").asString(""))) {
            return item;
        }
        return item.withoutValues();
    }

    private static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}

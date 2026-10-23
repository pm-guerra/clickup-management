package io.chronohealth.clickup.webhook;

import io.chronohealth.clickup.client.dto.User;
import java.util.List;
import tools.jackson.databind.JsonNode;

/**
 * Raw webhook body as sent by ClickUp. It does not contain the full task; fetch it when needed.
 */
public record ClickUpWebhookPayload(String webhookId, String event, String taskId, List<HistoryItem> historyItems) {

    /**
     * {@code before}/{@code after} carry the changed values and {@code comment} a posted comment. They can contain
     * task content, so {@link EventNormalizer} only keeps values for fields that are safe to store (tags, statuses),
     * and replaces a comment by its id and the ids of the users it mentions.
     */
    public record HistoryItem(String id, String type, String date, String field, String parentId, User user,
                              JsonNode before, JsonNode after, JsonNode comment, List<Long> mentionedUserIds,
                              String commentId) {

        public HistoryItem(String id, String type, String date, String field, String parentId, User user,
                           JsonNode before, JsonNode after) {
            this(id, type, date, field, parentId, user, before, after, null, null, null);
        }

        public HistoryItem withoutValues() {
            return new HistoryItem(id, type, date, field, parentId, user, null, null, null, mentionedUserIds,
                    commentId);
        }

        /**
         * Keeps only who the comment mentions and its id; drops the comment itself.
         */
        public HistoryItem withCommentReduced(List<Long> mentions, String reducedCommentId) {
            return new HistoryItem(id, type, date, field, parentId, user, before, after, null, mentions,
                    reducedCommentId);
        }

        public List<Long> mentionedUserIdsOrEmpty() {
            return mentionedUserIds == null ? List.of() : mentionedUserIds;
        }
    }
}

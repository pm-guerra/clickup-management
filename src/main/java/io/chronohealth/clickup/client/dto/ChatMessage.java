package io.chronohealth.clickup.client.dto;

import java.time.Instant;

/**
 * A ClickUp Chat message or thread reply. {@code content} is the message text (markdown): read it only to find
 * mentions, never store or log it.
 */
public record ChatMessage(String id, String userId, Long date, String content, Integer repliesCount) {

    public Instant postedAt() {
        return date == null ? Instant.EPOCH : Instant.ofEpochMilli(date);
    }

    public int repliesCountOrZero() {
        return repliesCount == null ? 0 : repliesCount;
    }

    public Long authorId() {
        try {
            return userId == null ? null : Long.valueOf(userId);
        } catch (NumberFormatException _) {
            return null;
        }
    }

    @Override
    public String toString() {
        return "ChatMessage[id=" + id + ", userId=" + userId + ", date=" + date + "]";
    }
}

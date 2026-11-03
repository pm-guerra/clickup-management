package io.chronohealth.clickup.client.dto;

/**
 * A comment on a task. {@code commentText} is the comment as plain text: hand it on, never store or log it.
 * {@code replyCount} is the number of replies in its thread.
 */
public record TaskComment(String id, String commentText, Member user, String date, Integer replyCount) {

    public boolean hasReplies() {
        return replyCount != null && replyCount > 0;
    }

    @Override
    public String toString() {
        return "TaskComment[id=" + id + ", user=" + user + ", date=" + date + ", replyCount=" + replyCount + "]";
    }
}

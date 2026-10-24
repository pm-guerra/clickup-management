package io.chronohealth.clickup.client.dto;

/**
 * A comment on a task. {@code commentText} is the comment as plain text: hand it on, never store or log it.
 */
public record TaskComment(String id, String commentText, Member user, String date) {

    @Override
    public String toString() {
        return "TaskComment[id=" + id + ", user=" + user + ", date=" + date + "]";
    }
}

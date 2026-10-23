package io.chronohealth.clickup.mention;

import io.chronohealth.clickup.mention.MentionRepository.NewMention;
import io.chronohealth.clickup.webhook.ClickUpEvent;
import io.chronohealth.clickup.webhook.ClickUpWebhookPayload.HistoryItem;
import io.chronohealth.clickup.webhook.EventHandler;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Records a mention of the bot in a task comment ({@code taskCommentPosted}). The comment's mentions were extracted
 * when the event arrived; the comment text itself is never stored.
 */
@Component
public class CommentMentionHandler implements EventHandler {

    static final String EVENT = "taskCommentPosted";
    static final String SOURCE = "task_comment";

    private static final Logger log = LoggerFactory.getLogger(CommentMentionHandler.class);

    private final MentionRepository repository;
    private final MentionProperties properties;
    private final Clock clock;

    public CommentMentionHandler(MentionRepository repository, MentionProperties properties, Clock clock) {
        this.repository = repository;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public Set<String> eventTypes() {
        return properties.enabled() && properties.botUserId() != null ? Set.of(EVENT) : Set.of();
    }

    @Override
    public void handle(ClickUpEvent event) {
        for (HistoryItem item : event.historyItems()) {
            Long author = item.user() == null ? null : item.user().id();
            if (!item.mentionedUserIdsOrEmpty().contains(properties.botUserId())
                    || Objects.equals(author, properties.botUserId())) {
                continue;
            }
            String commentId = item.commentId() == null ? item.id() : item.commentId();
            boolean recorded = repository.record(new NewMention(SOURCE, commentId, event.taskId(), null, null, author,
                    postedAt(item)));
            if (recorded) {
                log.info("Bot mentioned in a comment on task {} (comment {}, by user {})", event.taskId(), commentId,
                        author);
            }
        }
    }

    private Instant postedAt(HistoryItem item) {
        try {
            return item.date() == null ? clock.instant() : Instant.ofEpochMilli(Long.parseLong(item.date()));
        } catch (NumberFormatException _) {
            return clock.instant();
        }
    }
}

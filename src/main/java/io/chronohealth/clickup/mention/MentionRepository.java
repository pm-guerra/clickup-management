package io.chronohealth.clickup.mention;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class MentionRepository {

    private static final String COLUMNS = "id, source, item_id, task_id, channel_id, parent_message_id, author_user_id, "
            + "mentioned_at, recorded_at, forwarded_at";

    private final JdbcClient jdbc;
    private final Clock clock;

    public MentionRepository(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /**
     * @return true if it's new; recording the same comment/message again is a no-op
     */
    public boolean record(NewMention mention) {
        try {
            jdbc.sql("""
                            insert into octo_mention (source, item_id, task_id, channel_id, parent_message_id,
                                                      author_user_id, mentioned_at, recorded_at)
                            values (:source, :itemId, :taskId, :channelId, :parentId, :author, :mentionedAt, :now)
                            """)
                    .param("source", mention.source())
                    .param("itemId", mention.itemId())
                    .param("taskId", mention.taskId())
                    .param("channelId", mention.channelId())
                    .param("parentId", mention.parentMessageId())
                    .param("author", mention.authorUserId())
                    .param("mentionedAt", utc(mention.mentionedAt()))
                    .param("now", now())
                    .update();
            return true;
        } catch (DuplicateKeyException _) {
            return false;
        }
    }

    public List<StoredMention> findRecent(int limit) {
        return jdbc.sql("select " + COLUMNS + " from octo_mention order by mentioned_at desc limit :limit")
                .param("limit", limit)
                .query(this::map)
                .list();
    }

    /**
     * When polling of the channel started; set to now on first use, so older messages are never new mentions.
     */
    @Transactional
    public Instant channelStartedAt(String channelId) {
        Optional<OffsetDateTime> started = jdbc.sql("select started_at from chat_poll_state where channel_id = :id")
                .param("id", channelId)
                .query(OffsetDateTime.class)
                .optional();
        if (started.isPresent()) {
            return started.get().toInstant();
        }
        OffsetDateTime now = now();
        jdbc.sql("insert into chat_poll_state (channel_id, started_at) values (:id, :now)")
                .param("id", channelId)
                .param("now", now)
                .update();
        return now.toInstant();
    }

    public int knownReplyCount(String messageId) {
        return jdbc.sql("select replies_count from chat_thread_state where message_id = :id")
                .param("id", messageId)
                .query(Integer.class)
                .optional()
                .orElse(0);
    }

    @Transactional
    public void saveReplyCount(String messageId, String channelId, int count) {
        int updated = jdbc.sql("update chat_thread_state set replies_count = :count where message_id = :id")
                .param("count", count)
                .param("id", messageId)
                .update();
        if (updated == 0) {
            jdbc.sql("insert into chat_thread_state (message_id, channel_id, replies_count) values (:id, :channel, :count)")
                    .param("id", messageId)
                    .param("channel", channelId)
                    .param("count", count)
                    .update();
        }
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
    }

    private StoredMention map(ResultSet rs, int rowNum) throws SQLException {
        return new StoredMention(
                rs.getLong("id"),
                rs.getString("source"),
                rs.getString("item_id"),
                rs.getString("task_id"),
                rs.getString("channel_id"),
                rs.getString("parent_message_id"),
                rs.getObject("author_user_id", Long.class),
                rs.getObject("mentioned_at", OffsetDateTime.class),
                rs.getObject("recorded_at", OffsetDateTime.class),
                rs.getObject("forwarded_at", OffsetDateTime.class));
    }

    public record NewMention(String source, String itemId, String taskId, String channelId, String parentMessageId,
                             Long authorUserId, Instant mentionedAt) {
    }

    public record StoredMention(long id, String source, String itemId, String taskId, String channelId,
                                String parentMessageId, Long authorUserId, OffsetDateTime mentionedAt,
                                OffsetDateTime recordedAt, OffsetDateTime forwardedAt) {
    }
}

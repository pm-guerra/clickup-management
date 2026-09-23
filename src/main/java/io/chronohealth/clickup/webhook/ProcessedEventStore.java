package io.chronohealth.clickup.webhook;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Idempotency records. An event is "claimed" before processing (insert on its primary key), marked completed
 * afterwards, and released if processing fails so ClickUp's retry can process it again.
 */
@Repository
public class ProcessedEventStore {

    /**
     * A claim that was never completed or released (e.g. the process died) can be taken over after this.
     */
    private static final Duration STALE_CLAIM = Duration.ofMinutes(5);

    private final JdbcClient jdbc;
    private final Clock clock;

    public ProcessedEventStore(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /**
     * @return true if the caller now owns processing of this event, false if it was already processed or is in progress
     */
    public boolean tryClaim(ClickUpEvent event) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        try {
            jdbc.sql("""
                            insert into processed_event (idempotency_key, webhook_id, event, task_id, received_at)
                            values (:key, :webhookId, :event, :taskId, :now)
                            """)
                    .param("key", event.idempotencyKey())
                    .param("webhookId", event.webhookId())
                    .param("event", event.type())
                    .param("taskId", event.taskId())
                    .param("now", now)
                    .update();
            return true;
        } catch (DuplicateKeyException _) {
            int reclaimed = jdbc.sql("""
                            update processed_event set received_at = :now
                             where idempotency_key = :key and completed_at is null and received_at < :cutoff
                            """)
                    .param("now", now)
                    .param("key", event.idempotencyKey())
                    .param("cutoff", now.minus(STALE_CLAIM))
                    .update();
            return reclaimed == 1;
        }
    }

    public void markCompleted(ClickUpEvent event) {
        jdbc.sql("update processed_event set completed_at = :now where idempotency_key = :key")
                .param("now", OffsetDateTime.now(clock))
                .param("key", event.idempotencyKey())
                .update();
    }

    public void release(ClickUpEvent event) {
        jdbc.sql("delete from processed_event where idempotency_key = :key and completed_at is null")
                .param("key", event.idempotencyKey())
                .update();
    }
}

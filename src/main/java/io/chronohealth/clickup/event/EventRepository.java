package io.chronohealth.clickup.event;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

@Repository
public class EventRepository {

    private static final String COLUMNS = """
            id, idempotency_key, webhook_id, workspace_id, event_type, task_id, payload, status, attempts,
            next_attempt_at, last_error, received_at, updated_at""";

    /**
     * Due = waiting for a (re)try whose time has come, or stuck in PROCESSING past its lock.
     */
    private static final String DUE = """
            ((status in ('PENDING', 'FAILED') and next_attempt_at <= :now)
              or (status = 'PROCESSING' and locked_until < :now))""";

    private final JdbcClient jdbc;
    private final Clock clock;

    public EventRepository(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /**
     * @return the new row id, or empty if an event with the same idempotency key already exists
     */
    public Optional<Long> insert(NewEvent event) {
        OffsetDateTime now = now();
        KeyHolder keys = new GeneratedKeyHolder();
        try {
            jdbc.sql("""
                            insert into webhook_event (idempotency_key, webhook_id, workspace_id, event_type, task_id, payload,
                                                       status, attempts, next_attempt_at, received_at, updated_at)
                            values (:key, :webhookId, :workspaceId, :eventType, :taskId, :payload,
                                    'PENDING', 0, :now, :now, :now)
                            """)
                    .param("key", event.idempotencyKey())
                    .param("webhookId", event.webhookId())
                    .param("workspaceId", event.workspaceId())
                    .param("eventType", event.eventType())
                    .param("taskId", event.taskId())
                    .param("payload", event.payload())
                    .param("now", now)
                    .update(keys, "id");
        } catch (DuplicateKeyException _) {
            return Optional.empty();
        }
        return Optional.of(keys.getKeyAs(Number.class).longValue());
    }

    public List<Long> findDueIds(int limit) {
        return jdbc.sql("select id from webhook_event where " + DUE + " order by next_attempt_at limit :limit")
                .param("now", now())
                .param("limit", limit)
                .query(Long.class)
                .list();
    }

    /**
     * Atomically takes ownership of a due event and counts the attempt. Safe with concurrent workers:
     * only one conditional update can win.
     */
    public Optional<StoredEvent> claim(long id, OffsetDateTime lockedUntil) {
        int updated = jdbc.sql("update webhook_event set status = 'PROCESSING', attempts = attempts + 1, "
                        + "locked_until = :lockedUntil, updated_at = :now where id = :id and " + DUE)
                .param("lockedUntil", lockedUntil)
                .param("now", now())
                .param("id", id)
                .update();
        return updated == 1 ? findById(id) : Optional.empty();
    }

    public void markSucceeded(long id) {
        jdbc.sql("""
                        update webhook_event set status = 'SUCCEEDED', locked_until = null, last_error = null, updated_at = :now
                         where id = :id""")
                .param("now", now())
                .param("id", id)
                .update();
    }

    public void markFailed(long id, OffsetDateTime nextAttemptAt, String error) {
        jdbc.sql("""
                        update webhook_event set status = 'FAILED', locked_until = null, next_attempt_at = :next,
                               last_error = :error, updated_at = :now
                         where id = :id""")
                .param("next", nextAttemptAt)
                .param("error", error)
                .param("now", now())
                .param("id", id)
                .update();
    }

    public void markDead(long id, String error) {
        jdbc.sql("""
                        update webhook_event set status = 'DEAD', locked_until = null, last_error = :error, updated_at = :now
                         where id = :id""")
                .param("error", error)
                .param("now", now())
                .param("id", id)
                .update();
    }

    /**
     * Makes a FAILED or DEAD event due now, with a fresh attempt budget.
     */
    public boolean requeue(long id) {
        return jdbc.sql("""
                        update webhook_event set status = 'PENDING', attempts = 0, next_attempt_at = :now, updated_at = :now
                         where id = :id and status in ('FAILED', 'DEAD')""")
                .param("now", now())
                .param("id", id)
                .update() == 1;
    }

    public Optional<StoredEvent> findById(long id) {
        return jdbc.sql("select " + COLUMNS + " from webhook_event where id = :id")
                .param("id", id)
                .query(this::map)
                .optional();
    }

    public List<StoredEvent> findByStatus(EventStatus status, int limit) {
        return jdbc.sql("select " + COLUMNS + " from webhook_event where status = :status "
                        + "order by received_at desc limit :limit")
                .param("status", status.name())
                .param("limit", limit)
                .query(this::map)
                .list();
    }

    public int deleteSucceededBefore(OffsetDateTime cutoff) {
        return jdbc.sql("delete from webhook_event where status = 'SUCCEEDED' and updated_at < :cutoff")
                .param("cutoff", cutoff)
                .update();
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC);
    }

    private StoredEvent map(ResultSet rs, int rowNum) throws SQLException {
        return new StoredEvent(
                rs.getLong("id"),
                rs.getString("idempotency_key"),
                rs.getString("webhook_id"),
                rs.getString("workspace_id"),
                rs.getString("event_type"),
                rs.getString("task_id"),
                rs.getString("payload"),
                EventStatus.valueOf(rs.getString("status")),
                rs.getInt("attempts"),
                rs.getObject("next_attempt_at", OffsetDateTime.class),
                rs.getString("last_error"),
                rs.getObject("received_at", OffsetDateTime.class),
                rs.getObject("updated_at", OffsetDateTime.class));
    }

    public record NewEvent(String idempotencyKey, String webhookId, String workspaceId, String eventType,
                           String taskId, String payload) {
    }
}

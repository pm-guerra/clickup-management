package io.chronohealth.clickup.audit;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class AuditRepository {

    private static final int STATE_ID = 1;
    private static final int EVENTS_STATE_ID = 2;
    /**
     * Findings from event rules are one-off (a change someone made); their rule ids carry this prefix.
     */
    static final String EVENT_PREFIX = "event:";
    private static final String COLUMNS =
            "id, rule_id, task_id, details, first_seen_at, last_seen_at, resolved_at, notified_at";

    private final JdbcClient jdbc;
    private final Clock clock;

    public AuditRepository(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public Optional<OffsetDateTime> lastCheckedUntil() {
        return checkpoint(STATE_ID);
    }

    public void saveLastCheckedUntil(OffsetDateTime until) {
        saveCheckpoint(STATE_ID, until);
    }

    /**
     * How far the event (who-did-what) checks got; separate from the task window.
     */
    public Optional<OffsetDateTime> eventsCheckedUntil() {
        return checkpoint(EVENTS_STATE_ID);
    }

    public void saveEventsCheckedUntil(OffsetDateTime until) {
        saveCheckpoint(EVENTS_STATE_ID, until);
    }

    /**
     * Records a wrong change. One row per (rule, event); recording the same one again is a no-op.
     */
    public void recordEventFinding(String ruleId, long eventId, int index, String taskId, String details) {
        String key = EVENT_PREFIX + ruleId + ":" + eventId + ":" + index;
        if (find(key, taskId).isPresent()) {
            return;
        }
        OffsetDateTime now = now();
        jdbc.sql("""
                        insert into audit_violation (rule_id, task_id, details, first_seen_at, last_seen_at)
                        values (:rule, :task, :details, :now, :now)
                        """)
                .param("rule", key)
                .param("task", taskId)
                .param("details", truncate(details))
                .param("now", now)
                .update();
    }

    /**
     * Event findings don't stay open once reported.
     */
    public void closeReportedEventFindings() {
        jdbc.sql("update audit_violation set resolved_at = :now "
                        + "where rule_id like :prefix and notified_at is not null and resolved_at is null")
                .param("now", now())
                .param("prefix", EVENT_PREFIX + "%")
                .update();
    }

    /**
     * Records the outcome of one rule on one task: opens (or reopens) a finding, refreshes an open one, or resolves
     * it when the task now complies. Reopened findings are reported again.
     */
    @Transactional
    public void reconcile(String ruleId, String taskId, Optional<String> finding) {
        OffsetDateTime now = now();
        Optional<Violation> existing = find(ruleId, taskId);
        if (finding.isEmpty()) {
            if (existing.isPresent() && existing.get().isOpen()) {
                jdbc.sql("update audit_violation set resolved_at = :now where id = :id")
                        .param("now", now)
                        .param("id", existing.get().id())
                        .update();
            }
            return;
        }
        String details = truncate(finding.get());
        if (existing.isEmpty()) {
            jdbc.sql("""
                            insert into audit_violation (rule_id, task_id, details, first_seen_at, last_seen_at)
                            values (:rule, :task, :details, :now, :now)
                            """)
                    .param("rule", ruleId)
                    .param("task", taskId)
                    .param("details", details)
                    .param("now", now)
                    .update();
        } else if (existing.get().isOpen()) {
            jdbc.sql("update audit_violation set details = :details, last_seen_at = :now where id = :id")
                    .param("details", details)
                    .param("now", now)
                    .param("id", existing.get().id())
                    .update();
        } else {
            jdbc.sql("""
                            update audit_violation
                               set details = :details, first_seen_at = :now, last_seen_at = :now,
                                   resolved_at = null, notified_at = null
                             where id = :id
                            """)
                    .param("details", details)
                    .param("now", now)
                    .param("id", existing.get().id())
                    .update();
        }
    }

    public List<Violation> findOpenUnnotified() {
        return jdbc.sql("select " + COLUMNS + " from audit_violation where resolved_at is null and notified_at is null "
                        + "order by first_seen_at")
                .query(this::map)
                .list();
    }

    public List<Violation> findOpen() {
        return jdbc.sql("select " + COLUMNS + " from audit_violation where resolved_at is null order by first_seen_at")
                .query(this::map)
                .list();
    }

    /**
     * Forgets all findings and the last-checked position, so the next run re-checks from the initial lookback.
     */
    @Transactional
    public void reset() {
        jdbc.sql("delete from audit_violation").update();
        jdbc.sql("delete from audit_state").update();
    }

    public boolean delete(long id) {
        return jdbc.sql("delete from audit_violation where id = :id").param("id", id).update() == 1;
    }

    public void markNotified(List<Long> ids) {
        if (ids.isEmpty()) {
            return;
        }
        jdbc.sql("update audit_violation set notified_at = :now where id in (:ids)")
                .param("now", now())
                .param("ids", ids)
                .update();
    }

    private Optional<OffsetDateTime> checkpoint(int id) {
        return jdbc.sql("select last_checked_until from audit_state where id = :id")
                .param("id", id)
                .query(OffsetDateTime.class)
                .optional();
    }

    @Transactional
    void saveCheckpoint(int id, OffsetDateTime until) {
        int updated = jdbc.sql("update audit_state set last_checked_until = :until where id = :id")
                .param("until", until)
                .param("id", id)
                .update();
        if (updated == 0) {
            jdbc.sql("insert into audit_state (id, last_checked_until) values (:id, :until)")
                    .param("id", id)
                    .param("until", until)
                    .update();
        }
    }

    private Optional<Violation> find(String ruleId, String taskId) {
        return jdbc.sql("select " + COLUMNS + " from audit_violation where rule_id = :rule and task_id = :task")
                .param("rule", ruleId)
                .param("task", taskId)
                .query(this::map)
                .optional();
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC);
    }

    private static String truncate(String value) {
        return value.length() <= 1000 ? value : value.substring(0, 1000);
    }

    private Violation map(ResultSet rs, int rowNum) throws SQLException {
        return new Violation(
                rs.getLong("id"),
                rs.getString("rule_id"),
                rs.getString("task_id"),
                rs.getString("details"),
                rs.getObject("first_seen_at", OffsetDateTime.class),
                rs.getObject("last_seen_at", OffsetDateTime.class),
                rs.getObject("resolved_at", OffsetDateTime.class),
                rs.getObject("notified_at", OffsetDateTime.class));
    }

    public record Violation(long id, String ruleId, String taskId, String details, OffsetDateTime firstSeenAt,
                            OffsetDateTime lastSeenAt, OffsetDateTime resolvedAt, OffsetDateTime notifiedAt) {

        public boolean isOpen() {
            return resolvedAt == null;
        }
    }
}

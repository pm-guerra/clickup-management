package io.chronohealth.clickup.oauth;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * One-time OAuth "state" values. Persisted (not in-memory) so the flow survives restarts and multiple replicas.
 */
@Repository
public class OAuthStateStore {

    private static final Duration TTL = Duration.ofMinutes(10);

    private final JdbcClient jdbc;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public OAuthStateStore(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Transactional
    public String create() {
        OffsetDateTime now = OffsetDateTime.now(clock);
        jdbc.sql("delete from oauth_state where created_at < :cutoff")
                .param("cutoff", now.minus(TTL))
                .update();

        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String state = HexFormat.of().formatHex(bytes);
        jdbc.sql("insert into oauth_state (state, created_at) values (:state, :now)")
                .param("state", state)
                .param("now", now)
                .update();
        return state;
    }

    /**
     * Deletes the state and returns true if it existed and hadn't expired.
     */
    @Transactional
    public boolean consume(String state) {
        Optional<OffsetDateTime> createdAt = jdbc.sql("select created_at from oauth_state where state = :state")
                .param("state", state)
                .query(OffsetDateTime.class)
                .optional();
        if (createdAt.isEmpty()) {
            return false;
        }
        jdbc.sql("delete from oauth_state where state = :state").param("state", state).update();
        return createdAt.get().isAfter(OffsetDateTime.now(clock).minus(TTL));
    }
}

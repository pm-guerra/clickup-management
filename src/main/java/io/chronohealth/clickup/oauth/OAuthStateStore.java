package io.chronohealth.clickup.oauth;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * One-time OAuth "state" values, kept in memory (the service runs as a single instance).
 */
@Component
public class OAuthStateStore {

    private static final Duration TTL = Duration.ofMinutes(10);

    private final Map<String, Instant> states = new ConcurrentHashMap<>();
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public OAuthStateStore(Clock clock) {
        this.clock = clock;
    }

    public String create() {
        Instant now = clock.instant();
        states.values().removeIf(createdAt -> createdAt.isBefore(now.minus(TTL)));

        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String state = HexFormat.of().formatHex(bytes);
        states.put(state, now);
        return state;
    }

    /**
     * Removes the state and returns true if it existed and hadn't expired.
     */
    public boolean consume(String state) {
        Instant createdAt = states.remove(state);
        return createdAt != null && createdAt.isAfter(clock.instant().minus(TTL));
    }
}

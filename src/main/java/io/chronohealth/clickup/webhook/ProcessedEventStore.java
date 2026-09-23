package io.chronohealth.clickup.webhook;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * In-memory idempotency records. An event is "claimed" before processing, marked completed afterwards, and
 * released if processing fails so ClickUp's retry can process it again.
 * <p>
 * Being in memory, this only deduplicates within one running instance, which is why the service is deployed
 * with a single instance. ClickUp's duplicate deliveries arrive within minutes, well inside {@link #RETENTION}.
 */
@Component
public class ProcessedEventStore {

    private static final Duration RETENTION = Duration.ofHours(24);
    /**
     * A claim that was never completed or released (e.g. a hung request) can be taken over after this.
     */
    private static final Duration STALE_CLAIM = Duration.ofMinutes(5);

    private final Map<String, Entry> entries = new ConcurrentHashMap<>();
    private final Clock clock;

    public ProcessedEventStore(Clock clock) {
        this.clock = clock;
    }

    /**
     * @return true if the caller now owns processing of this event, false if it was already processed or is in progress
     */
    public boolean tryClaim(ClickUpEvent event) {
        Instant now = clock.instant();
        evictExpired(now);
        boolean[] claimed = {false};
        entries.compute(event.idempotencyKey(), (_, existing) -> {
            if (existing == null || (!existing.completed() && existing.claimedAt().isBefore(now.minus(STALE_CLAIM)))) {
                claimed[0] = true;
                return new Entry(now, false);
            }
            return existing;
        });
        return claimed[0];
    }

    public void markCompleted(ClickUpEvent event) {
        entries.computeIfPresent(event.idempotencyKey(), (_, e) -> new Entry(e.claimedAt(), true));
    }

    public void release(ClickUpEvent event) {
        entries.computeIfPresent(event.idempotencyKey(), (_, e) -> e.completed() ? e : null);
    }

    private void evictExpired(Instant now) {
        Instant cutoff = now.minus(RETENTION);
        entries.values().removeIf(e -> e.claimedAt().isBefore(cutoff));
    }

    private record Entry(Instant claimedAt, boolean completed) {
    }
}

package io.chronohealth.clickup.workflow;

import io.chronohealth.clickup.client.ClickUpClient;
import io.chronohealth.clickup.client.dto.TaskStatus;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * A list's statuses in board order, cached briefly so status workflows don't fetch the list on every event.
 */
@Component
public class ListStatusResolver {

    private static final Duration TTL = Duration.ofMinutes(10);

    private final Clock clock;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    public ListStatusResolver(Clock clock) {
        this.clock = clock;
    }

    /**
     * Position of {@code status} in the list's order (ClickUp's {@code orderindex}), if the list has it.
     */
    public Optional<Integer> orderOf(ClickUpClient client, String listId, String status) {
        Cached cached = cache.get(listId);
        if (cached == null || cached.fetchedAt().plus(TTL).isBefore(clock.instant())) {
            cached = new Cached(client.getListStatuses(listId), clock.instant());
            cache.put(listId, cached);
        }
        String wanted = status.trim().toLowerCase(Locale.ROOT);
        return cached.statuses().stream()
                .filter(s -> s.status() != null && s.status().trim().toLowerCase(Locale.ROOT).equals(wanted))
                .map(TaskStatus::orderindex)
                .findFirst();
    }

    private record Cached(List<TaskStatus> statuses, Instant fetchedAt) {
    }
}

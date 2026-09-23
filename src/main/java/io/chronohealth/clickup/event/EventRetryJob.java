package io.chronohealth.clickup.event;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Processes due events (new ones whose inline attempt didn't complete, failed ones whose backoff elapsed, stuck
 * ones whose lock expired) and prunes old successful ones.
 * <p>
 * Triggered by Cloud Scheduler via {@code POST /admin/events/process-due} when deployed (Cloud Run throttles CPU
 * between requests, so in-process timers are unreliable there), and by {@link EventRetryScheduler} locally.
 */
@Service
public class EventRetryJob {

    private static final Logger log = LoggerFactory.getLogger(EventRetryJob.class);

    private final EventRepository repository;
    private final EventProcessor processor;
    private final EventProperties properties;
    private final Clock clock;

    public EventRetryJob(EventRepository repository, EventProcessor processor, EventProperties properties, Clock clock) {
        this.repository = repository;
        this.processor = processor;
        this.properties = properties;
        this.clock = clock;
    }

    public RunResult run() {
        List<Long> due = repository.findDueIds(properties.batchSize());
        int succeeded = 0;
        int failed = 0;
        for (long id : due) {
            EventStatus status = processor.process(id).orElse(null);
            if (status == EventStatus.SUCCEEDED) {
                succeeded++;
            } else if (status != null) {
                failed++;
            }
        }
        int pruned = repository.deleteSucceededBefore(OffsetDateTime.now(clock).minus(properties.retention()));
        if (!due.isEmpty() || pruned > 0) {
            log.info("Retry run: {} due, {} succeeded, {} failed, {} pruned", due.size(), succeeded, failed, pruned);
        }
        return new RunResult(due.size(), succeeded, failed, pruned);
    }

    public record RunResult(int due, int succeeded, int failed, int pruned) {
    }
}

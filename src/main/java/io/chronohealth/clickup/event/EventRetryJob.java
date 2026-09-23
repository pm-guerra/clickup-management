package io.chronohealth.clickup.event;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Works through the event inbox: new events, failed ones whose backoff elapsed and stuck ones whose lock expired.
 * The webhook only stores events, so this is where all ClickUp work happens.
 * <p>
 * Events are processed one at a time, at most one every {@code pacing}, so bursts (e.g. a bulk tag edit) stay under
 * ClickUp's rate limit instead of hammering it. A run stops after {@code run-budget} so it finishes before the next
 * trigger; whatever is left is picked up by the next run. Only one run executes at a time.
 * <p>
 * Triggered by Cloud Scheduler via {@code POST /admin/events/process-due} every minute when deployed (Cloud Run
 * throttles CPU outside requests, so in-process timers are unreliable there), and by {@link EventRetryScheduler}
 * locally.
 */
@Service
public class EventRetryJob {

    private static final Logger log = LoggerFactory.getLogger(EventRetryJob.class);

    private final EventRepository repository;
    private final EventProcessor processor;
    private final EventProperties properties;
    private final Clock clock;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public EventRetryJob(EventRepository repository, EventProcessor processor, EventProperties properties, Clock clock) {
        this.repository = repository;
        this.processor = processor;
        this.properties = properties;
        this.clock = clock;
    }

    public RunResult run() {
        if (!running.compareAndSet(false, true)) {
            log.info("Event run skipped: previous run still in progress");
            return new RunResult(0, 0, 0, 0, true);
        }
        try {
            return drain();
        } finally {
            running.set(false);
        }
    }

    private RunResult drain() {
        Instant deadline = clock.instant().plus(properties.runBudget());
        Instant nextStart = clock.instant();
        int attempted = 0;
        int succeeded = 0;
        int failed = 0;

        batches:
        while (clock.instant().isBefore(deadline)) {
            List<Long> due = repository.findDueIds(properties.batchSize());
            if (due.isEmpty()) {
                break;
            }
            for (long id : due) {
                if (!waitUntil(nextStart, deadline)) {
                    break batches;
                }
                nextStart = clock.instant().plus(properties.pacing());
                EventStatus status = processor.process(id).orElse(null);
                if (status == null) {
                    continue;
                }
                attempted++;
                if (status == EventStatus.SUCCEEDED) {
                    succeeded++;
                } else {
                    failed++;
                }
            }
        }

        int pruned = repository.deleteSucceededBefore(OffsetDateTime.now(clock).minus(properties.retention()));
        if (attempted > 0 || pruned > 0) {
            log.info("Event run: {} processed, {} succeeded, {} failed, {} pruned", attempted, succeeded, failed, pruned);
        }
        return new RunResult(attempted, succeeded, failed, pruned, false);
    }

    /**
     * Sleeps until {@code start} (pacing). Returns false if that would pass the run's deadline.
     */
    private boolean waitUntil(Instant start, Instant deadline) {
        if (!start.isBefore(deadline)) {
            return false;
        }
        Duration wait = Duration.between(clock.instant(), start);
        if (wait.isPositive()) {
            try {
                Thread.sleep(wait);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return true;
    }

    /**
     * @param due     events processed in this run (field name kept for API compatibility)
     * @param skipped true if another run was already in progress
     */
    public record RunResult(int due, int succeeded, int failed, int pruned, boolean skipped) {
    }
}

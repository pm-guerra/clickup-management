package io.chronohealth.clickup.event;

import io.chronohealth.clickup.webhook.ClickUpEvent;
import io.chronohealth.clickup.webhook.EventDispatcher;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/**
 * Processes one stored event: claim -> dispatch to workflows -> record the outcome.
 * Never throws; failures are recorded on the event and picked up again by {@link EventRetryJob}.
 */
@Service
public class EventProcessor {

    private static final Logger log = LoggerFactory.getLogger(EventProcessor.class);

    private final EventRepository repository;
    private final EventDispatcher dispatcher;
    private final RetryPolicy retryPolicy;
    private final EventProperties properties;
    private final JsonMapper jsonMapper;
    private final Clock clock;

    public EventProcessor(EventRepository repository, EventDispatcher dispatcher, RetryPolicy retryPolicy,
                          EventProperties properties, JsonMapper jsonMapper, Clock clock) {
        this.repository = repository;
        this.dispatcher = dispatcher;
        this.retryPolicy = retryPolicy;
        this.properties = properties;
        this.jsonMapper = jsonMapper;
        this.clock = clock;
    }

    /**
     * @return the resulting status, or empty if the event wasn't due or another worker claimed it
     */
    public Optional<EventStatus> process(long eventId) {
        Optional<StoredEvent> claimed = repository.claim(eventId, now().plus(properties.lockDuration()));
        if (claimed.isEmpty()) {
            return Optional.empty();
        }
        StoredEvent event = claimed.get();
        try {
            dispatcher.dispatch(jsonMapper.readValue(event.payload(), ClickUpEvent.class));
            repository.markSucceeded(eventId);
            log.info("Event {} ({}, task {}) succeeded on attempt {}", eventId, event.eventType(), event.taskId(), event.attempts());
            return Optional.of(EventStatus.SUCCEEDED);
        } catch (RuntimeException e) {
            return Optional.of(recordFailure(event, e));
        }
    }

    private EventStatus recordFailure(StoredEvent event, RuntimeException error) {
        String description = RetryPolicy.describe(error);
        if (retryPolicy.shouldRetry(error, event.attempts())) {
            OffsetDateTime next = now().plus(retryPolicy.backoffAfter(event.attempts()));
            repository.markFailed(event.id(), next, description);
            log.warn("Event {} ({}, task {}) failed on attempt {}/{}; retrying at {}: {}", event.id(), event.eventType(),
                    event.taskId(), event.attempts(), properties.maxAttempts(), next, description);
            return EventStatus.FAILED;
        }
        repository.markDead(event.id(), description);
        log.error("Event {} ({}, task {}) is DEAD after {} attempt(s): {}", event.id(), event.eventType(),
                event.taskId(), event.attempts(), description);
        return EventStatus.DEAD;
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC);
    }
}

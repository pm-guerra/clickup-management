package io.chronohealth.clickup.event;

import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin endpoints for the event inbox. Protected by {@code X-Admin-Key}.
 */
@RestController
@RequestMapping("/admin/events")
public class EventAdminController {

    private final EventRepository repository;
    private final EventRetryJob retryJob;
    private final EventProcessor processor;

    public EventAdminController(EventRepository repository, EventRetryJob retryJob, EventProcessor processor) {
        this.repository = repository;
        this.retryJob = retryJob;
        this.processor = processor;
    }

    @GetMapping
    public List<EventView> list(@RequestParam(defaultValue = "FAILED") EventStatus status,
                                @RequestParam(defaultValue = "50") int limit) {
        return repository.findByStatus(status, Math.min(limit, 500)).stream().map(EventView::of).toList();
    }

    @GetMapping("/{id}")
    public ResponseEntity<EventView> get(@PathVariable long id) {
        return ResponseEntity.of(repository.findById(id).map(EventView::of));
    }

    /**
     * Requeues a FAILED or DEAD event with a fresh attempt budget and processes it now.
     */
    @PostMapping("/{id}/retry")
    public ResponseEntity<EventView> retry(@PathVariable long id) {
        if (!repository.requeue(id)) {
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        }
        processor.process(id);
        return ResponseEntity.of(repository.findById(id).map(EventView::of));
    }

    /**
     * Called by Cloud Scheduler every minute.
     */
    @PostMapping("/process-due")
    public EventRetryJob.RunResult processDue() {
        return retryJob.run();
    }

    /**
     * Event without its payload.
     */
    public record EventView(long id, String eventType, String taskId, EventStatus status, int attempts,
                            OffsetDateTime nextAttemptAt, String lastError, OffsetDateTime receivedAt,
                            OffsetDateTime updatedAt) {

        static EventView of(StoredEvent e) {
            return new EventView(e.id(), e.eventType(), e.taskId(), e.status(), e.attempts(), e.nextAttemptAt(),
                    e.lastError(), e.receivedAt(), e.updatedAt());
        }
    }
}

package io.chronohealth.clickup.event;

import io.chronohealth.clickup.webhook.ClickUpEvent;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Admin endpoints for the event inbox. Protected by {@code X-Admin-Key}.
 */
@RestController
@RequestMapping("/admin/events")
public class EventAdminController {

    private final EventRepository repository;
    private final EventRetryJob retryJob;
    private final EventProcessor processor;

    private final JsonMapper jsonMapper;

    public EventAdminController(EventRepository repository, EventRetryJob retryJob, EventProcessor processor,
                                JsonMapper jsonMapper) {
        this.repository = repository;
        this.retryJob = retryJob;
        this.processor = processor;
        this.jsonMapper = jsonMapper;
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
     * A task's most recent events with what changed (who, field, from, to), newest first. Only the values kept in
     * stored events are shown (status and tag names), never task content.
     */
    @GetMapping("/task/{taskId}")
    public List<TaskChangeView> taskHistory(@PathVariable String taskId,
                                            @RequestParam(defaultValue = "20") int limit) {
        return repository.findByTask(taskId, Math.min(limit, 200)).stream().map(this::changes).toList();
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

    private TaskChangeView changes(StoredEvent stored) {
        ClickUpEvent event = jsonMapper.readValue(stored.payload(), ClickUpEvent.class);
        List<Change> changes = event.historyItems().stream()
                .map(i -> new Change(i.field(), i.user() == null ? null : i.user().id(), name(i.before()),
                        name(i.after())))
                .toList();
        return new TaskChangeView(stored.id(), stored.eventType(), stored.status(), stored.receivedAt(), changes);
    }

    /**
     * Status ({"status": ...}) or tags ([{"name": ...}]) as plain names; null when ClickUp sent nothing.
     */
    private static String name(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        if (node.isArray()) {
            List<String> names = new java.util.ArrayList<>();
            node.forEach(n -> names.add(n.isString() ? n.asString() : n.path("name").asString("?")));
            return String.join(", ", names);
        }
        return node.isString() ? node.asString() : node.path("status").asString(node.path("name").asString("?"));
    }

    public record TaskChangeView(long eventId, String eventType, EventStatus status, OffsetDateTime receivedAt,
                                 List<Change> changes) {
    }

    public record Change(String field, Long userId, String from, String to) {
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

package io.chronohealth.clickup.webhook;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Routes normalized events to the business workflows that subscribed to their type. {@link EventGuard}s run first:
 * if one blocks the event (e.g. it undid a forbidden change), no workflow reacts to it.
 */
@Component
public class EventDispatcher {

    private static final Logger log = LoggerFactory.getLogger(EventDispatcher.class);

    private final List<EventHandler> handlers;
    private final List<EventGuard> guards;

    public EventDispatcher(List<EventHandler> handlers, List<EventGuard> guards) {
        this.handlers = handlers;
        this.guards = guards;
    }

    public void dispatch(ClickUpEvent event) {
        for (EventGuard guard : guards) {
            if (guard.blocks(event)) {
                log.info("{} for task {} was blocked by {}; workflows skipped", event.type(), event.taskId(),
                        guard.getClass().getSimpleName());
                return;
            }
        }
        List<EventHandler> matching = handlers.stream()
                .filter(h -> h.eventTypes().contains(event.type()))
                .toList();
        if (matching.isEmpty()) {
            log.debug("No handler for event {} (task {})", event.type(), event.taskId());
            return;
        }
        for (EventHandler handler : matching) {
            log.info("Dispatching {} for task {} to {}", event.type(), event.taskId(), handler.getClass().getSimpleName());
            handler.handle(event);
        }
    }
}

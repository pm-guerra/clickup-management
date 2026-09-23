package io.chronohealth.clickup.webhook;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Routes normalized events to the business workflows that subscribed to their type.
 */
@Component
public class EventDispatcher {

    private static final Logger log = LoggerFactory.getLogger(EventDispatcher.class);

    private final List<EventHandler> handlers;

    public EventDispatcher(List<EventHandler> handlers) {
        this.handlers = handlers;
    }

    public void dispatch(ClickUpEvent event) {
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

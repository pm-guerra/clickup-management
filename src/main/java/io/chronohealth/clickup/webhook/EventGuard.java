package io.chronohealth.clickup.webhook;

/**
 * Runs before the workflows. Returning true stops the event: no workflow reacts to it.
 */
public interface EventGuard {

    boolean blocks(ClickUpEvent event);
}

package io.chronohealth.clickup.event;

public enum EventStatus {
    /**
     * Stored, not processed yet.
     */
    PENDING,
    /**
     * Claimed by a worker; if the lock expires (crash, timeout) it becomes due again.
     */
    PROCESSING,
    SUCCEEDED,
    /**
     * Last attempt failed; will be retried at {@code next_attempt_at}.
     */
    FAILED,
    /**
     * Gave up (non-retryable error or max attempts reached). Can be retried manually.
     */
    DEAD
}

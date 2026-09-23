package io.chronohealth.clickup.client;

import java.time.Instant;

public class ClickUpRateLimitedException extends ClickUpApiException {

    private final Instant resetAt;

    public ClickUpRateLimitedException(Instant resetAt) {
        super(429, "RATE_LIMITED");
        this.resetAt = resetAt;
    }

    /**
     * When the rate limit window resets, or null if ClickUp didn't say.
     */
    public Instant resetAt() {
        return resetAt;
    }
}

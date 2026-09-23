package io.chronohealth.clickup.event;

import io.chronohealth.clickup.client.ClickUpApiException;
import io.chronohealth.clickup.client.ClickUpClientFactory;
import java.time.Duration;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;

@Component
public class RetryPolicy {

    private final EventProperties properties;

    public RetryPolicy(EventProperties properties) {
        this.properties = properties;
    }

    /**
     * Exponential backoff: initialBackoff * 2^(attempt-1), capped at maxBackoff.
     *
     * @param attempt the attempt that just failed, starting at 1
     */
    public Duration backoffAfter(int attempt) {
        Duration delay = properties.initialBackoff();
        for (int i = 1; i < attempt && delay.compareTo(properties.maxBackoff()) < 0; i++) {
            delay = delay.multipliedBy(2);
        }
        return delay.compareTo(properties.maxBackoff()) > 0 ? properties.maxBackoff() : delay;
    }

    public boolean shouldRetry(Throwable error, int attempt) {
        return attempt < properties.maxAttempts() && isRetryable(error);
    }

    /**
     * Retrying won't fix ClickUp rejecting the request (4xx other than 408/429) or a payload we can't parse.
     * A missing OAuth token is retryable: it recovers once someone re-authorizes.
     */
    public boolean isRetryable(Throwable error) {
        return switch (error) {
            case ClickUpApiException e -> e.statusCode() >= 500 || e.statusCode() == 408 || e.statusCode() == 429;
            case ClickUpClientFactory.WorkspaceNotAuthorizedException _ -> true;
            case JacksonException _ -> false;
            default -> true;
        };
    }

    /**
     * Error summary safe to store and log: exception type plus, for ClickUp errors, status and ECODE.
     * Other exception messages are dropped because they can echo task data.
     */
    public static String describe(Throwable error) {
        String summary = error instanceof ClickUpApiException || error instanceof ClickUpClientFactory.WorkspaceNotAuthorizedException
                ? error.getClass().getSimpleName() + ": " + error.getMessage()
                : error.getClass().getName();
        return summary.length() > 1000 ? summary.substring(0, 1000) : summary;
    }
}

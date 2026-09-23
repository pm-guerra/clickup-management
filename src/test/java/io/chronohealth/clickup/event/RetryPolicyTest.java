package io.chronohealth.clickup.event;

import static org.assertj.core.api.Assertions.assertThat;

import io.chronohealth.clickup.client.ClickUpApiException;
import io.chronohealth.clickup.client.ClickUpClientFactory;
import io.chronohealth.clickup.client.ClickUpRateLimitedException;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class RetryPolicyTest {

    private final RetryPolicy policy = new RetryPolicy(new EventProperties(4, Duration.ofMinutes(1), Duration.ofMinutes(5),
            Duration.ofMinutes(5), 10, Duration.ZERO, Duration.ofSeconds(10), Duration.ofDays(30),
            new EventProperties.Scheduler(false)));

    @Test
    void backsOffExponentiallyUpToTheCap() {
        assertThat(policy.backoffAfter(1)).isEqualTo(Duration.ofMinutes(1));
        assertThat(policy.backoffAfter(2)).isEqualTo(Duration.ofMinutes(2));
        assertThat(policy.backoffAfter(3)).isEqualTo(Duration.ofMinutes(4));
        assertThat(policy.backoffAfter(4)).isEqualTo(Duration.ofMinutes(5));
        assertThat(policy.backoffAfter(50)).isEqualTo(Duration.ofMinutes(5));
    }

    @Test
    void retriesTransientErrorsOnly() {
        assertThat(policy.isRetryable(new ClickUpApiException(503, null))).isTrue();
        assertThat(policy.isRetryable(new ClickUpRateLimitedException(null))).isTrue();
        assertThat(policy.isRetryable(new ClickUpClientFactory.WorkspaceNotAuthorizedException("1"))).isTrue();
        assertThat(policy.isRetryable(new IllegalStateException())).isTrue();
        assertThat(policy.isRetryable(new ClickUpApiException(400, "X"))).isFalse();
        assertThat(policy.isRetryable(new ClickUpApiException(404, "X"))).isFalse();
    }

    @Test
    void stopsAtMaxAttempts() {
        assertThat(policy.shouldRetry(new IllegalStateException(), 3)).isTrue();
        assertThat(policy.shouldRetry(new IllegalStateException(), 4)).isFalse();
    }

    @Test
    void describeDropsArbitraryExceptionMessages() {
        assertThat(RetryPolicy.describe(new IllegalStateException("patient John Doe")))
                .isEqualTo("java.lang.IllegalStateException");
        assertThat(RetryPolicy.describe(new ClickUpApiException(500, "E1"))).contains("status=500", "ecode=E1");
    }
}

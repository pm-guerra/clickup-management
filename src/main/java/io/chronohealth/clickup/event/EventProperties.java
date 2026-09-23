package io.chronohealth.clickup.event;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * @param maxAttempts    attempts before an event is marked DEAD
 * @param initialBackoff delay after the first failure; doubles on each further failure
 * @param maxBackoff     cap for the delay between attempts
 * @param lockDuration   how long a PROCESSING claim is honoured before the event is considered stuck
 * @param batchSize      events processed per retry run
 * @param retention      how long SUCCEEDED events are kept
 * @param scheduler      in-process retry schedule; disabled on Cloud Run, where Cloud Scheduler calls the endpoint
 */
@Validated
@ConfigurationProperties("app.events")
public record EventProperties(
        @Min(1) int maxAttempts,
        @NotNull Duration initialBackoff,
        @NotNull Duration maxBackoff,
        @NotNull Duration lockDuration,
        @Min(1) int batchSize,
        @NotNull Duration retention,
        @Valid @NotNull Scheduler scheduler
) {

    public record Scheduler(boolean enabled) {
    }
}

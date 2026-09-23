package io.chronohealth.clickup.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class EventRetryJobTest {

    private final EventRepository repository = mock(EventRepository.class);
    private final EventProcessor processor = mock(EventProcessor.class);

    @Test
    void pacesEventsAtTheConfiguredInterval() {
        when(repository.findDueIds(anyInt())).thenReturn(List.of(1L, 2L, 3L), List.of());
        when(processor.process(anyLong())).thenReturn(Optional.of(EventStatus.SUCCEEDED));

        long start = System.nanoTime();
        EventRetryJob.RunResult result = job(Duration.ofMillis(100), Duration.ofSeconds(5)).run();
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(result.succeeded()).isEqualTo(3);
        // 3 events -> 2 waits between them.
        assertThat(elapsedMs).isGreaterThanOrEqualTo(200);
    }

    @Test
    void stopsAtTheRunBudgetAndLeavesTheRestForTheNextRun() {
        when(repository.findDueIds(anyInt())).thenReturn(List.of(1L, 2L, 3L, 4L, 5L));
        when(processor.process(anyLong())).thenReturn(Optional.of(EventStatus.SUCCEEDED));

        EventRetryJob.RunResult result = job(Duration.ofMillis(200), Duration.ofMillis(500)).run();

        // Starts at 0, 200 and 400 ms; the 4th would start at 600 ms, past the budget.
        assertThat(result.succeeded()).isEqualTo(3);
    }

    private EventRetryJob job(Duration pacing, Duration budget) {
        EventProperties properties = new EventProperties(8, Duration.ofMinutes(1), Duration.ofHours(6),
                Duration.ofMinutes(5), 20, pacing, budget, Duration.ofDays(30), new EventProperties.Scheduler(false));
        return new EventRetryJob(repository, processor, properties, Clock.systemUTC());
    }
}

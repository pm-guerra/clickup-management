package io.chronohealth.clickup.event;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * In-process retry schedule for local runs. Disabled on Cloud Run (see {@link EventRetryJob}).
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(name = "app.events.scheduler.enabled", havingValue = "true")
public class EventRetryScheduler {

    private final EventRetryJob job;

    public EventRetryScheduler(EventRetryJob job) {
        this.job = job;
    }

    @Scheduled(fixedDelayString = "${app.events.scheduler.interval:60s}", initialDelayString = "30s")
    public void run() {
        job.run();
    }
}

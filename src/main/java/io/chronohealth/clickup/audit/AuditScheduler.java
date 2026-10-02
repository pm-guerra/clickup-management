package io.chronohealth.clickup.audit;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * In-process hourly trigger for local runs. Disabled on Cloud Run, where Cloud Scheduler calls
 * {@code POST /admin/audit/run}.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(name = "app.audit.scheduler.enabled", havingValue = "true")
public class AuditScheduler {

    private final AuditJob job;

    public AuditScheduler(AuditJob job) {
        this.job = job;
    }

    @Scheduled(cron = "0 0 * * * *")
    public void run() {
        job.run();
    }
}

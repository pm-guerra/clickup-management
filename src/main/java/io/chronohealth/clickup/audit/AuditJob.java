package io.chronohealth.clickup.audit;

import io.chronohealth.clickup.audit.AuditRepository.Violation;
import io.chronohealth.clickup.client.ClickUpClient;
import io.chronohealth.clickup.client.ClickUpClient.TaskPage;
import io.chronohealth.clickup.client.ClickUpClientFactory;
import io.chronohealth.clickup.client.dto.Task;
import io.chronohealth.clickup.config.ClickUpProperties;
import io.chronohealth.clickup.workflow.TaskTypeResolver;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Hourly audit: fetches the tasks updated since the previous run, checks each against every enabled
 * {@link AuditRule}, records findings, and sends a direct message listing the new ones (nothing is sent when there's
 * nothing new). A finding is reported once; it's resolved when a later run sees the task complying.
 * <p>
 * The window only advances after the tasks were checked; a failed run is retried on the next trigger. Sending the
 * message is best-effort: unsent findings stay unnotified and go out with the next run.
 */
@Service
public class AuditJob {

    private static final Logger log = LoggerFactory.getLogger(AuditJob.class);

    private final ClickUpClientFactory clientFactory;
    private final TaskTypeResolver taskTypes;
    private final AuditRepository repository;
    private final AuditNotifier notifier;
    private final List<AuditRule> rules;
    private final AuditProperties properties;
    private final ClickUpProperties clickUpProperties;
    private final Clock clock;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public AuditJob(ClickUpClientFactory clientFactory, TaskTypeResolver taskTypes, AuditRepository repository,
                    AuditNotifier notifier, List<AuditRule> rules, AuditProperties properties,
                    ClickUpProperties clickUpProperties, Clock clock) {
        this.clientFactory = clientFactory;
        this.taskTypes = taskTypes;
        this.repository = repository;
        this.notifier = notifier;
        this.rules = rules;
        this.properties = properties;
        this.clickUpProperties = clickUpProperties;
        this.clock = clock;
    }

    public RunResult run() {
        if (!properties.enabled()) {
            return RunResult.notRun();
        }
        if (!running.compareAndSet(false, true)) {
            log.info("Audit skipped: previous run still in progress");
            return RunResult.notRun();
        }
        try {
            return audit();
        } finally {
            running.set(false);
        }
    }

    private RunResult audit() {
        OffsetDateTime runStart = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC);
        OffsetDateTime from = repository.lastCheckedUntil()
                .map(last -> last.minus(properties.overlap()))
                .orElse(runStart.minus(properties.initialLookback()));

        String workspaceId = clickUpProperties.workspaceId();
        ClickUpClient client = clientFactory.forWorkspace(workspaceId);
        AuditContext context = new AuditContext(client, workspaceId, taskTypes, properties.apiPacing());
        List<AuditRule> active = rules.stream().filter(AuditRule::enabled).toList();

        Map<String, String> taskNames = new HashMap<>();
        int checked = 0;
        for (int page = 0; page < properties.maxPages(); page++) {
            TaskPage result = client.getTasksUpdatedSince(workspaceId, from.toInstant(), properties.listIdsOrEmpty(), page);
            result.tasksOrEmpty().forEach(context::remember);
            for (Task task : result.tasksOrEmpty()) {
                taskNames.put(task.id(), task.name() == null ? task.id() : task.name());
                for (AuditRule rule : active) {
                    Optional<String> finding = rule.check(task, context);
                    repository.reconcile(rule.id(), task.id(), finding);
                }
                checked++;
            }
            if (result.isLastPage()) {
                break;
            }
        }
        repository.saveLastCheckedUntil(runStart);

        List<Violation> fresh = repository.findOpenUnnotified();
        if (!fresh.isEmpty()) {
            try {
                notifier.send(client, workspaceId, fresh, taskNames);
                repository.markNotified(fresh.stream().map(Violation::id).toList());
            } catch (RuntimeException e) {
                log.warn("Audit report not sent ({}); will retry with the next run", e.getClass().getSimpleName());
            }
        }
        log.info("Audit: {} task(s) checked since {}, {} new issue(s), {} open in total", checked, from, fresh.size(),
                repository.findOpen().size());
        return new RunResult(false, checked, fresh.size());
    }

    public record RunResult(boolean skipped, int tasksChecked, int newIssues) {

        static RunResult notRun() {
            return new RunResult(true, 0, 0);
        }
    }
}

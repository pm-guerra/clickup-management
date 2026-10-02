package io.chronohealth.clickup.audit;

import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Audit endpoints. Protected by {@code X-Admin-Key}; {@code /run} is called hourly by Cloud Scheduler.
 */
@RestController
@RequestMapping("/admin/audit")
public class AuditController {

    private final AuditJob job;
    private final AuditRepository repository;

    public AuditController(AuditJob job, AuditRepository repository) {
        this.job = job;
        this.repository = repository;
    }

    @PostMapping("/run")
    public AuditJob.RunResult run() {
        return job.run();
    }

    /**
     * Clears all findings and rewinds the window (e.g. after changing a rule's definition). The next run re-checks the
     * initial lookback and reports what's still wrong.
     */
    @PostMapping("/reset")
    public void reset() {
        repository.reset();
    }

    @GetMapping("/violations")
    public List<ViolationView> openViolations() {
        return repository.findOpen().stream().map(ViolationView::of).toList();
    }

    public record ViolationView(String ruleId, String taskId, String details, OffsetDateTime firstSeenAt,
                                OffsetDateTime lastSeenAt, boolean notified) {

        static ViolationView of(AuditRepository.Violation v) {
            return new ViolationView(v.ruleId(), v.taskId(), v.details(), v.firstSeenAt(), v.lastSeenAt(),
                    v.notifiedAt() != null);
        }
    }
}

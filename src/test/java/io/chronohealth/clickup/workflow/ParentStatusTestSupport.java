package io.chronohealth.clickup.workflow;

import io.chronohealth.clickup.audit.AuditProperties;
import io.chronohealth.clickup.config.ClickUpProperties;
import java.time.Duration;
import java.util.List;

/**
 * Properties for tests of the parent status rules: Pedro (100796657) is unrestricted, Martim (106791322) a tester.
 */
public final class ParentStatusTestSupport {

    private ParentStatusTestSupport() {
    }

    public static ClickUpProperties clickUpProperties() {
        return new ClickUpProperties("id", "secret", "https://x/cb", "ws", "https://api", "https://auth",
                Duration.ofSeconds(1), Duration.ofSeconds(1), new ClickUpProperties.RateLimit(0, Duration.ofSeconds(1)),
                new ClickUpProperties.Workflows(new ClickUpProperties.CopyParentFields(false, "{}", ""),
                        new ClickUpProperties.TagSubtasks(false, List.of()),
                        new ClickUpProperties.ParentStatus(true, List.of("Story", "Bug", "Change", "Feature", "Task"),
                                List.of("Story", "Bug", "Change", "Feature"), "mobile"),
                        new ClickUpProperties.CreateDefaults(false, List.of()),
                        new ClickUpProperties.RejectionReopen(false, List.of(), List.of(), List.of(), List.of(),
                                "waiting info"),
                        new ClickUpProperties.PrioritySync(false, List.of(), List.of(), List.of())));
    }

    public static AuditProperties auditProperties() {
        return new AuditProperties(true, Duration.ofDays(7), Duration.ofMinutes(5), Duration.ZERO, 5, List.of(),
                "", "", "https://docs.example/rules",
                List.of(new AuditProperties.Person("100796657", "Pedro", "admin", null, null, true),
                        new AuditProperties.Person("106791322", "Martim", "tester", null, null, null)),
                List.of("Story", "Bug", "Change", "Feature", "Epic"), List.of("backend", "web", "mobile"),
                new AuditProperties.Scheduler(false),
                new AuditProperties.Rules(
                        new AuditProperties.StreamTaskStatus(true, List.of("Story", "Bug", "Change", "Feature"),
                                List.of("Task"), List.of("to do", "in progress", "review", "complete")),
                        new AuditProperties.Toggle(true), new AuditProperties.Toggle(true),
                        new AuditProperties.Toggle(true), new AuditProperties.Toggle(true)));
    }
}

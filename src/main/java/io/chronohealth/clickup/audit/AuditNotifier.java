package io.chronohealth.clickup.audit;

import io.chronohealth.clickup.audit.AuditRepository.Violation;
import io.chronohealth.clickup.client.ClickUpClient;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Posts new findings to the ClickUp Chat channel {@code app.audit.notify-channel-id}, or as a direct message to
 * {@code app.audit.notify-user-id} when no channel is configured.
 * The message carries task names and links, so it's sent through ClickUp only and never logged.
 */
@Component
public class AuditNotifier {

    static final int MAX_ITEMS = 50;
    private static final String TASK_URL = "https://app.clickup.com/t/";

    private static final Logger log = LoggerFactory.getLogger(AuditNotifier.class);

    private final AuditProperties properties;
    private volatile String channelId;

    public AuditNotifier(AuditProperties properties) {
        this.properties = properties;
    }

    /**
     * @param taskNames names of tasks seen in this run, used as link text when available
     */
    public void send(ClickUpClient client, String workspaceId, List<Violation> violations, Map<String, String> taskNames) {
        if (channelId == null) {
            channelId = resolveChannel(client, workspaceId);
        }
        if (channelId == null) {
            log.info("Audit found {} new issue(s); no notify channel or user configured", violations.size());
            return;
        }
        String message = format(violations, taskNames);
        if (properties.hasRulesDoc()) {
            message += "\n\n📖 Rules: [How we work](" + properties.rulesDocUrl() + ")";
        }
        client.sendChatMessage(workspaceId, channelId, message);
        log.info("Sent audit report with {} new issue(s)", violations.size());
    }

    private String resolveChannel(ClickUpClient client, String workspaceId) {
        if (!isBlank(properties.notifyChannelId())) {
            return properties.notifyChannelId();
        }
        if (!isBlank(properties.notifyUserId())) {
            return client.getOrCreateDirectMessage(workspaceId, properties.notifyUserId());
        }
        return null;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    static String format(List<Violation> violations, Map<String, String> taskNames) {
        StringBuilder message = new StringBuilder("**ClickUp audit: ")
                .append(violations.size()).append(" new issue").append(violations.size() == 1 ? "" : "s")
                .append("**\n");
        violations.stream().limit(MAX_ITEMS).forEach(v -> message
                .append("\n- [").append(escape(taskNames.getOrDefault(v.taskId(), v.taskId())))
                .append("](").append(TASK_URL).append(v.taskId()).append("): ")
                .append(v.details()));
        if (violations.size() > MAX_ITEMS) {
            message.append("\n\n…and ").append(violations.size() - MAX_ITEMS).append(" more.");
        }
        return message.toString();
    }

    private static String escape(String text) {
        return text.replace("[", "\\[").replace("]", "\\]");
    }
}

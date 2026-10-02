package io.chronohealth.clickup.audit;

import io.chronohealth.clickup.audit.People.Role;
import io.chronohealth.clickup.webhook.ClickUpEvent;
import io.chronohealth.clickup.webhook.ClickUpWebhookPayload.HistoryItem;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Only admins may add or remove the stream tags (backend / web / mobile), on any task.
 */
@Component
public class StreamTagChangeRule implements AuditEventRule {

    private static final String ADDED = "tag";
    private static final String REMOVED = "tag_removed";

    private final People people;
    private final TaskKinds kinds;
    private final AuditProperties.Toggle config;

    public StreamTagChangeRule(People people, TaskKinds kinds, AuditProperties properties) {
        this.people = people;
        this.kinds = kinds;
        this.config = properties.rules().streamTagChange();
    }

    @Override
    public String id() {
        return "stream-tag-change";
    }

    @Override
    public boolean enabled() {
        return config.enabled();
    }

    @Override
    public List<String> eventTypes() {
        return List.of("taskTagUpdated");
    }

    @Override
    public List<String> check(ClickUpEvent event, AuditContext context) {
        List<String> findings = new ArrayList<>();
        for (HistoryItem item : event.historyItems()) {
            boolean added = ADDED.equals(item.field());
            if (!added && !REMOVED.equals(item.field())) {
                continue;
            }
            Long userId = item.user() == null ? null : item.user().id();
            if (people.role(userId).map(r -> r == Role.ADMIN).orElse(false)) {
                continue;
            }
            // The changed tags are in "after"; for removals fall back to "before" if ClickUp sent them there.
            List<String> tags = streamTags(item.after());
            if (!added && tags.isEmpty()) {
                tags = streamTags(item.before());
            }
            for (String tag : tags) {
                findings.add(people.describe(userId) + (added ? " added" : " removed") + " the '" + tag
                        + "' tag. Only admins may add or remove stream tags.");
            }
        }
        return findings;
    }

    private List<String> streamTags(JsonNode node) {
        List<String> tags = new ArrayList<>();
        if (node == null || node.isNull() || node.isMissingNode()) {
            return tags;
        }
        Iterable<JsonNode> values = node.isArray() ? node : List.of(node);
        for (JsonNode tag : values) {
            String name = tag.isString() ? tag.asString() : tag.path("name").asString(null);
            if (kinds.isStreamTag(name)) {
                tags.add(name.trim().toLowerCase(java.util.Locale.ROOT));
            }
        }
        return tags;
    }
}

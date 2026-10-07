package io.chronohealth.clickup.audit;

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

    static final String ADDED = "tag";
    static final String REMOVED = "tag_removed";

    private final People people;
    private final TaskKinds kinds;
    private final Permissions permissions;
    private final AuditProperties.Toggle config;

    public StreamTagChangeRule(People people, TaskKinds kinds, Permissions permissions, AuditProperties properties) {
        this.people = people;
        this.kinds = kinds;
        this.permissions = permissions;
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
            if (permissions.canChangeStreamTags(userId)) {
                continue;
            }
            // The changed tags are in "after"; for removals fall back to "before" if ClickUp sent them there.
            List<String> tags = changedStreamTags(item);
            for (String tag : tags) {
                findings.add(people.describe(userId) + (added ? " added" : " removed") + " the '" + tag
                        + "' tag. " + permissions.whoMayTag()
                        + (people.isEnforced(userId) ? " Undone automatically." : ""));
            }
        }
        return findings;
    }

    /**
     * Stream tags added or removed by this history item. The changed tags are in "after"; for removals fall back to
     * "before" if ClickUp sent them there.
     */
    List<String> changedStreamTags(HistoryItem item) {
        List<String> tags = streamTags(item.after());
        if (REMOVED.equals(item.field()) && tags.isEmpty()) {
            tags = streamTags(item.before());
        }
        return tags;
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

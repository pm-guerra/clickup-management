package io.chronohealth.clickup.workflow;

import io.chronohealth.clickup.client.ClickUpClient;
import io.chronohealth.clickup.client.ClickUpClientFactory;
import io.chronohealth.clickup.client.dto.CustomField;
import io.chronohealth.clickup.client.dto.Task;
import io.chronohealth.clickup.config.ClickUpProperties;
import io.chronohealth.clickup.webhook.ClickUpEvent;
import io.chronohealth.clickup.webhook.EventHandler;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Epic -> Bug -> Task: when a subtask is created under a Bug (e.g. by a ClickUp Automation), copy the configured
 * custom fields from the Bug to the new subtask.
 * <p>
 * Loop protection: this only reacts to {@code taskCreated}. The custom-field writes it performs produce
 * {@code taskUpdated} events, which it never subscribes to, so it can't re-trigger itself. On top of that it
 * only writes values that differ from what the child already has, so a replayed event is a no-op.
 */
@Component
public class CopyParentFieldsWorkflow implements EventHandler {

    private static final Logger log = LoggerFactory.getLogger(CopyParentFieldsWorkflow.class);

    private final ClickUpClientFactory clientFactory;
    private final CustomFieldValueMapper valueMapper;
    private final ClickUpProperties.CopyParentFields config;
    /**
     * child field id -> parent field id
     */
    private final Map<String, String> fieldMappings;

    public CopyParentFieldsWorkflow(ClickUpClientFactory clientFactory, CustomFieldValueMapper valueMapper,
                                    ClickUpProperties properties, JsonMapper jsonMapper) {
        this.clientFactory = clientFactory;
        this.valueMapper = valueMapper;
        this.config = properties.workflows().copyParentFields();
        this.fieldMappings = parseMappings(jsonMapper, config.fieldMappings());
    }

    @Override
    public Set<String> eventTypes() {
        return config.enabled() && !fieldMappings.isEmpty() ? Set.of("taskCreated") : Set.of();
    }

    @Override
    public void handle(ClickUpEvent event) {
        ClickUpClient client = clientFactory.forWorkspace(event.workspaceId());

        Task child = client.getTask(event.taskId());
        if (child.parent() == null) {
            log.debug("Task {} has no parent; nothing to copy", child.id());
            return;
        }

        Task parent = client.getTask(child.parent());
        if (!isEligibleParent(parent)) {
            log.debug("Parent {} of task {} is not an eligible type; skipping", parent.id(), child.id());
            return;
        }

        Map<String, CustomField> parentFields = byId(parent);
        Map<String, CustomField> childFields = byId(child);
        int copied = 0;

        for (Map.Entry<String, String> mapping : fieldMappings.entrySet()) {
            String childFieldId = mapping.getKey();
            CustomField source = parentFields.get(mapping.getValue());
            if (source == null || !source.hasValue()) {
                continue;
            }
            if (!childFields.containsKey(childFieldId)) {
                log.warn("Field {} is not available on task {}'s list; skipping", childFieldId, child.id());
                continue;
            }
            CustomField target = childFields.get(childFieldId);
            if (target.hasValue() && Objects.equals(target.value(), source.value())) {
                continue;
            }
            Optional<Object> writeValue = valueMapper.toWriteValue(source);
            if (writeValue.isEmpty()) {
                log.warn("Field {} of type {} is not supported for copying; skipping", source.id(), source.type());
                continue;
            }
            client.setCustomField(child.id(), childFieldId, writeValue.get());
            copied++;
        }
        log.info("Copied {} custom field(s) from parent {} to task {}", copied, parent.id(), child.id());
    }

    private boolean isEligibleParent(Task parent) {
        String requiredType = config.parentCustomItemId();
        if (requiredType == null || requiredType.isBlank()) {
            return true;
        }
        return parent.customItemId() != null && requiredType.equals(String.valueOf(parent.customItemId()));
    }

    private static Map<String, CustomField> byId(Task task) {
        return task.customFieldsOrEmpty().stream()
                .collect(Collectors.toMap(CustomField::id, Function.identity(), (a, _) -> a));
    }

    private static Map<String, String> parseMappings(JsonMapper jsonMapper, String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return Map.copyOf(jsonMapper.readValue(json, new TypeReference<Map<String, String>>() {
            }));
        } catch (RuntimeException e) {
            throw new IllegalStateException("CLICKUP_CUSTOM_FIELD_MAPPINGS must be a JSON object of field ids", e);
        }
    }
}

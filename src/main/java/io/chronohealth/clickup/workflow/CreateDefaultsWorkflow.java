package io.chronohealth.clickup.workflow;

import io.chronohealth.clickup.client.ClickUpClient;
import io.chronohealth.clickup.client.ClickUpClientFactory;
import io.chronohealth.clickup.client.dto.CustomField;
import io.chronohealth.clickup.client.dto.Task;
import io.chronohealth.clickup.config.ClickUpProperties;
import io.chronohealth.clickup.config.ClickUpProperties.CreateDefault;
import io.chronohealth.clickup.webhook.ClickUpEvent;
import io.chronohealth.clickup.webhook.EventHandler;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * When a task is created, sets custom fields to default values depending on its type, e.g. maintenance = true for
 * every new Bug or Change.
 * <p>
 * Idempotent (a field that already has the value is left alone) and loop-free: setting a field produces a
 * {@code taskUpdated} event, which no workflow subscribes to.
 */
@Component
public class CreateDefaultsWorkflow implements EventHandler {

    static final String EVENT = "taskCreated";

    private static final Logger log = LoggerFactory.getLogger(CreateDefaultsWorkflow.class);

    private final ClickUpClientFactory clientFactory;
    private final TaskTypeResolver taskTypes;
    private final CustomFieldValueMapper valueMapper;
    private final ClickUpProperties.CreateDefaults config;

    public CreateDefaultsWorkflow(ClickUpClientFactory clientFactory, TaskTypeResolver taskTypes,
                                  CustomFieldValueMapper valueMapper, ClickUpProperties properties) {
        this.clientFactory = clientFactory;
        this.taskTypes = taskTypes;
        this.valueMapper = valueMapper;
        this.config = properties.workflows().createDefaults();
    }

    @Override
    public Set<String> eventTypes() {
        return config.enabled() && !config.rulesOrEmpty().isEmpty() ? Set.of(EVENT) : Set.of();
    }

    @Override
    public void handle(ClickUpEvent event) {
        ClickUpClient client = clientFactory.forWorkspace(event.workspaceId());
        Task task = client.getTask(event.taskId());
        Optional<String> type = taskTypes.typeName(client, event.workspaceId(), task);
        if (type.isEmpty()) {
            return;
        }
        for (CreateDefault rule : config.rulesOrEmpty()) {
            if (matches(type.get(), rule.taskTypesOrEmpty())) {
                apply(client, task, type.get(), rule);
            }
        }
    }

    private void apply(ClickUpClient client, Task task, String type, CreateDefault rule) {
        Optional<CustomField> field = task.customFieldNamed(rule.field());
        if (field.isEmpty()) {
            log.warn("Field '{}' doesn't exist on the list of new {} {}; skipping", rule.field(), type, task.id());
            return;
        }
        Object value = valueMapper.fromConfig(field.get(), rule.value());
        if (field.get().hasValue() && valueMapper.toWriteValue(field.get()).map(v -> Objects.equals(v, value)).orElse(false)) {
            return;
        }
        client.setCustomField(task.id(), field.get().id(), value);
        log.info("Set '{}' on new {} {}", rule.field(), type, task.id());
    }

    private static boolean matches(String type, List<String> allowed) {
        String wanted = type.trim().toLowerCase(Locale.ROOT);
        return allowed.stream().anyMatch(t -> t.trim().toLowerCase(Locale.ROOT).equals(wanted));
    }
}

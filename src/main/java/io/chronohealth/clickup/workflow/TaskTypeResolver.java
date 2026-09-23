package io.chronohealth.clickup.workflow;

import io.chronohealth.clickup.client.ClickUpClient;
import io.chronohealth.clickup.client.dto.CustomTaskType;
import io.chronohealth.clickup.client.dto.Task;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Resolves a task's type name ("Bug", "Change", ...) from its {@code custom_item_id}, so rules can be written with
 * names instead of ids. Types are cached per Workspace and reloaded when an unknown id shows up (a new type).
 */
@Component
public class TaskTypeResolver {

    /**
     * Name of ClickUp's built-in type, used by tasks without a custom type.
     */
    public static final String DEFAULT_TYPE = "Task";

    private static final Logger log = LoggerFactory.getLogger(TaskTypeResolver.class);

    private final Map<String, Map<Long, String>> typesByWorkspace = new ConcurrentHashMap<>();

    public Optional<String> typeName(ClickUpClient client, String workspaceId, Task task) {
        Long id = task.customItemId();
        if (id == null || id == 0) {
            return Optional.of(DEFAULT_TYPE);
        }
        Map<Long, String> types = typesByWorkspace.computeIfAbsent(workspaceId, ws -> load(client, ws));
        if (!types.containsKey(id)) {
            types = load(client, workspaceId);
            typesByWorkspace.put(workspaceId, types);
        }
        return Optional.ofNullable(types.get(id));
    }

    private static Map<Long, String> load(ClickUpClient client, String workspaceId) {
        Map<Long, String> types = client.getCustomTaskTypes(workspaceId).stream()
                .filter(t -> t.id() != null && t.name() != null)
                .collect(Collectors.toMap(CustomTaskType::id, CustomTaskType::name, (a, _) -> a));
        log.info("Loaded custom task types for workspace {}: {}", workspaceId, types.values().stream()
                .filter(Objects::nonNull).sorted().toList());
        return types;
    }
}

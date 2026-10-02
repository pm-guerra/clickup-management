package io.chronohealth.clickup.audit;

import io.chronohealth.clickup.client.dto.Tag;
import io.chronohealth.clickup.client.dto.Task;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Classifies a task for permission checks:
 * <ul>
 *   <li>{@code MAIN}: a Story, Bug, Change or Epic;</li>
 *   <li>{@code STREAM}: a Task-type subtask of a Story/Bug/Change, or a sub-subtask inside one. Its stream comes from
 *       its backend/web/mobile tag, else from its name (e.g. "Mobile | ..." or a legacy "Backend");</li>
 *   <li>{@code OTHER}: anything else (not checked).</li>
 * </ul>
 */
@Component
public class TaskKinds {

    public enum Kind { MAIN, STREAM, OTHER }

    public record Classification(Kind kind, Optional<String> stream) {

        static Classification other() {
            return new Classification(Kind.OTHER, Optional.empty());
        }
    }

    private static final int MAX_DEPTH = 3;
    private static final String DEFAULT_TYPE = "task";

    private final List<String> mainTypes;
    private final List<String> parentTypes;
    private final List<String> streams;
    private final Pattern streamInName;

    public TaskKinds(AuditProperties properties) {
        this.mainTypes = lower(properties.mainTaskTypesOrEmpty());
        this.parentTypes = lower(properties.rules().streamTaskStatus().parentTypesOrEmpty());
        this.streams = lower(properties.streamsOrEmpty());
        this.streamInName = Pattern.compile("\\b(" + String.join("|", streams.stream().map(Pattern::quote).toList())
                + ")\\b", Pattern.CASE_INSENSITIVE);
    }

    public Classification classify(Task task, AuditContext context) {
        String type = context.typeName(task).map(TaskKinds::lower).orElse("");
        if (mainTypes.contains(type)) {
            return new Classification(Kind.MAIN, Optional.empty());
        }
        if (!DEFAULT_TYPE.equals(type)) {
            return Classification.other();
        }
        // Walk up through Task-type ancestors until a Story/Bug/Change; the top-most Task is the stream task.
        Task streamTask = task;
        Task current = task;
        for (int depth = 0; depth < MAX_DEPTH && current.parent() != null; depth++) {
            Task parent = context.task(current.parent());
            String parentType = context.typeName(parent).map(TaskKinds::lower).orElse("");
            if (parentTypes.contains(parentType)) {
                return new Classification(Kind.STREAM, streamOf(streamTask));
            }
            if (!DEFAULT_TYPE.equals(parentType)) {
                return Classification.other();
            }
            streamTask = parent;
            current = parent;
        }
        return Classification.other();
    }

    public boolean isStreamTag(String tag) {
        return tag != null && streams.contains(lower(tag));
    }

    private Optional<String> streamOf(Task streamTask) {
        if (streamTask.tags() != null) {
            Optional<String> fromTag = streamTask.tags().stream().map(Tag::name).filter(this::isStreamTag)
                    .map(TaskKinds::lower).findFirst();
            if (fromTag.isPresent()) {
                return fromTag;
            }
        }
        Matcher matcher = streamInName.matcher(streamTask.name() == null ? "" : streamTask.name());
        return matcher.find() ? Optional.of(lower(matcher.group(1))) : Optional.empty();
    }

    private static List<String> lower(List<String> values) {
        return values.stream().map(TaskKinds::lower).collect(Collectors.toList());
    }

    private static String lower(String value) {
        return value.trim().toLowerCase(Locale.ROOT);
    }
}

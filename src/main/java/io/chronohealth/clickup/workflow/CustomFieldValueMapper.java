package io.chronohealth.clickup.workflow;

import io.chronohealth.clickup.client.dto.CustomField;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Converts a custom field value as returned by "Get Task" into the shape "Set Custom Field Value" expects.
 * These differ for some field types.
 */
@Component
public class CustomFieldValueMapper {

    /**
     * @return the value to send, or empty if the field type isn't supported for copying
     */
    public Optional<Object> toWriteValue(CustomField field) {
        JsonNode value = field.value();
        return switch (field.type() == null ? "" : field.type()) {
            // GET returns the option's orderindex; SET wants the option id.
            case "drop_down" -> dropDownOptionId(field);
            // GET returns user objects; SET wants {"add": [ids]}.
            case "users" -> Optional.of(Map.of("add", ids(value)));
            // Relationship fields need add/rem semantics against existing links; not supported yet.
            case "tasks", "list_relationship" -> Optional.empty();
            default -> Optional.of(value);
        };
    }

    private Optional<Object> dropDownOptionId(CustomField field) {
        JsonNode value = field.value();
        if (value.isString()) {
            // Some responses already carry the option id.
            return Optional.of(value.asString());
        }
        int orderIndex = value.asInt();
        for (JsonNode option : field.typeConfig().path("options")) {
            if (option.path("orderindex").asInt(-1) == orderIndex) {
                return Optional.of(option.path("id").asString());
            }
        }
        return Optional.empty();
    }

    private static List<Long> ids(JsonNode users) {
        List<Long> ids = new ArrayList<>();
        for (JsonNode user : users) {
            ids.add(user.path("id").asLong());
        }
        return ids;
    }
}

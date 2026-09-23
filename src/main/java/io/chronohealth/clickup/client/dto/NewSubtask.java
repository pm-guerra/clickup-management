package io.chronohealth.clickup.client.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record NewSubtask(
        String name,
        String description,
        List<Long> assignees,
        String status,
        Integer priority,
        Long dueDate
) {

    public static NewSubtask named(String name) {
        return new NewSubtask(name, null, null, null, null, null);
    }
}

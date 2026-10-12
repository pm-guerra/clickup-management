package io.chronohealth.clickup.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import io.chronohealth.clickup.client.dto.CustomField;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class CustomFieldValueMapperTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final JsonNode ENVIRONMENT_OPTIONS = MAPPER.readTree("""
            {"options":[{"id":"opt-prod","name":"Production","orderindex":0},
                        {"id":"opt-staging","name":"Staging","orderindex":1},
                        {"id":"opt-dev","name":"Dev","orderindex":2}]}""");

    private final CustomFieldValueMapper mapper = new CustomFieldValueMapper();

    @Test
    void copiesADropDownGivenAsOrderIndex() {
        CustomField environment = new CustomField("env", "Environment", "drop_down", ENVIRONMENT_OPTIONS,
                MAPPER.valueToTree(1));

        assertThat(mapper.toWriteValue(environment)).contains("opt-staging");
    }

    @Test
    void copiesADropDownGivenAsOptionId() {
        CustomField environment = new CustomField("env", "Environment", "drop_down", ENVIRONMENT_OPTIONS,
                MAPPER.valueToTree("opt-dev"));

        assertThat(mapper.toWriteValue(environment)).contains("opt-dev");
    }
}

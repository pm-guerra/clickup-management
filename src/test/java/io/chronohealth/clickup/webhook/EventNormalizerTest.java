package io.chronohealth.clickup.webhook;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class EventNormalizerTest {

    private static ClickUpWebhookPayload.HistoryItem item(String id) {
        return new ClickUpWebhookPayload.HistoryItem(id, null, null, null, null, null, null, null);
    }

    @Test
    void usesWebhookIdAndHistoryItemId() {
        assertThat(EventNormalizer.idempotencyKey("w1", List.of(item("h1")), new byte[0])).isEqualTo("w1:h1");
        assertThat(EventNormalizer.idempotencyKey("w1", List.of(item("h1"), item("h2")), new byte[0])).isEqualTo("w1:h1,h2");
    }

    @Test
    void fallsBackToDeterministicBodyHash() {
        String a = EventNormalizer.idempotencyKey("w1", List.of(), "{\"a\":1}".getBytes());
        String b = EventNormalizer.idempotencyKey("w1", List.of(), "{\"a\":1}".getBytes());
        String c = EventNormalizer.idempotencyKey("w1", List.of(), "{\"a\":2}".getBytes());

        assertThat(a).isEqualTo(b).startsWith("w1:body-").isNotEqualTo(c);
    }

    @Test
    void keepsTagValuesButStripsOtherChangedValues() {
        JsonMapper mapper = JsonMapper.builder().build();
        ClickUpWebhookPayload payload = new ClickUpWebhookPayload("w1", "taskUpdated", "t1", List.of(
                new ClickUpWebhookPayload.HistoryItem("h1", "1", null, "name", null, null,
                        mapper.valueToTree("old name"), mapper.valueToTree("new name")),
                new ClickUpWebhookPayload.HistoryItem("h2", "1", null, "tag", null, null,
                        null, mapper.readTree("[{\"name\":\"backend\"}]"))));
        WebhookRegistration registration = new WebhookRegistration("w1", "ws", "e", List.of(), "s", Instant.now());

        ClickUpEvent event = new EventNormalizer().normalize(payload, registration, new byte[0]);

        assertThat(event.historyItems().get(0).before()).isNull();
        assertThat(event.historyItems().get(0).after()).isNull();
        assertThat(event.historyItems().get(1).after()).isNotNull();
    }
}

package io.chronohealth.clickup.webhook;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class EventNormalizerTest {

    private static ClickUpWebhookPayload.HistoryItem item(String id) {
        return new ClickUpWebhookPayload.HistoryItem(id, null, null, null, null, null);
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
}

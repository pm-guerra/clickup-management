package io.chronohealth.clickup.webhook;

import java.time.Instant;
import java.util.List;

public record WebhookRegistration(
        String webhookId,
        String workspaceId,
        String endpoint,
        List<String> events,
        String secret,
        Instant createdAt
) {

    @Override
    public String toString() {
        return "WebhookRegistration[webhookId=" + webhookId + ", workspaceId=" + workspaceId + ", endpoint=" + endpoint
                + ", events=" + events + ", secret=***, createdAt=" + createdAt + "]";
    }
}

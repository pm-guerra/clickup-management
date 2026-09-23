package io.chronohealth.clickup.webhook;

import io.chronohealth.clickup.config.ClickUpProperties;
import io.chronohealth.clickup.secret.SecretStore;
import java.util.Optional;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * One webhook per Workspace, stored as secret {@code clickup-webhook-<workspaceId>}.
 */
@Component
public class WebhookRegistrationStore {

    private final SecretStore secretStore;
    private final JsonMapper jsonMapper;
    private final ClickUpProperties properties;

    public WebhookRegistrationStore(SecretStore secretStore, JsonMapper jsonMapper, ClickUpProperties properties) {
        this.secretStore = secretStore;
        this.jsonMapper = jsonMapper;
        this.properties = properties;
    }

    public static String secretName(String workspaceId) {
        return "clickup-webhook-" + workspaceId;
    }

    public void save(WebhookRegistration registration) {
        secretStore.put(secretName(registration.workspaceId()), jsonMapper.writeValueAsString(registration));
    }

    public Optional<WebhookRegistration> findByWorkspace(String workspaceId) {
        return secretStore.get(secretName(workspaceId))
                .map(json -> jsonMapper.readValue(json, WebhookRegistration.class));
    }

    /**
     * Looks the webhook up among the configured Workspaces (currently one).
     */
    public Optional<WebhookRegistration> findByWebhookId(String webhookId) {
        return findByWorkspace(properties.workspaceId())
                .filter(r -> r.webhookId().equals(webhookId));
    }

    public void delete(String workspaceId) {
        secretStore.remove(secretName(workspaceId));
    }
}

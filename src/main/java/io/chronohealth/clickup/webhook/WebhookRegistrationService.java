package io.chronohealth.clickup.webhook;

import io.chronohealth.clickup.client.ClickUpApiException;
import io.chronohealth.clickup.client.ClickUpClient;
import io.chronohealth.clickup.client.ClickUpClientFactory;
import io.chronohealth.clickup.client.dto.Webhook;
import io.chronohealth.clickup.config.AppProperties;
import io.chronohealth.clickup.config.ClickUpProperties;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class WebhookRegistrationService {

    public static final String WEBHOOK_PATH = "/clickup/webhook";

    private static final Logger log = LoggerFactory.getLogger(WebhookRegistrationService.class);

    private final ClickUpClientFactory clientFactory;
    private final WebhookRegistrationStore store;
    private final AppProperties appProperties;
    private final ClickUpProperties clickUpProperties;
    private final Clock clock;

    public WebhookRegistrationService(ClickUpClientFactory clientFactory, WebhookRegistrationStore store,
                                      AppProperties appProperties, ClickUpProperties clickUpProperties, Clock clock) {
        this.clientFactory = clientFactory;
        this.store = store;
        this.appProperties = appProperties;
        this.clickUpProperties = clickUpProperties;
        this.clock = clock;
    }

    /**
     * Registers the webhook for the configured Workspace at {@code APP_BASE_URL/clickup/webhook}.
     * An existing registration is deleted from ClickUp first, so this also works for changing the URL.
     */
    public WebhookRegistration register() {
        String workspaceId = clickUpProperties.workspaceId();
        String endpoint = stripTrailingSlash(appProperties.baseUrl()) + WEBHOOK_PATH;
        List<String> events = clickUpProperties.webhookEvents();
        ClickUpClient client = clientFactory.forWorkspace(workspaceId);

        store.findByWorkspace(workspaceId).ifPresent(existing -> deleteFromClickUp(client, existing.webhookId()));

        Webhook webhook = client.createWebhook(workspaceId, endpoint, events);
        WebhookRegistration registration = new WebhookRegistration(
                webhook.id(), workspaceId, endpoint, events, webhook.secret(), clock.instant());
        store.save(registration);
        log.info("Registered ClickUp webhook {} for workspace {} with events {}", webhook.id(), workspaceId, events);
        return registration;
    }

    public Optional<WebhookRegistration> current() {
        return store.findByWorkspace(clickUpProperties.workspaceId());
    }

    public void delete() {
        String workspaceId = clickUpProperties.workspaceId();
        WebhookRegistration registration = store.findByWorkspace(workspaceId)
                .orElseThrow(NoWebhookException::new);
        deleteFromClickUp(clientFactory.forWorkspace(workspaceId), registration.webhookId());
        store.delete(workspaceId);
    }

    private static void deleteFromClickUp(ClickUpClient client, String webhookId) {
        try {
            client.deleteWebhook(webhookId);
            log.info("Deleted ClickUp webhook {}", webhookId);
        } catch (ClickUpApiException e) {
            if (e.statusCode() != 404) {
                throw e;
            }
            log.info("ClickUp webhook {} was already gone", webhookId);
        }
    }

    private static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    public static class NoWebhookException extends RuntimeException {

        public NoWebhookException() {
            super("No webhook registered");
        }
    }
}

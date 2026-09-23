package io.chronohealth.clickup.webhook;

import io.chronohealth.clickup.client.ClickUpClientFactory;
import io.chronohealth.clickup.client.dto.Webhook;
import io.chronohealth.clickup.config.AppProperties;
import io.chronohealth.clickup.config.ClickUpProperties;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class WebhookRegistrationService {

    public static final String WEBHOOK_PATH = "/clickup/webhook";

    private static final Logger log = LoggerFactory.getLogger(WebhookRegistrationService.class);

    private final ClickUpClientFactory clientFactory;
    private final WebhookRegistrationRepository repository;
    private final AppProperties appProperties;
    private final ClickUpProperties clickUpProperties;
    private final Clock clock;

    public WebhookRegistrationService(ClickUpClientFactory clientFactory, WebhookRegistrationRepository repository,
                                      AppProperties appProperties, ClickUpProperties clickUpProperties, Clock clock) {
        this.clientFactory = clientFactory;
        this.repository = repository;
        this.appProperties = appProperties;
        this.clickUpProperties = clickUpProperties;
        this.clock = clock;
    }

    /**
     * Registers a webhook for the configured Workspace pointing at {@code APP_BASE_URL/clickup/webhook}.
     */
    public WebhookRegistration register() {
        String workspaceId = clickUpProperties.workspaceId();
        String endpoint = stripTrailingSlash(appProperties.baseUrl()) + WEBHOOK_PATH;
        List<String> events = clickUpProperties.webhookEvents();

        Webhook webhook = clientFactory.forWorkspace(workspaceId).createWebhook(workspaceId, endpoint, events);
        WebhookRegistration registration = new WebhookRegistration(
                webhook.id(), workspaceId, endpoint, events, webhook.secret(), OffsetDateTime.now(clock));
        repository.save(registration);
        log.info("Registered ClickUp webhook {} for workspace {} with events {}", webhook.id(), workspaceId, events);
        return registration;
    }

    public List<WebhookRegistration> list() {
        return repository.findByWorkspace(clickUpProperties.workspaceId());
    }

    public void delete(String webhookId) {
        WebhookRegistration registration = repository.findById(webhookId)
                .orElseThrow(() -> new UnknownWebhookException(webhookId));
        clientFactory.forWorkspace(registration.workspaceId()).deleteWebhook(webhookId);
        repository.delete(webhookId);
        log.info("Deleted ClickUp webhook {}", webhookId);
    }

    private static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    public static class UnknownWebhookException extends RuntimeException {

        public UnknownWebhookException(String webhookId) {
            super("Unknown webhook " + webhookId);
        }
    }
}

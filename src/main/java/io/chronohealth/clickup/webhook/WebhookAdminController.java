package io.chronohealth.clickup.webhook;

import io.chronohealth.clickup.client.ClickUpClientFactory;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin endpoints for managing the ClickUp webhook. Protected by {@code X-Admin-Key}.
 */
@RestController
@RequestMapping("/admin/clickup/webhooks")
public class WebhookAdminController {

    private final WebhookRegistrationService service;

    public WebhookAdminController(WebhookRegistrationService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public WebhookView register() {
        return WebhookView.of(service.register());
    }

    @GetMapping
    public List<WebhookView> list() {
        return service.list().stream().map(WebhookView::of).toList();
    }

    @DeleteMapping("/{webhookId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String webhookId) {
        service.delete(webhookId);
    }

    @ExceptionHandler(ClickUpClientFactory.WorkspaceNotAuthorizedException.class)
    public ResponseEntity<Map<String, String>> notAuthorized(RuntimeException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(WebhookRegistrationService.UnknownWebhookException.class)
    public ResponseEntity<Map<String, String>> unknown(RuntimeException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
    }

    /**
     * Registration without the secret.
     */
    public record WebhookView(String webhookId, String workspaceId, String endpoint, List<String> events,
                              OffsetDateTime createdAt) {

        static WebhookView of(WebhookRegistration r) {
            return new WebhookView(r.webhookId(), r.workspaceId(), r.endpoint(), r.events(), r.createdAt());
        }
    }
}

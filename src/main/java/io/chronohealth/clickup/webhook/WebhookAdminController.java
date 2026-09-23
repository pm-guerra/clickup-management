package io.chronohealth.clickup.webhook;

import io.chronohealth.clickup.client.ClickUpClientFactory;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin endpoints for managing the ClickUp webhook. Protected by {@code X-Admin-Key}.
 */
@RestController
@RequestMapping("/admin/clickup/webhook")
public class WebhookAdminController {

    private final WebhookRegistrationService service;

    public WebhookAdminController(WebhookRegistrationService service) {
        this.service = service;
    }

    /**
     * Registers (or re-registers) the webhook.
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public WebhookView register() {
        return WebhookView.of(service.register());
    }

    @GetMapping
    public ResponseEntity<WebhookView> current() {
        return ResponseEntity.of(service.current().map(WebhookView::of));
    }

    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete() {
        service.delete();
    }

    @ExceptionHandler(ClickUpClientFactory.WorkspaceNotAuthorizedException.class)
    public ResponseEntity<Map<String, String>> notAuthorized(RuntimeException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(WebhookRegistrationService.NoWorkflowsEnabledException.class)
    public ResponseEntity<Map<String, String>> noWorkflows(RuntimeException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(WebhookRegistrationService.NoWebhookException.class)
    public ResponseEntity<Map<String, String>> none(RuntimeException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
    }

    /**
     * Registration without the secret.
     */
    public record WebhookView(String webhookId, String workspaceId, String endpoint, List<String> events,
                              Instant createdAt) {

        static WebhookView of(WebhookRegistration r) {
            return new WebhookView(r.webhookId(), r.workspaceId(), r.endpoint(), r.events(), r.createdAt());
        }
    }
}

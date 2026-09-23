package io.chronohealth.clickup.webhook;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class WebhookController {

    private final WebhookService webhookService;

    public WebhookController(WebhookService webhookService) {
        this.webhookService = webhookService;
    }

    /**
     * Takes the body as raw bytes: the signature is computed over the exact bytes ClickUp sent.
     */
    @PostMapping(WebhookRegistrationService.WEBHOOK_PATH)
    public ResponseEntity<Void> receive(@RequestBody byte[] rawBody,
                                        @RequestHeader(name = "X-Signature", required = false) String signature) {
        HttpStatus status = switch (webhookService.handle(rawBody, signature)) {
            case ACCEPTED, DUPLICATE -> HttpStatus.OK;
            case REJECTED -> HttpStatus.UNAUTHORIZED;
            case MALFORMED -> HttpStatus.BAD_REQUEST;
        };
        return ResponseEntity.status(status).build();
    }
}

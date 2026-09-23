package io.chronohealth.clickup.webhook;

import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Webhook pipeline: signature verification -> normalization -> idempotency -> dispatch.
 * Transport concerns stop here; business rules live in {@link EventHandler}s.
 */
@Service
public class WebhookService {

    private static final Logger log = LoggerFactory.getLogger(WebhookService.class);

    private final JsonMapper jsonMapper;
    private final WebhookRegistrationRepository registrations;
    private final WebhookSignatureVerifier signatureVerifier;
    private final EventNormalizer normalizer;
    private final ProcessedEventStore processedEvents;
    private final EventDispatcher dispatcher;

    public WebhookService(JsonMapper jsonMapper, WebhookRegistrationRepository registrations,
                          WebhookSignatureVerifier signatureVerifier, EventNormalizer normalizer,
                          ProcessedEventStore processedEvents, EventDispatcher dispatcher) {
        this.jsonMapper = jsonMapper;
        this.registrations = registrations;
        this.signatureVerifier = signatureVerifier;
        this.normalizer = normalizer;
        this.processedEvents = processedEvents;
        this.dispatcher = dispatcher;
    }

    public Outcome handle(byte[] rawBody, String signature) {
        JsonNode tree;
        try {
            tree = jsonMapper.readTree(rawBody);
        } catch (JacksonException _) {
            return Outcome.MALFORMED;
        }

        // The secret is per webhook, so we need the (still unverified) webhook_id to find it.
        // Nothing else from the body is trusted until the signature checks out.
        String webhookId = tree.path("webhook_id").asString(null);
        if (webhookId == null) {
            return Outcome.MALFORMED;
        }
        Optional<WebhookRegistration> registration = registrations.findById(webhookId);
        if (registration.isEmpty()) {
            log.warn("Rejected webhook delivery for unknown webhook {}", webhookId);
            return Outcome.REJECTED;
        }
        if (!signatureVerifier.isValid(rawBody, signature, registration.get().secret())) {
            log.warn("Rejected webhook delivery with invalid signature for webhook {}", webhookId);
            return Outcome.REJECTED;
        }

        ClickUpWebhookPayload payload = jsonMapper.treeToValue(tree, ClickUpWebhookPayload.class);
        ClickUpEvent event = normalizer.normalize(payload, registration.get(), rawBody);

        if (!processedEvents.tryClaim(event)) {
            log.info("Skipping duplicate event {} ({})", event.idempotencyKey(), event.type());
            return Outcome.DUPLICATE;
        }
        try {
            dispatcher.dispatch(event);
            processedEvents.markCompleted(event);
            return Outcome.PROCESSED;
        } catch (RuntimeException e) {
            // Release so ClickUp's retry gets another chance.
            processedEvents.release(event);
            throw e;
        }
    }

    public enum Outcome {
        PROCESSED,
        DUPLICATE,
        REJECTED,
        MALFORMED
    }
}

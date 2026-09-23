package io.chronohealth.clickup.webhook;

import io.chronohealth.clickup.event.EventRepository;
import io.chronohealth.clickup.event.EventRepository.NewEvent;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Webhook pipeline: signature verification -> normalization -> store in the event inbox (deduplicated by
 * idempotency key) -> acknowledge. No ClickUp calls happen here: the endpoint must answer instantly even during
 * bursts, or ClickUp counts failures and suspends the webhook. The inbox is worked through, paced, by
 * {@link io.chronohealth.clickup.event.EventRetryJob}. Business rules live in {@link EventHandler}s.
 */
@Service
public class WebhookService {

    private static final Logger log = LoggerFactory.getLogger(WebhookService.class);

    private final JsonMapper jsonMapper;
    private final WebhookRegistrationStore registrations;
    private final WebhookSignatureVerifier signatureVerifier;
    private final EventNormalizer normalizer;
    private final EventRepository events;

    public WebhookService(JsonMapper jsonMapper, WebhookRegistrationStore registrations,
                          WebhookSignatureVerifier signatureVerifier, EventNormalizer normalizer,
                          EventRepository events) {
        this.jsonMapper = jsonMapper;
        this.registrations = registrations;
        this.signatureVerifier = signatureVerifier;
        this.normalizer = normalizer;
        this.events = events;
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
        Optional<WebhookRegistration> registration = registrations.findByWebhookId(webhookId);
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

        Optional<Long> eventId = events.insert(new NewEvent(event.idempotencyKey(), event.webhookId(),
                event.workspaceId(), event.type(), event.taskId(), jsonMapper.writeValueAsString(event)));
        if (eventId.isEmpty()) {
            log.info("Skipping duplicate delivery {} ({}, task {})", event.idempotencyKey(), event.type(), event.taskId());
            return Outcome.DUPLICATE;
        }
        log.info("Stored event {} ({}, task {})", eventId.get(), event.type(), event.taskId());
        return Outcome.ACCEPTED;
    }

    public enum Outcome {
        ACCEPTED,
        DUPLICATE,
        REJECTED,
        MALFORMED
    }
}

package io.chronohealth.clickup.webhook;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class WebhookSignatureVerifierTest {

    private final WebhookSignatureVerifier verifier = new WebhookSignatureVerifier();
    private final byte[] body = "{\"webhook_id\":\"w1\",\"event\":\"taskCreated\"}".getBytes(StandardCharsets.UTF_8);

    @Test
    void acceptsMatchingSignature() {
        String signature = verifier.sign(body, "secret");

        assertThat(verifier.isValid(body, signature, "secret")).isTrue();
        assertThat(verifier.isValid(body, signature.toUpperCase(), "secret")).isTrue();
    }

    @Test
    void rejectsWrongSecretTamperedBodyOrMissingHeader() {
        String signature = verifier.sign(body, "secret");

        assertThat(verifier.isValid(body, signature, "other")).isFalse();
        assertThat(verifier.isValid("{}".getBytes(StandardCharsets.UTF_8), signature, "secret")).isFalse();
        assertThat(verifier.isValid(body, null, "secret")).isFalse();
    }

    @Test
    void matchesKnownHmacSha256Vector() {
        // Computed with: echo -n 'hello' | openssl dgst -sha256 -hmac key
        assertThat(verifier.sign("hello".getBytes(StandardCharsets.UTF_8), "key"))
                .isEqualTo("9307b3b915efb5171ff14d8cb55fbcc798c6c0ef1456d66ded1a6aa723a58b7b");
    }
}

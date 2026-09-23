package io.chronohealth.clickup.webhook;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * Verifies ClickUp's {@code X-Signature} header: hex(HMAC-SHA256(webhookSecret, rawBody)).
 */
@Component
public class WebhookSignatureVerifier {

    private static final String ALGORITHM = "HmacSHA256";

    public boolean isValid(byte[] rawBody, String signatureHeader, String secret) {
        if (signatureHeader == null || signatureHeader.isBlank() || secret == null) {
            return false;
        }
        byte[] expected = sign(rawBody, secret).getBytes(StandardCharsets.US_ASCII);
        byte[] provided = signatureHeader.trim().toLowerCase().getBytes(StandardCharsets.US_ASCII);
        return MessageDigest.isEqual(expected, provided);
    }

    public String sign(byte[] rawBody, String secret) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM));
            return HexFormat.of().formatHex(mac.doFinal(rawBody));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC computation failed", e);
        }
    }
}

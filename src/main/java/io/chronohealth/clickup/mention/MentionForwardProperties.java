package io.chronohealth.clickup.mention;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Where new mentions are sent: the agent's Claude Code routine ("fire" endpoint).
 *
 * @param url         the routine's fire URL
 * @param token       bearer token for the routine; forwarding is off while it's empty
 * @param betaHeader  value of the {@code anthropic-beta} header the routine API requires
 * @param maxAttempts tries per mention (one per minute) before giving up on it
 * @param maxAge      mentions recorded longer ago than this are no longer sent
 * @param timeout     read timeout of a call to the routine
 */
@Validated
@ConfigurationProperties("app.mentions.forward")
public record MentionForwardProperties(
        String url,
        String token,
        String betaHeader,
        @Min(1) int maxAttempts,
        @NotNull Duration maxAge,
        @NotNull Duration timeout
) {

    public boolean enabled() {
        return url != null && !url.isBlank() && token != null && !token.isBlank();
    }

    @Override
    public String toString() {
        return "MentionForwardProperties[url=" + url + ", token=" + (token == null || token.isBlank() ? "" : "***")
                + ", betaHeader=" + betaHeader + ", maxAttempts=" + maxAttempts + ", maxAge=" + maxAge
                + ", timeout=" + timeout + "]";
    }
}

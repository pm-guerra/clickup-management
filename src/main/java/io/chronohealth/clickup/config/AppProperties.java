package io.chronohealth.clickup.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("app")
public record AppProperties(
        @NotBlank String baseUrl,
        @NotBlank String adminApiKey,
        @NotBlank String tokenEncryptionKey
) {
}

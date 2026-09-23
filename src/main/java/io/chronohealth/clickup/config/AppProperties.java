package io.chronohealth.clickup.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("app")
public record AppProperties(
        @NotBlank String baseUrl,
        @NotBlank String adminApiKey,
        @Valid @NotNull SecretStore secretStore
) {

    /**
     * @param type               {@code local} (encrypted files, for development) or {@code gcp} (Secret Manager)
     * @param localPath          directory for the local store
     * @param localEncryptionKey base64 AES-256 key for the local store
     * @param gcpProjectId       project holding the secrets for the gcp store
     */
    public record SecretStore(@NotBlank String type, String localPath, String localEncryptionKey, String gcpProjectId) {
    }
}

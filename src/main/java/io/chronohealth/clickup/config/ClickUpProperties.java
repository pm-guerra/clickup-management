package io.chronohealth.clickup.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("clickup")
public record ClickUpProperties(
        @NotBlank String clientId,
        @NotBlank String clientSecret,
        @NotBlank String redirectUri,
        @NotBlank String workspaceId,
        @NotBlank String apiBaseUrl,
        @NotBlank String authorizeUrl,
        @NotNull Duration connectTimeout,
        @NotNull Duration readTimeout,
        @Valid @NotNull RateLimit rateLimit,
        @NotEmpty List<String> webhookEvents,
        @Valid @NotNull Workflows workflows
) {

    public record RateLimit(int maxRetries, @NotNull Duration maxWait) {
    }

    public record Workflows(@Valid @NotNull CopyParentFields copyParentFields) {
    }

    /**
     * @param fieldMappings       JSON object of {@code {"childFieldId": "parentFieldId"}}.
     * @param parentCustomItemId  when set, only parents of this custom task type (e.g. "Bug") are considered.
     */
    public record CopyParentFields(boolean enabled, String fieldMappings, String parentCustomItemId) {
    }
}

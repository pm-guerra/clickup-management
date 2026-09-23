package io.chronohealth.clickup.client;

import io.chronohealth.clickup.config.ClickUpProperties;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

/**
 * Unauthenticated ClickUp calls used during the OAuth handshake.
 */
@Component
public class ClickUpOAuthClient {

    private final ClickUpHttp http;
    private final ClickUpProperties properties;

    public ClickUpOAuthClient(ClickUpHttp http, ClickUpProperties properties) {
        this.http = http;
        this.properties = properties;
    }

    /**
     * Exchanges an authorization code for an access token.
     */
    public String exchangeCode(String code) {
        TokenRequest request = new TokenRequest(properties.clientId(), properties.clientSecret(), code);
        TokenResponse response = http.execute("exchangeOAuthCode", () -> http.restClient().post()
                .uri("/oauth/token")
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(TokenResponse.class));
        if (response == null || response.accessToken() == null) {
            throw new ClickUpApiException(502, "NO_ACCESS_TOKEN");
        }
        return response.accessToken();
    }

    private record TokenRequest(String clientId, String clientSecret, String code) {

        @Override
        public String toString() {
            return "TokenRequest[clientId=" + clientId + ", clientSecret=***, code=***]";
        }
    }

    private record TokenResponse(String accessToken) {

        @Override
        public String toString() {
            return "TokenResponse[accessToken=***]";
        }
    }
}

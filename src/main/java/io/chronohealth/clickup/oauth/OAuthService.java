package io.chronohealth.clickup.oauth;

import io.chronohealth.clickup.client.ClickUpClient;
import io.chronohealth.clickup.client.ClickUpClientFactory;
import io.chronohealth.clickup.client.ClickUpOAuthClient;
import io.chronohealth.clickup.client.dto.User;
import io.chronohealth.clickup.config.ClickUpProperties;
import io.chronohealth.clickup.token.AccessTokenStore;
import io.chronohealth.clickup.token.AccessTokenStore.StoredAccessToken;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriComponentsBuilder;

@Service
public class OAuthService {

    private static final Logger log = LoggerFactory.getLogger(OAuthService.class);

    private final ClickUpProperties properties;
    private final OAuthStateStore stateStore;
    private final ClickUpOAuthClient oauthClient;
    private final ClickUpClientFactory clientFactory;
    private final AccessTokenStore tokenStore;

    public OAuthService(ClickUpProperties properties, OAuthStateStore stateStore, ClickUpOAuthClient oauthClient,
                        ClickUpClientFactory clientFactory, AccessTokenStore tokenStore) {
        this.properties = properties;
        this.stateStore = stateStore;
        this.oauthClient = oauthClient;
        this.clientFactory = clientFactory;
        this.tokenStore = tokenStore;
    }

    public String buildAuthorizationUrl() {
        return UriComponentsBuilder.fromUriString(properties.authorizeUrl())
                .queryParam("client_id", properties.clientId())
                .queryParam("redirect_uri", properties.redirectUri())
                .queryParam("state", stateStore.create())
                .encode()
                .toUriString();
    }

    /**
     * Validates the state, exchanges the code and stores the token for the configured Workspace.
     *
     * @return the Workspace id the token was stored for
     */
    public String completeAuthorization(String code, String state) {
        if (state == null || !stateStore.consume(state)) {
            throw new InvalidOAuthStateException();
        }

        String accessToken = oauthClient.exchangeCode(code);
        ClickUpClient client = clientFactory.forToken(accessToken);

        String workspaceId = properties.workspaceId();
        boolean authorized = client.getAuthorizedWorkspaces().stream().anyMatch(w -> workspaceId.equals(w.id()));
        if (!authorized) {
            throw new WorkspaceNotGrantedException(workspaceId);
        }

        User user = client.getAuthorizedUser();
        tokenStore.save(new StoredAccessToken(workspaceId, accessToken, user == null ? null : String.valueOf(user.id())));
        log.info("Stored ClickUp OAuth token for workspace {}", workspaceId);
        return workspaceId;
    }

    public static class InvalidOAuthStateException extends RuntimeException {

        public InvalidOAuthStateException() {
            super("Invalid or expired OAuth state");
        }
    }

    public static class WorkspaceNotGrantedException extends RuntimeException {

        public WorkspaceNotGrantedException(String workspaceId) {
            super("The authorization did not grant access to workspace " + workspaceId);
        }
    }
}

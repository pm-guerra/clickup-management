package io.chronohealth.clickup.client;

import io.chronohealth.clickup.token.AccessTokenStore;
import org.springframework.stereotype.Component;

@Component
public class ClickUpClientFactory {

    private final ClickUpHttp http;
    private final AccessTokenStore tokenStore;

    public ClickUpClientFactory(ClickUpHttp http, AccessTokenStore tokenStore) {
        this.http = http;
        this.tokenStore = tokenStore;
    }

    /**
     * Client authorized with the stored OAuth token for the given Workspace.
     *
     * @throws WorkspaceNotAuthorizedException if OAuth hasn't been completed for that Workspace
     */
    public ClickUpClient forWorkspace(String workspaceId) {
        String token = tokenStore.find(workspaceId)
                .orElseThrow(() -> new WorkspaceNotAuthorizedException(workspaceId))
                .accessToken();
        return new ClickUpClient(http, token);
    }

    /**
     * Client for a freshly obtained token (used during the OAuth callback, before it's stored).
     */
    public ClickUpClient forToken(String accessToken) {
        return new ClickUpClient(http, accessToken);
    }

    public static class WorkspaceNotAuthorizedException extends RuntimeException {

        public WorkspaceNotAuthorizedException(String workspaceId) {
            super("No ClickUp OAuth token for workspace " + workspaceId + "; complete /clickup/oauth/start first");
        }
    }
}

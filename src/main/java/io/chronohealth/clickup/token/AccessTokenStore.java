package io.chronohealth.clickup.token;

import java.util.Optional;

/**
 * Storage for ClickUp OAuth access tokens, keyed by Workspace.
 * <p>
 * ClickUp OAuth tokens currently don't expire. If that changes, refresh/rotation belongs behind this interface.
 */
public interface AccessTokenStore {

    void save(StoredAccessToken token);

    Optional<StoredAccessToken> find(String workspaceId);

    record StoredAccessToken(String workspaceId, String accessToken, String authorizedUserId) {

        @Override
        public String toString() {
            return "StoredAccessToken[workspaceId=" + workspaceId + ", accessToken=***]";
        }
    }
}

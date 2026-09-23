package io.chronohealth.clickup.token;

import io.chronohealth.clickup.secret.SecretStore;
import java.util.Optional;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Stores each Workspace's token as secret {@code clickup-oauth-token-<workspaceId>}.
 */
@Component
public class SecretStoreAccessTokenStore implements AccessTokenStore {

    private final SecretStore secretStore;
    private final JsonMapper jsonMapper;

    public SecretStoreAccessTokenStore(SecretStore secretStore, JsonMapper jsonMapper) {
        this.secretStore = secretStore;
        this.jsonMapper = jsonMapper;
    }

    public static String secretName(String workspaceId) {
        return "clickup-oauth-token-" + workspaceId;
    }

    @Override
    public void save(StoredAccessToken token) {
        secretStore.put(secretName(token.workspaceId()), jsonMapper.writeValueAsString(token));
    }

    @Override
    public Optional<StoredAccessToken> find(String workspaceId) {
        return secretStore.get(secretName(workspaceId))
                .map(json -> jsonMapper.readValue(json, StoredAccessToken.class));
    }
}

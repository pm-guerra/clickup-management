package io.chronohealth.clickup.secret;

import com.google.api.gax.rpc.NotFoundException;
import com.google.cloud.secretmanager.v1.SecretManagerServiceClient;
import com.google.cloud.secretmanager.v1.SecretName;
import com.google.cloud.secretmanager.v1.SecretPayload;
import com.google.cloud.secretmanager.v1.SecretVersionName;
import com.google.protobuf.ByteString;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * GCP Secret Manager store. The secrets themselves are created by infrastructure setup; the service account
 * only needs {@code secretAccessor} and {@code secretVersionAdder} on them. Writes add a new version.
 * Values are cached in memory, so Secret Manager is only read once per secret per instance.
 */
public class GcpSecretManagerStore implements SecretStore, AutoCloseable {

    /**
     * Secret Manager versions can't be removed without extra permissions, so removal writes this marker.
     */
    private static final String TOMBSTONE = "__removed__";

    private static final Logger log = LoggerFactory.getLogger(GcpSecretManagerStore.class);

    private final SecretManagerServiceClient client;
    private final String projectId;
    private final Map<String, Optional<String>> cache = new ConcurrentHashMap<>();

    public GcpSecretManagerStore(SecretManagerServiceClient client, String projectId) {
        this.client = client;
        this.projectId = projectId;
    }

    @Override
    public Optional<String> get(String name) {
        return cache.computeIfAbsent(name, this::load);
    }

    @Override
    public void put(String name, String value) {
        write(name, value);
        cache.put(name, Optional.of(value));
    }

    @Override
    public void remove(String name) {
        write(name, TOMBSTONE);
        cache.put(name, Optional.empty());
    }

    @Override
    public void close() {
        client.close();
    }

    private Optional<String> load(String name) {
        try {
            String value = client.accessSecretVersion(SecretVersionName.of(projectId, name, "latest"))
                    .getPayload().getData().toString(StandardCharsets.UTF_8);
            return TOMBSTONE.equals(value) ? Optional.empty() : Optional.of(value);
        } catch (NotFoundException _) {
            // Secret exists but has no versions yet (or doesn't exist): nothing stored.
            return Optional.empty();
        }
    }

    private void write(String name, String value) {
        client.addSecretVersion(SecretName.of(projectId, name),
                SecretPayload.newBuilder().setData(ByteString.copyFrom(value, StandardCharsets.UTF_8)).build());
        log.info("Stored new version of secret {}", name);
    }
}

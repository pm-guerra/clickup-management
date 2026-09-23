package io.chronohealth.clickup.secret;

import com.google.cloud.secretmanager.v1.SecretManagerServiceClient;
import io.chronohealth.clickup.config.AppProperties;
import java.io.IOException;
import java.nio.file.Path;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class SecretStoreConfig {

    @Bean
    @ConditionalOnProperty(name = "app.secret-store.type", havingValue = "local", matchIfMissing = true)
    SecretStore localFileSecretStore(AppProperties properties) {
        AppProperties.SecretStore config = properties.secretStore();
        return new LocalFileSecretStore(Path.of(config.localPath()), new SecretCipher(config.localEncryptionKey()));
    }

    @Bean
    @ConditionalOnProperty(name = "app.secret-store.type", havingValue = "gcp")
    SecretStore gcpSecretManagerStore(AppProperties properties) throws IOException {
        String projectId = properties.secretStore().gcpProjectId();
        if (projectId == null || projectId.isBlank()) {
            throw new IllegalStateException("GCP_PROJECT_ID is required when SECRET_STORE=gcp");
        }
        return new GcpSecretManagerStore(SecretManagerServiceClient.create(), projectId);
    }
}

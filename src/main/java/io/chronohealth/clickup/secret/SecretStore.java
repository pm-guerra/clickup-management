package io.chronohealth.clickup.secret;

import java.util.Optional;

/**
 * Durable key/value storage for the few secrets this service must remember across restarts
 * (OAuth tokens, webhook secrets). Backed by GCP Secret Manager when deployed, a local encrypted file otherwise.
 */
public interface SecretStore {

    Optional<String> get(String name);

    void put(String name, String value);

    void remove(String name);
}

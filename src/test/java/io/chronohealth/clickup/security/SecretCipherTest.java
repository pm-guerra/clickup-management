package io.chronohealth.clickup.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.chronohealth.clickup.config.AppProperties;
import org.junit.jupiter.api.Test;

class SecretCipherTest {

    private final SecretCipher cipher = new SecretCipher(
            new AppProperties("http://x", "k", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="));

    @Test
    void roundTrips() {
        String encrypted = cipher.encrypt("pk_secret_token");

        assertThat(encrypted).doesNotContain("pk_secret_token");
        assertThat(cipher.decrypt(encrypted)).isEqualTo("pk_secret_token");
    }

    @Test
    void usesRandomIv() {
        assertThat(cipher.encrypt("same")).isNotEqualTo(cipher.encrypt("same"));
    }

    @Test
    void rejectsWrongKeyLength() {
        assertThatThrownBy(() -> new SecretCipher(new AppProperties("http://x", "k", "AAAA")))
                .isInstanceOf(IllegalStateException.class);
    }
}

package io.chronohealth.clickup.secret;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class SecretCipherTest {

    private final SecretCipher cipher = new SecretCipher("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");

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
        assertThatThrownBy(() -> new SecretCipher("AAAA"))
                .isInstanceOf(IllegalStateException.class);
    }
}

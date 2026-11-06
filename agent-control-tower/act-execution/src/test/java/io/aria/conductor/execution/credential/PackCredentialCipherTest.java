package io.aria.conductor.execution.credential;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Behaviour + security tests for {@link PackCredentialCipher}.
 *
 * <p>With a configured key the cipher uses AES-GCM: encryption is non-deterministic (random IV),
 * decryption round-trips, a wrong key fails, and any tampering with the ciphertext fails the
 * GCM authentication tag. Without a key it degrades to reversible Base64 (dev mode). Null inputs
 * pass through as null in both modes.
 */
class PackCredentialCipherTest {

    private static final String KEY = "test-master-key-please-change";

    private PackCredentialCipher withKey(String key) {
        return new PackCredentialCipher(key);
    }

    @ParameterizedTest(name = "AES-GCM round-trips: [{0}]")
    @ValueSource(strings = {"ghp_secrettoken123", "a", "with spaces and — unicode ✓", "{\"json\":true}"})
    void encryptThenDecrypt_roundTrips_withKey(String plaintext) {
        PackCredentialCipher cipher = withKey(KEY);

        String encrypted = cipher.encrypt(plaintext);

        assertThat(encrypted).isNotEqualTo(plaintext); // actually encrypted, not stored raw
        assertThat(cipher.decrypt(encrypted)).isEqualTo(plaintext);
    }

    @Test
    void encrypt_isNonDeterministic_dueToRandomIv() {
        PackCredentialCipher cipher = withKey(KEY);

        String a = cipher.encrypt("same-plaintext");
        String b = cipher.encrypt("same-plaintext");

        assertThat(a).isNotEqualTo(b); // different IV each time
        // ...yet both decrypt back to the original.
        assertThat(cipher.decrypt(a)).isEqualTo("same-plaintext");
        assertThat(cipher.decrypt(b)).isEqualTo("same-plaintext");
    }

    @Test
    void decrypt_withWrongKey_fails() {
        String encrypted = withKey(KEY).encrypt("topsecret");

        assertThatThrownBy(() -> withKey("a-different-master-key").decrypt(encrypted))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("Credential decryption failed");
    }

    @Test
    void decrypt_tamperedCiphertext_failsGcmAuthentication() {
        PackCredentialCipher cipher = withKey(KEY);
        String encrypted = cipher.encrypt("integrity-matters");

        // Flip the last Base64 char to corrupt the authentication tag.
        char last = encrypted.charAt(encrypted.length() - 1);
        char flipped = last == 'A' ? 'B' : 'A';
        String tampered = encrypted.substring(0, encrypted.length() - 1) + flipped;

        assertThatThrownBy(() -> cipher.decrypt(tampered))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("Credential decryption failed");
    }

    @Test
    void devMode_withoutKey_usesReversibleBase64() {
        PackCredentialCipher cipher = withKey(""); // no key configured

        String encoded = cipher.encrypt("plainvalue");

        assertThat(encoded).isNotEqualTo("plainvalue");
        assertThat(cipher.decrypt(encoded)).isEqualTo("plainvalue");
        // Base64 mode is a plain encoding (not encryption) — decodes to the original bytes.
        assertThat(new String(java.util.Base64.getDecoder().decode(encoded))).isEqualTo("plainvalue");
    }

    @Test
    void nullInputs_passThroughAsNull_inBothModes() {
        assertThat(withKey(KEY).encrypt(null)).isNull();
        assertThat(withKey(KEY).decrypt(null)).isNull();
        assertThat(withKey("").encrypt(null)).isNull();
        assertThat(withKey("").decrypt(null)).isNull();
    }
}

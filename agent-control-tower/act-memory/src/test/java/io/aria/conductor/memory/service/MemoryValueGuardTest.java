package io.aria.conductor.memory.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The write-boundary secret filter. The rule is deliberately narrow (known credential
 * prefixes plus a high-entropy run that is not a hex digest), so the negative cases matter
 * as much as the positive ones: an ordinary timezone, branch, editor or commit SHA value
 * must still be storable while a key-shaped value is rejected.
 * <p>
 * Every rejection names the field and never the offending input, so a validation error
 * cannot become a secret leak of its own.
 */
class MemoryValueGuardTest {

    private final MemoryValueGuard guard = new MemoryValueGuard();

    @ParameterizedTest
    @ValueSource(strings = {
            "api_key", "apiKey", "api-key", "access_token", "refreshToken",
            "password", "db_password", "client_secret", "credentials", "credential_id"
    })
    void credentialLikeKeysAreRejected(String key) {
        assertThatThrownBy(() -> guard.checkKey("factKey", key))
                .isInstanceOf(MemoryValidationException.class)
                .hasMessageContaining("factKey")
                .hasMessageNotContaining(key);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "timezone", "preferred_editor", "default_branch", "repo", "workspace", "editor"
    })
    void ordinaryKeysPass(String key) {
        assertThatCode(() -> guard.checkKey("factKey", key)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "sk-live-51H8xQ2eZ",
            "ghp_16CharactersOfPat",
            "xoxb-1234-5678-abcdef",
            "QUJDREVGR0hJSktMTU5PUFFSU1RVVldYWVo0NTY3ODkw",
            "0123456789012345678901234567890123456789"
    })
    void credentialLikeValuesAreRejected(String value) {
        assertThatThrownBy(() -> guard.checkValue("valueText", value))
                .isInstanceOf(MemoryValidationException.class)
                .hasMessageContaining("valueText")
                .hasMessageNotContaining(value);
    }

    /**
     * A commit SHA is a 40-hex digest, not authentication material. It must stay storable:
     * the branch value is the most common structural fact a run writes.
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "9f2c1b4d6e8a0c3f5b7d9e1a3c5f7b9d1e3a5c7f",
            "9F2C1B4D6E8A0C3F5B7D9E1A3C5F7B9D1E3A5C7F",
            "9f2c1b4d6e8a0c3f5b7d9e1a3c5f7b9d1e3a5c7f9f2c1b4d6e8a0c3f5b7d9e1a"
    })
    void aHexDigestIsNotAuthenticationMaterial(String value) {
        assertThatCode(() -> guard.checkValue("valueText", value))
                .as("a commit SHA must be storable")
                .doesNotThrowAnyException();
    }

    /** The positive control: a value shaped like a real API key is still rejected. */
    @ParameterizedTest
    @ValueSource(strings = {
            "sk-proj-9f8a7b6c5d4e3f2a1b0c",
            "ghp_a1B2c3D4e5F6g7H8i9J0k1L2m3N4",
            "Zx9Qw3Er5Ty7Ui1Op2As4Df6Gh8Jk0Lm3Nn5Bb7Vv"
    })
    void aRealLookingApiKeyIsRejected(String value) {
        assertThatThrownBy(() -> guard.checkValue("valueText", value))
                .isInstanceOf(MemoryValidationException.class)
                .hasMessageContaining("valueText")
                .hasMessageNotContaining(value);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Asia/Hong_Kong", "vim", "main", "aria-conductor",
            "012345678901234567890123456789012345678",
            "2026-11-09T00:00:00Z"
    })
    void ordinaryValuesPass(String value) {
        assertThatCode(() -> guard.checkValue("valueText", value)).doesNotThrowAnyException();
    }

    @Test
    void aNullInputIsLeftToFieldValidation() {
        assertThatCode(() -> {
            guard.checkKey("factKey", null);
            guard.checkValue("valueText", null);
        }).doesNotThrowAnyException();
    }
}

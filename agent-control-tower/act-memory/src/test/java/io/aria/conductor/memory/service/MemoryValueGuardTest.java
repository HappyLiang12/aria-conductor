package io.aria.conductor.memory.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The write-boundary secret filter. The rule is intentionally blunt (the plan's
 * acceptance matrix), so the negative cases matter as much as the positive ones: an
 * ordinary timezone, branch or editor value must still be storable.
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

package io.aria.conductor.execution.adk.opencode;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Construction-level tests for {@link OpenCodeSandboxManager}.
 *
 * <p>Covers the api-key handling contract: the OpenSandbox SDK rejects blank
 * keys ("API key cannot be blank") and falls back to the OPEN_SANDBOX_API_KEY
 * env var when the key is null, so a blank configured key must not be passed
 * to the SDK builder (regression: application failed to start with the default
 * empty OPENSANDBOX_API_KEY).
 */
class OpenCodeSandboxManagerTest {

    @Test
    void constructor_acceptsNullApiKey() {
        assertThatCode(() -> new OpenCodeSandboxManager("http://localhost:8080", null))
                .doesNotThrowAnyException();
    }

    @Test
    void constructor_acceptsBlankApiKey() {
        assertThatCode(() -> new OpenCodeSandboxManager("http://localhost:8080", "  "))
                .doesNotThrowAnyException();
    }

    @Test
    void constructor_acceptsConfiguredApiKey() {
        assertThatCode(() -> new OpenCodeSandboxManager("http://localhost:8080", "secret-key"))
                .doesNotThrowAnyException();
    }

    @Test
    void constructor_acceptsNullServerUrl() {
        assertThatCode(() -> new OpenCodeSandboxManager(null, null))
                .doesNotThrowAnyException();
    }
}

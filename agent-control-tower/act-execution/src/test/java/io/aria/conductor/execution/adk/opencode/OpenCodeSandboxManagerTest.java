package io.aria.conductor.execution.adk.opencode;

import com.alibaba.opensandbox.sandbox.Sandbox;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Construction-level tests for {@link OpenCodeSandboxManager}.
 *
 * <p>Covers the api-key handling contract: the OpenSandbox SDK rejects blank
 * keys ("API key cannot be blank") and falls back to the OPEN_SANDBOX_API_KEY
 * env var when the key is null, so a blank configured key must not be passed
 * to the SDK builder (regression: application failed to start with the default
 * empty OPENSANDBOX_API_KEY).
 *
 * <p>Also covers env var injection: {@code createSandbox(agentId, image, env)}
 * must forward a non-empty env map to {@code Sandbox.Builder#env(Map)} and skip
 * the SDK env call for null/empty maps.
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

    @Test
    void createSandbox_passesEnvToBuilder() {
        UUID agentId = UUID.randomUUID();
        Map<String, String> env = Map.of("DEEPSEEK_API_KEY", "secret-key");
        try (MockedStatic<Sandbox> sandboxStatic = mockStatic(Sandbox.class)) {
            Sandbox.Builder builder = mock(Sandbox.Builder.class);
            Sandbox sandbox = mock(Sandbox.class);
            sandboxStatic.when(Sandbox::builder).thenReturn(builder);
            when(builder.connectionConfig(any())).thenReturn(builder);
            when(builder.image(anyString())).thenReturn(builder);
            when(builder.timeout(any())).thenReturn(builder);
            when(builder.env(anyMap())).thenReturn(builder);
            when(builder.build()).thenReturn(sandbox);
            when(sandbox.getId()).thenReturn("sb-1");

            OpenCodeSandboxManager manager = new OpenCodeSandboxManager("http://localhost:8080", null);
            String id = manager.createSandbox(agentId, "test-image", env);

            assertThat(id).isEqualTo("sb-1");
            verify(builder).env(env);
        }
    }

    @Test
    void createSandbox_nullEnv_skipsSdkEnvCall() {
        UUID agentId = UUID.randomUUID();
        try (MockedStatic<Sandbox> sandboxStatic = mockStatic(Sandbox.class)) {
            Sandbox.Builder builder = mock(Sandbox.Builder.class);
            Sandbox sandbox = mock(Sandbox.class);
            sandboxStatic.when(Sandbox::builder).thenReturn(builder);
            when(builder.connectionConfig(any())).thenReturn(builder);
            when(builder.image(anyString())).thenReturn(builder);
            when(builder.timeout(any())).thenReturn(builder);
            when(builder.build()).thenReturn(sandbox);
            when(sandbox.getId()).thenReturn("sb-1");

            OpenCodeSandboxManager manager = new OpenCodeSandboxManager("http://localhost:8080", null);
            String id = manager.createSandbox(agentId, "test-image", null);

            assertThat(id).isEqualTo("sb-1");
            verify(builder, never()).env(anyMap());
        }
    }

    @Test
    void createSandbox_emptyEnv_skipsSdkEnvCall() {
        UUID agentId = UUID.randomUUID();
        try (MockedStatic<Sandbox> sandboxStatic = mockStatic(Sandbox.class)) {
            Sandbox.Builder builder = mock(Sandbox.Builder.class);
            Sandbox sandbox = mock(Sandbox.class);
            sandboxStatic.when(Sandbox::builder).thenReturn(builder);
            when(builder.connectionConfig(any())).thenReturn(builder);
            when(builder.image(anyString())).thenReturn(builder);
            when(builder.timeout(any())).thenReturn(builder);
            when(builder.build()).thenReturn(sandbox);
            when(sandbox.getId()).thenReturn("sb-1");

            OpenCodeSandboxManager manager = new OpenCodeSandboxManager("http://localhost:8080", null);
            String id = manager.createSandbox(agentId, "test-image", Map.of());

            assertThat(id).isEqualTo("sb-1");
            verify(builder, never()).env(anyMap());
        }
    }
}

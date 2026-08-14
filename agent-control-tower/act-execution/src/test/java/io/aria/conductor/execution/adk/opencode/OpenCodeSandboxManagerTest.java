package io.aria.conductor.execution.adk.opencode;

import com.alibaba.opensandbox.sandbox.Sandbox;
import com.alibaba.opensandbox.sandbox.config.ConnectionConfig;
import com.alibaba.opensandbox.sandbox.domain.models.sandboxes.SandboxEndpoint;
import com.alibaba.opensandbox.sandbox.domain.models.sandboxes.SandboxRenewResponse;
import io.aria.conductor.execution.adk.TaskExecutionException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
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
            when(builder.skipHealthCheck(anyBoolean())).thenReturn(builder);
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
            when(builder.skipHealthCheck(anyBoolean())).thenReturn(builder);
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
            when(builder.skipHealthCheck(anyBoolean())).thenReturn(builder);
            when(builder.build()).thenReturn(sandbox);
            when(sandbox.getId()).thenReturn("sb-1");

            OpenCodeSandboxManager manager = new OpenCodeSandboxManager("http://localhost:8080", null);
            String id = manager.createSandbox(agentId, "test-image", Map.of());

            assertThat(id).isEqualTo("sb-1");
            verify(builder, never()).env(anyMap());
        }
    }

    @Test
    void connectionConfig_doesNotUseServerProxy() {
        // Regression: the server-side proxy path (/v1/sandboxes/{id}/proxy/{port}) resolves
        // the target as the sandbox container IP, which is unreachable from the server's own
        // Docker network when sandboxes run on the default bridge. Direct endpoints
        // (`<host_ip>:{mapped}/proxy/<port>`, execd built-in forwarding) are the only
        // reliable path, so useServerProxy must stay off.
        try (MockedStatic<Sandbox> sandboxStatic = mockStatic(Sandbox.class)) {
            Sandbox.Builder builder = mock(Sandbox.Builder.class);
            Sandbox sandbox = mock(Sandbox.class);
            sandboxStatic.when(Sandbox::builder).thenReturn(builder);
            when(builder.connectionConfig(any())).thenReturn(builder);
            when(builder.image(anyString())).thenReturn(builder);
            when(builder.timeout(any())).thenReturn(builder);
            when(builder.skipHealthCheck(anyBoolean())).thenReturn(builder);
            when(builder.build()).thenReturn(sandbox);
            when(sandbox.getId()).thenReturn("sb-1");

            new OpenCodeSandboxManager("http://localhost:8090", null)
                    .createSandbox(UUID.randomUUID(), "test-image", null);

            ArgumentCaptor<ConnectionConfig> captor = ArgumentCaptor.forClass(ConnectionConfig.class);
            verify(builder).connectionConfig(captor.capture());
            assertThat(captor.getValue().getUseServerProxy())
                    .as("sandbox client must use direct execd endpoints, not the server proxy")
                    .isFalse();
        }
    }

    @Test
    void createSandbox_skipsSdkHealthCheck() {
        // Regression: the SDK health check probes the raw execd endpoint whose
        // `host.docker.internal` hostname does not resolve on a Windows host, timing
        // out sandbox creation. Readiness is verified by the provider itself against
        // the rewritten endpoint instead (see {@link #getSandboxUrl}).
        try (MockedStatic<Sandbox> sandboxStatic = mockStatic(Sandbox.class)) {
            Sandbox.Builder builder = mock(Sandbox.Builder.class);
            Sandbox sandbox = mock(Sandbox.class);
            sandboxStatic.when(Sandbox::builder).thenReturn(builder);
            when(builder.connectionConfig(any())).thenReturn(builder);
            when(builder.image(anyString())).thenReturn(builder);
            when(builder.timeout(any())).thenReturn(builder);
            when(builder.skipHealthCheck(anyBoolean())).thenReturn(builder);
            when(builder.build()).thenReturn(sandbox);
            when(sandbox.getId()).thenReturn("sb-1");

            new OpenCodeSandboxManager("http://localhost:8090", null)
                    .createSandbox(UUID.randomUUID(), "test-image", null);

            verify(builder).skipHealthCheck(true);
        }
    }

    @Test
    void getSandboxUrl_prependsHttpSchemeWhenMissing() {
        // Regression: the server returns scheme-less direct endpoints like
        // `127.0.0.1:40369/proxy/4096` (execd built-in forwarding on the Docker host);
        // feeding that straight into URI.create() fails with "invalid URI scheme".
        UUID agentId = UUID.randomUUID();
        try (MockedStatic<Sandbox> sandboxStatic = mockStatic(Sandbox.class)) {
            Sandbox.Builder builder = mock(Sandbox.Builder.class);
            Sandbox sandbox = mock(Sandbox.class);
            SandboxEndpoint endpoint = mock(SandboxEndpoint.class);
            sandboxStatic.when(Sandbox::builder).thenReturn(builder);
            when(builder.connectionConfig(any())).thenReturn(builder);
            when(builder.image(anyString())).thenReturn(builder);
            when(builder.timeout(any())).thenReturn(builder);
            when(builder.skipHealthCheck(anyBoolean())).thenReturn(builder);
            when(builder.build()).thenReturn(sandbox);
            when(sandbox.getId()).thenReturn("sb-1");
            when(sandbox.getEndpoint(4096)).thenReturn(endpoint);
            when(endpoint.getEndpoint())
                    .thenReturn("127.0.0.1:40369/proxy/4096");

            OpenCodeSandboxManager manager = new OpenCodeSandboxManager("http://localhost:8090", null);
            manager.createSandbox(agentId, "test-image", null);
            String url = manager.getSandboxUrl("sb-1", 4096);

            assertThat(url).isEqualTo("http://127.0.0.1:40369/proxy/4096");
        }
    }

    @Test
    void renewSandbox_delegatesToSdk() {
        UUID agentId = UUID.randomUUID();
        try (MockedStatic<Sandbox> sandboxStatic = mockStatic(Sandbox.class)) {
            Sandbox.Builder builder = mock(Sandbox.Builder.class);
            Sandbox sandbox = mock(Sandbox.class);
            sandboxStatic.when(Sandbox::builder).thenReturn(builder);
            when(builder.connectionConfig(any())).thenReturn(builder);
            when(builder.image(anyString())).thenReturn(builder);
            when(builder.timeout(any())).thenReturn(builder);
            when(builder.skipHealthCheck(anyBoolean())).thenReturn(builder);
            when(builder.build()).thenReturn(sandbox);
            when(sandbox.getId()).thenReturn("sb-1");
            when(sandbox.renew(any(Duration.class)))
                    .thenReturn(new SandboxRenewResponse(OffsetDateTime.now().plusMinutes(30)));

            OpenCodeSandboxManager manager = new OpenCodeSandboxManager("http://localhost:8090", null);
            manager.createSandbox(agentId, "test-image", null);
            manager.renewSandbox("sb-1", Duration.ofMinutes(30));

            verify(sandbox).renew(Duration.ofMinutes(30));
        }
    }

    @Test
    void renewSandbox_unknownId_throws() {
        OpenCodeSandboxManager manager = new OpenCodeSandboxManager("http://localhost:8090", null);

        assertThatThrownBy(() -> manager.renewSandbox("missing", Duration.ofMinutes(30)))
                .isInstanceOf(TaskExecutionException.class)
                .satisfies(e -> assertThat(((TaskExecutionException) e).cause())
                        .isEqualTo(TaskExecutionException.Cause.SANDBOX_UNAVAILABLE));
    }

    @Test
    void getSandboxUrl_keepsExistingScheme() {
        UUID agentId = UUID.randomUUID();
        try (MockedStatic<Sandbox> sandboxStatic = mockStatic(Sandbox.class)) {
            Sandbox.Builder builder = mock(Sandbox.Builder.class);
            Sandbox sandbox = mock(Sandbox.class);
            SandboxEndpoint endpoint = mock(SandboxEndpoint.class);
            sandboxStatic.when(Sandbox::builder).thenReturn(builder);
            when(builder.connectionConfig(any())).thenReturn(builder);
            when(builder.image(anyString())).thenReturn(builder);
            when(builder.timeout(any())).thenReturn(builder);
            when(builder.skipHealthCheck(anyBoolean())).thenReturn(builder);
            when(builder.build()).thenReturn(sandbox);
            when(sandbox.getId()).thenReturn("sb-1");
            when(sandbox.getEndpoint(4096)).thenReturn(endpoint);
            when(endpoint.getEndpoint()).thenReturn("http://192.168.1.10:4096");

            OpenCodeSandboxManager manager = new OpenCodeSandboxManager("http://localhost:8090", null);
            manager.createSandbox(agentId, "test-image", null);
            String url = manager.getSandboxUrl("sb-1", 4096);

            assertThat(url).isEqualTo("http://192.168.1.10:4096");
        }
    }
}

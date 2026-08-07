package io.aria.conductor.execution.adk.opencode;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Configuration binding tests for {@link OpenCodeProperties}.
 *
 * <p>Regression: the docker-compose backend service used the env var
 * {@code OPENSANDBOX_SERVER_URL}, which does NOT bind to
 * {@code opencode.sandbox-server-url} (Spring relaxed binding requires the
 * {@code opencode} prefix). The backend therefore connected to its own port 8080
 * and every sandbox creation failed with HTTP 404. The correct env var is
 * {@code OPENCODE_SANDBOX_SERVER_URL}.
 *
 * <p>Uses {@link SystemEnvironmentPropertySource} to reproduce the relaxed
 * binding semantics of real OS environment variables (upper-case + underscore
 * names are canonicalized to dotted property names).
 */
class OpenCodePropertiesBindingTest {

    private OpenCodeProperties bind(Map<String, Object> envVars) {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(
                new SystemEnvironmentPropertySource("test-env", envVars));
        ConfigurationPropertySources.attach(environment);
        Binder binder = new Binder(ConfigurationPropertySources.get(environment));
        return binder.bind("opencode", Bindable.of(OpenCodeProperties.class))
                .orElseGet(OpenCodeProperties::new);
    }

    @Test
    void envOpenCodeSandboxServerUrl_bindsToSandboxServerUrl() {
        Map<String, Object> env = new HashMap<>();
        env.put("OPENCODE_SANDBOX_SERVER_URL", "http://localhost:8090");

        OpenCodeProperties props = bind(env);

        assertThat(props.getSandboxServerUrl()).isEqualTo("http://localhost:8090");
    }

    @Test
    void envOpenCodeSandboxApiKey_bindsToSandboxApiKey() {
        Map<String, Object> env = new HashMap<>();
        env.put("OPENCODE_SANDBOX_API_KEY", "secret-key");

        OpenCodeProperties props = bind(env);

        assertThat(props.getSandboxApiKey()).isEqualTo("secret-key");
    }

    @Test
    void envOpenSandboxServerUrl_doesNotBind_keepsDefault() {
        // Prefix mismatch: OPENSANDBOX_SERVER_URL belongs to opensandbox.*, not opencode.*
        Map<String, Object> env = new HashMap<>();
        env.put("OPENSANDBOX_SERVER_URL", "http://localhost:9999");

        OpenCodeProperties props = bind(env);

        assertThat(props.getSandboxServerUrl())
                .as("OPENSANDBOX_SERVER_URL must NOT bind to opencode.sandbox-server-url")
                .isEqualTo("http://localhost:8080");
    }

    @Test
    void envOpenSandboxApiKey_doesNotBind_keepsDefault() {
        Map<String, Object> env = new HashMap<>();
        env.put("OPENSANDBOX_API_KEY", "secret-key");

        OpenCodeProperties props = bind(env);

        assertThat(props.getSandboxApiKey())
                .as("OPENSANDBOX_API_KEY must NOT bind to opencode.sandbox-api-key")
                .isEmpty();
    }
}

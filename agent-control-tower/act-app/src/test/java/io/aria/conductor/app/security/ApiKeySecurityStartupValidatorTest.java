package io.aria.conductor.app.security;

import io.aria.conductor.app.security.ApiKeySecurityConfig.SecurityStartupValidator;
import io.aria.conductor.execution.adk.opencode.OpenCodeProperties;
import org.junit.jupiter.api.Test;

import static io.aria.conductor.app.security.SecurityPropertiesTest.sha256Hex;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for the auth startup gate (AC5) and sandbox credential provisioning (proposed
 * solution item 4).
 */
class ApiKeySecurityStartupValidatorTest {

    private final OpenCodeProperties openCodeProperties = new OpenCodeProperties();

    private SecurityStartupValidator validator(SecurityProperties props) {
        return new SecurityStartupValidator(props, openCodeProperties);
    }

    @Test
    void disabledAuthStartsWithoutAnyKey() {
        SecurityProperties props = new SecurityProperties();
        props.setEnabled(false);
        props.setApiKeys("");
        validator(props).afterPropertiesSet();
    }

    @Test
    void enabledAuthWithNoKeyFailsFastWithClearMessage() {
        SecurityProperties props = new SecurityProperties();
        props.setEnabled(true);
        props.setApiKeys("   ");
        assertThatThrownBy(() -> validator(props).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.security.enabled=true")
                .hasMessageContaining("mariadb")
                .hasMessageContaining("AUTH_API_KEYS");
    }

    @Test
    void enabledAuthWithKeyStarts() {
        SecurityProperties props = new SecurityProperties();
        props.setEnabled(true);
        props.setApiKeys("some-key");
        validator(props).afterPropertiesSet();
    }

    @Test
    void enabledAuthWithMalformedHashFailsFast() {
        SecurityProperties props = new SecurityProperties();
        props.setEnabled(true);
        props.setApiKeys("sha256:zzz");
        assertThatThrownBy(() -> validator(props).afterPropertiesSet())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sha256:");
    }

    @Test
    void provisionsFirstPlaintextKeyToOpenCodeSandboxEnvWhenEnabled() {
        SecurityProperties props = new SecurityProperties();
        props.setEnabled(true);
        props.setApiKeys("agent-key-1, agent-key-2");
        openCodeProperties.getSandboxEnv().put("DEEPSEEK_API_KEY", "llm-secret");

        validator(props).afterPropertiesSet();

        assertThat(openCodeProperties.getSandboxEnv()).containsEntry("ARIA_API_KEY", "agent-key-1");
        // Pre-existing sandbox env vars are untouched.
        assertThat(openCodeProperties.getSandboxEnv()).containsEntry("DEEPSEEK_API_KEY", "llm-secret");
    }

    @Test
    void doesNotOverwriteExplicitlyConfiguredAriaApiKey() {
        SecurityProperties props = new SecurityProperties();
        props.setEnabled(true);
        props.setApiKeys("agent-key-1");
        openCodeProperties.getSandboxEnv().put("ARIA_API_KEY", "explicit");

        validator(props).afterPropertiesSet();

        assertThat(openCodeProperties.getSandboxEnv()).containsEntry("ARIA_API_KEY", "explicit");
    }

    @Test
    void doesNotProvisionWhenAuthDisabledOrHashOnly() {
        SecurityProperties disabled = new SecurityProperties();
        disabled.setEnabled(false);
        disabled.setApiKeys("some-key");
        validator(disabled).afterPropertiesSet();
        assertThat(openCodeProperties.getSandboxEnv()).doesNotContainKey("ARIA_API_KEY");

        SecurityProperties hashOnly = new SecurityProperties();
        hashOnly.setEnabled(true);
        hashOnly.setApiKeys("sha256:" + sha256Hex("only-hash"));
        validator(hashOnly).afterPropertiesSet();
        assertThat(openCodeProperties.getSandboxEnv()).doesNotContainKey("ARIA_API_KEY");
    }
}

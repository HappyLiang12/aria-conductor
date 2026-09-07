package io.aria.conductor.execution.mcp;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class McpPropertiesTest {

    @Test
    void defaults_matchSpec() {
        McpProperties props = new McpProperties();
        assertThat(props.isEnabled()).isTrue();
        assertThat(props.getAuthMode()).isEqualTo("none");
        assertThat(props.isDebug()).isFalse();
        assertThat(props.getToken()).isEmpty();
        assertThat(props.getSandboxHostAddress()).isEmpty();
        assertThat(props.getPort()).isEqualTo(8080);
        // Startup-fragility fix: the execd-readiness window is configurable and the
        // default was raised from the hardcoded ~5s (10 x 500ms) to 15s — cold first
        // boots routinely exceeded 5s, silently skipping the mcp block.
        assertThat(props.getExecdReadyTimeoutMs()).isEqualTo(15000);
        assertThat(props.isTokenMode()).isFalse();
    }

    @Test
    void tokenMode_acceptsConfiguration() {
        McpProperties props = new McpProperties();
        props.setAuthMode("token");
        props.setToken("secret-1");
        assertThat(props.getAuthMode()).isEqualTo("token");
        assertThat(props.isTokenMode()).isTrue();
    }

    @Test
    void tokenMode_isCaseInsensitive_andMalformedFailsOpenToNone() {
        McpProperties props = new McpProperties();
        props.setAuthMode("TOKEN");
        assertThat(props.isTokenMode()).isTrue();

        McpProperties malformed = new McpProperties();
        malformed.setAuthMode("tokn");
        // Documented fail-open semantic (spec §6): anything not "token" behaves as none.
        assertThat(malformed.isTokenMode()).isFalse();
    }
}

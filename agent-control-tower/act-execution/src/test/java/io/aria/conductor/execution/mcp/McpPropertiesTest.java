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

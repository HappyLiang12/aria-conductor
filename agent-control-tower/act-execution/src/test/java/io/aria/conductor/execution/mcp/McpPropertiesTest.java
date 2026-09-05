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
    }

    @Test
    void tokenMode_acceptsConfiguration() {
        McpProperties props = new McpProperties();
        props.setAuthMode("token");
        props.setToken("secret-1");
        assertThat(props.getAuthMode()).isEqualTo("token");
    }
}

package io.aria.conductor.aria.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AriaPropertiesTest {

    @Test
    void defaultConstructor_createsWithDefaults() {
        AriaProperties props = new AriaProperties();

        assertThat(props.getSystemPrompt()).contains("Aria");
    }
}

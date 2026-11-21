package io.aria.conductor.aria.prompt;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AriaGlassesPromptTest {

    @Test
    void thePolicyCarriesTheGlassesRules() {
        assertThat(AriaPromptCatalog.POLICY)
                .contains("[glasses observation]")
                .contains("ar_render")
                .contains("Traditional Chinese")
                .contains("never invent a trackId");
    }
}

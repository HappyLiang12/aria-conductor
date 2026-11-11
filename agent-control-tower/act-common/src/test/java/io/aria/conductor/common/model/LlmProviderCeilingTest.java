package io.aria.conductor.common.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LlmProviderCeilingTest {

    @Test
    void newProvidersDefaultToInternal() {
        LlmProvider provider = LlmProvider.builder()
                .name("local").type(LlmProviderType.OPENAI).build();

        assertThat(provider.getSensitivityCeiling()).isEqualTo(Sensitivity.INTERNAL);
    }

    @Test
    void ceilingCanBeRaisedExplicitly() {
        LlmProvider provider = LlmProvider.builder()
                .name("local").type(LlmProviderType.OPENAI)
                .sensitivityCeiling(Sensitivity.RESTRICTED).build();

        assertThat(provider.getSensitivityCeiling()).isEqualTo(Sensitivity.RESTRICTED);
    }
}

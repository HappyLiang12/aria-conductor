package io.aria.conductor.execution.context;

import io.aria.conductor.agent.repository.LlmProviderRepository;
import io.aria.conductor.common.model.LlmProvider;
import io.aria.conductor.common.model.Sensitivity;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The provider ceiling is resolved on the required assembly path, so every
 * failure mode must fail closed to {@link Sensitivity#INTERNAL}: an unknown
 * provider, no active provider, or a lookup that throws may narrow the
 * envelope but never widen it and never fail the run.
 */
class ProviderCeilingResolverTest {

    private final LlmProviderRepository providers = mock(LlmProviderRepository.class);
    private final ProviderCeilingResolver resolver = new ProviderCeilingResolver(providers);

    @Test
    void resolvesTheNamedProvidersCeiling() {
        UUID providerId = UUID.randomUUID();
        when(providers.findById(providerId)).thenReturn(Optional.of(provider(Sensitivity.CONFIDENTIAL)));

        assertThat(resolver.ceilingFor(providerId)).isEqualTo(Sensitivity.CONFIDENTIAL);
    }

    @Test
    void anUnknownProviderFallsBackToInternal() {
        UUID providerId = UUID.randomUUID();
        when(providers.findById(providerId)).thenReturn(Optional.empty());

        assertThat(resolver.ceilingFor(providerId)).isEqualTo(Sensitivity.INTERNAL);
    }

    @Test
    void theActiveProviderCeilingIsUsedWhenNoProviderIsNamed() {
        when(providers.findByActiveTrue()).thenReturn(Optional.of(provider(Sensitivity.PUBLIC)));

        assertThat(resolver.ceilingFor(null)).isEqualTo(Sensitivity.PUBLIC);
    }

    @Test
    void aFailedLookupFailsClosedToInternal() {
        when(providers.findByActiveTrue()).thenThrow(new IllegalStateException("two active providers"));

        assertThat(resolver.ceilingFor(null)).isEqualTo(Sensitivity.INTERNAL);
    }

    private static LlmProvider provider(Sensitivity ceiling) {
        LlmProvider provider = new LlmProvider();
        provider.setSensitivityCeiling(ceiling);
        return provider;
    }
}

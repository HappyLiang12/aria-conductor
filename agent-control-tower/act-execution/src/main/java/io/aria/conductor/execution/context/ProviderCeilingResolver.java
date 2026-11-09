package io.aria.conductor.execution.context;

import io.aria.conductor.agent.repository.LlmProviderRepository;
import io.aria.conductor.common.model.LlmProvider;
import io.aria.conductor.common.model.Sensitivity;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * The sensitivity ceiling of the provider an envelope is assembled for (spec
 * §8.2 step 1). The default is {@link Sensitivity#INTERNAL}; a provider that is
 * unknown, absent or stores no ceiling fails closed to the default rather than
 * opening the envelope to every section.
 */
@Component
public class ProviderCeilingResolver {

    private final LlmProviderRepository providers;

    public ProviderCeilingResolver(LlmProviderRepository providers) {
        this.providers = providers;
    }

    public Sensitivity ceilingFor(UUID providerId) {
        if (providerId != null) {
            return providers.findById(providerId)
                    .map(LlmProvider::getSensitivityCeiling)
                    .orElse(Sensitivity.INTERNAL);
        }
        return providers.findByActiveTrue()
                .map(LlmProvider::getSensitivityCeiling)
                .orElse(Sensitivity.INTERNAL);
    }
}

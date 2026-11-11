package io.aria.conductor.execution.context;

import io.aria.conductor.agent.repository.LlmProviderRepository;
import io.aria.conductor.common.model.LlmProvider;
import io.aria.conductor.common.model.Sensitivity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * The sensitivity ceiling of the provider an envelope is assembled for (spec
 * §8.2 step 1). Every failure mode fails closed to
 * {@link Sensitivity#INTERNAL}: an unknown provider, no active provider, or a
 * lookup that throws narrows the envelope rather than widening it — and, since
 * this runs on the required assembly path, never fails the run.
 */
@Slf4j
@Component
public class ProviderCeilingResolver {

    private final LlmProviderRepository providers;

    public ProviderCeilingResolver(LlmProviderRepository providers) {
        this.providers = providers;
    }

    public Sensitivity ceilingFor(UUID providerId) {
        try {
            if (providerId != null) {
                return providers.findById(providerId)
                        .map(LlmProvider::getSensitivityCeiling)
                        .filter(ceiling -> ceiling != null)
                        .orElse(Sensitivity.INTERNAL);
            }
            return providers.findByActiveTrue()
                    .map(LlmProvider::getSensitivityCeiling)
                    .filter(ceiling -> ceiling != null)
                    .orElse(Sensitivity.INTERNAL);
        } catch (RuntimeException e) {
            log.warn("Could not resolve the provider sensitivity ceiling for {}; failing closed to {}: {}",
                    providerId, Sensitivity.INTERNAL, e.getMessage());
            return Sensitivity.INTERNAL;
        }
    }
}

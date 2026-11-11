package io.aria.conductor.aria.prompt;

import io.aria.conductor.common.AriaConstants;
import io.aria.conductor.common.context.ContextContributor;
import io.aria.conductor.common.context.ContextRequest;
import io.aria.conductor.common.context.ContextSection;
import io.aria.conductor.common.model.Sensitivity;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;

/**
 * Contributes Aria's managed prompt as the required {@code policy.aria} section
 * (spec §8.1, P1). It applies to the Aria agent only; every other agent falls
 * back to its own configured policy.
 */
@Component
public class AriaPromptContributor implements ContextContributor {

    static final int ORDER = 20;

    @Override
    public String id() {
        return AriaPromptCatalog.SECTION_ID;
    }

    @Override
    public int order() {
        return ORDER;
    }

    @Override
    public boolean appliesTo(ContextRequest request) {
        return isAria(request);
    }

    @Override
    public boolean required() {
        return true;
    }

    @Override
    public List<ContextSection> contribute(ContextRequest request) {
        if (!isAria(request)) {
            return List.of();
        }
        return List.of(new ContextSection(
                AriaPromptCatalog.SECTION_ID,
                "Policy",
                AriaPromptCatalog.POLICY,
                Sensitivity.INTERNAL,
                List.of(AriaPromptCatalog.SOURCE_ID),
                false,
                null));
    }

    private static boolean isAria(ContextRequest request) {
        return request != null && Objects.equals(AriaConstants.ARIA_AGENT_ID, request.agentId());
    }
}

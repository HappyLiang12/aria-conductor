package io.aria.conductor.execution.context.contributor;

import io.aria.conductor.common.context.ContextContributor;
import io.aria.conductor.common.context.ContextRequest;
import io.aria.conductor.common.context.ContextSection;
import io.aria.conductor.common.model.Sensitivity;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * The skill directive the transport carries with the request (spec §8.1,
 * "Policy"): a chat adapter's active-skill policy text. A request without one
 * yields no section.
 */
@Component
public class ClientPolicyContextContributor implements ContextContributor {

    @Override
    public String id() {
        return "policy.client";
    }

    @Override
    public int order() {
        return 21;
    }

    @Override
    public boolean appliesTo(ContextRequest request) {
        return request.clientPolicy() != null && !request.clientPolicy().isBlank();
    }

    @Override
    public List<ContextSection> contribute(ContextRequest request) {
        if (request.clientPolicy() == null || request.clientPolicy().isBlank()) {
            return List.of();
        }
        return List.of(new ContextSection(id(), "Policy", request.clientPolicy(),
                Sensitivity.INTERNAL, List.of(), false, null));
    }
}

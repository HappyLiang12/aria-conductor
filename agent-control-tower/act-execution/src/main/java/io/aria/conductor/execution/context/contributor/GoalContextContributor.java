package io.aria.conductor.execution.context.contributor;

import io.aria.conductor.common.context.ContextContributor;
import io.aria.conductor.common.context.ContextRequest;
import io.aria.conductor.common.context.ContextSection;
import io.aria.conductor.common.model.Sensitivity;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * The current request, rendered once (spec §8.1, "Goal"). The request is the
 * run's prompt seed and enters as this section and never again as the last
 * history message.
 */
@Component
public class GoalContextContributor implements ContextContributor {

    @Override
    public String id() {
        return "goal";
    }

    @Override
    public int order() {
        return 30;
    }

    @Override
    public boolean required() {
        return true;
    }

    @Override
    public List<ContextSection> contribute(ContextRequest request) {
        return List.of(new ContextSection(id(), "Goal", request.goal(),
                Sensitivity.INTERNAL, List.of(), false, null));
    }
}

package io.aria.conductor.execution.context.contributor;

import io.aria.conductor.common.context.ContextContributor;
import io.aria.conductor.common.context.ContextRequest;
import io.aria.conductor.common.context.ContextSection;
import io.aria.conductor.common.model.Sensitivity;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Where the run works (spec §8.1, "Workspace"): the workspace reference the
 * caller resolved (for example {@code WORKTREE@main}), never a local path. A
 * run without one yields no section.
 */
@Component
public class WorkspaceContextContributor implements ContextContributor {

    @Override
    public String id() {
        return "workspace";
    }

    @Override
    public int order() {
        return 80;
    }

    @Override
    public boolean appliesTo(ContextRequest request) {
        return request.workspaceRef() != null && !request.workspaceRef().isBlank();
    }

    @Override
    public List<ContextSection> contribute(ContextRequest request) {
        if (request.workspaceRef() == null || request.workspaceRef().isBlank()) {
            return List.of();
        }
        return List.of(new ContextSection(id(), "Workspace", request.workspaceRef(),
                Sensitivity.INTERNAL, List.of(), false, null));
    }
}

package io.aria.conductor.execution.context.contributor;

import io.aria.conductor.agent.repository.RunRepository;
import io.aria.conductor.common.context.ContextContributor;
import io.aria.conductor.common.context.ContextRequest;
import io.aria.conductor.common.context.ContextSection;
import io.aria.conductor.common.model.ApprovalStatus;
import io.aria.conductor.common.model.RunStatus;
import io.aria.conductor.common.model.Sensitivity;
import io.aria.conductor.execution.repository.ApprovalRepository;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Live domain state read from the domain tables (spec §7, "Fact types that are
 * current state are not mirrored"): how many runs are executing and how many
 * approvals await the operator. Never stored in memory.
 */
@Component
public class StateContextContributor implements ContextContributor {

    private final RunRepository runs;
    private final ApprovalRepository approvals;

    public StateContextContributor(RunRepository runs, ApprovalRepository approvals) {
        this.runs = runs;
        this.approvals = approvals;
    }

    @Override
    public String id() {
        return "state";
    }

    @Override
    public int order() {
        return 70;
    }

    @Override
    public List<ContextSection> contribute(ContextRequest request) {
        String content = "running runs: " + runs.countByStatus(RunStatus.RUNNING)
                + "\npending approvals: " + approvals.countByStatus(ApprovalStatus.PENDING);
        return List.of(new ContextSection(id(), "State", content,
                Sensitivity.INTERNAL, List.of(), false, null));
    }
}

package io.aria.conductor.execution.runtime;

import io.aria.conductor.agent.service.WorkflowService;
import io.aria.conductor.common.model.WorkflowChain;
import io.aria.conductor.common.model.WorkflowStep;
import io.aria.conductor.execution.git.GitHandoffMetadata;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * F15: gives a workflow's BA step a real checkout instead of an empty scratch
 * directory.
 *
 * <p>The SDD template carries the target repository as the {@code repoUrl}
 * instantiation parameter on the chain ({@link GitHandoffMetadata#KEY_REPO_URL}).
 * A BA run that resolves to a scratch workspace is bound to a shallow checkout
 * of that repository at its default branch, so spec work reads an isolated copy
 * of the target revision rather than whatever branch the operator happens to
 * have checked out. The running agent's prompt already names the checkout
 * relatively ({@code repo}, from {@code aria.workflows.workspace-root}); with
 * this binding that relative root is no longer empty.
 *
 * <p>Only the BA step is bound. Dev/QA runs resolve to their own worktree or
 * sandbox workspace where the SDD branch already exists, and a default-branch
 * clone there would point them at the wrong revision.
 *
 * <p>Without a chain, a non-BA step, or a missing {@code repoUrl}, the binding
 * is empty and scratch provisioning is unchanged.
 */
@Component
@RequiredArgsConstructor
public class WorkflowScratchBinding implements ScratchWorkspaceProvisioner {

    private final WorkflowService workflowService;

    @Override
    public Optional<RepositoryBinding> bindingFor(ExecutionSpec spec) {
        if (spec == null || spec.runId() == null) {
            return Optional.empty();
        }
        WorkflowChain chain = workflowService.findChainByRunId(spec.runId());
        if (chain == null) {
            return Optional.empty();
        }
        int stepIndex = workflowService.findStepIndex(chain, spec.runId());
        if (stepIndex < 0) {
            return Optional.empty();
        }
        WorkflowStep step = workflowService.stepAt(chain, stepIndex);
        if (step == null || step.getKind() != WorkflowStep.StepKind.BA) {
            return Optional.empty();
        }
        String repoUrl = GitHandoffMetadata.parse(chain.getTemplateParams())
                .get(GitHandoffMetadata.KEY_REPO_URL);
        if (repoUrl == null || repoUrl.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(new RepositoryBinding(repoUrl, null));
    }
}

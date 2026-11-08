package io.aria.conductor.execution.runtime;

import io.aria.conductor.agent.service.WorkflowService;
import io.aria.conductor.common.model.WorkflowChain;
import io.aria.conductor.common.model.WorkflowStep;
import io.aria.conductor.common.runtime.ExecutionMode;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * F15: only a workflow's BA step is bound to a checkout of the chain's
 * {@code repoUrl}; every other shape stays an empty scratch.
 */
class WorkflowScratchBindingTest {

    private final WorkflowService workflowService = mock(WorkflowService.class);
    private final WorkflowScratchBinding binding = new WorkflowScratchBinding(workflowService);

    private static final String REPO_URL = "https://github.com/acme/aria-conductor.git";

    @Test
    void baStepWithRepoUrl_bindsTheRepositoryAtTheDefaultBranch() {
        UUID runId = UUID.randomUUID();
        WorkflowChain chain = chainWithRepoUrl(REPO_URL);
        WorkflowStep ba = WorkflowStep.builder().kind(WorkflowStep.StepKind.BA).runId(runId).build();
        when(workflowService.findChainByRunId(runId)).thenReturn(chain);
        when(workflowService.findStepIndex(chain, runId)).thenReturn(0);
        when(workflowService.stepAt(chain, 0)).thenReturn(ba);

        Optional<ScratchWorkspaceProvisioner.RepositoryBinding> result = binding.bindingFor(spec(runId));

        assertThat(result).contains(new ScratchWorkspaceProvisioner.RepositoryBinding(REPO_URL, null));
    }

    @Test
    void devOrQaStep_isNotBound() {
        UUID runId = UUID.randomUUID();
        WorkflowChain chain = chainWithRepoUrl(REPO_URL);
        WorkflowStep qa = WorkflowStep.builder().kind(WorkflowStep.StepKind.QA).runId(runId).build();
        when(workflowService.findChainByRunId(runId)).thenReturn(chain);
        when(workflowService.findStepIndex(chain, runId)).thenReturn(1);
        when(workflowService.stepAt(chain, 1)).thenReturn(qa);

        assertThat(binding.bindingFor(spec(runId))).isEmpty();
    }

    @Test
    void baStepWithoutRepoUrl_isNotBound() {
        UUID runId = UUID.randomUUID();
        WorkflowChain chain = chainWithRepoUrl(null);
        WorkflowStep ba = WorkflowStep.builder().kind(WorkflowStep.StepKind.BA).runId(runId).build();
        when(workflowService.findChainByRunId(runId)).thenReturn(chain);
        when(workflowService.findStepIndex(chain, runId)).thenReturn(0);
        when(workflowService.stepAt(chain, 0)).thenReturn(ba);

        assertThat(binding.bindingFor(spec(runId))).isEmpty();
    }

    @Test
    void nonWorkflowRun_isNotBound() {
        UUID runId = UUID.randomUUID();
        when(workflowService.findChainByRunId(runId)).thenReturn(null);

        assertThat(binding.bindingFor(spec(runId))).isEmpty();
    }

    private static WorkflowChain chainWithRepoUrl(String repoUrl) {
        String params = repoUrl == null ? "{}" : "{\"repoUrl\":\"" + repoUrl + "\"}";
        return WorkflowChain.builder()
                .id(UUID.randomUUID())
                .name("sdd")
                .status(WorkflowChain.Status.RUNNING)
                .currentStepIndex(0)
                .stepsJson("[]")
                .templateParams(params)
                .build();
    }

    private static ExecutionSpec spec(UUID runId) {
        return new ExecutionSpec(runId, UUID.randomUUID(), "qoder", ExecutionMode.HOST,
                null, null, null, null);
    }
}

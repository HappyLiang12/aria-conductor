package io.aria.conductor.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.aria.conductor.agent.dto.CreateRunRequest;
import io.aria.conductor.agent.dto.CreateWorkflowRequest;
import io.aria.conductor.agent.dto.RunResponse;
import io.aria.conductor.agent.repository.WorkflowChainRepository;
import io.aria.conductor.common.model.WorkflowChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * F11: the SDD prompts name their checkout root with the {@code {workspaceRoot}}
 * placeholder. {@link WorkflowService} resolves it at step start from the
 * configured run workspace root; when none is configured it substitutes the
 * literal relative directory {@code repo} under the step working directory and
 * says so in the prompt. The absolute {@code /workspace} path of the sandbox
 * must never reach a HOST run.
 */
@ExtendWith(MockitoExtension.class)
class WorkflowServiceWorkspaceRootTest {

    private static final UUID AGENT_ID = UUID.randomUUID();

    @Mock
    WorkflowChainRepository workflowChainRepository;

    @Mock
    RunService runService;

    @Mock
    ApplicationEventPublisher eventPublisher;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        when(workflowChainRepository.save(any())).thenAnswer(inv -> {
            WorkflowChain chain = inv.getArgument(0);
            if (chain.getId() == null) chain.setId(UUID.randomUUID());
            if (chain.getCreatedAt() == null) chain.setCreatedAt(Instant.now());
            return chain;
        });
        when(runService.createRun(any())).thenReturn(RunResponse.builder()
                .id(UUID.randomUUID()).agentId(AGENT_ID).build());
    }

    @Test
    void unconfiguredWorkspaceRoot_substitutesRelativeRepoAndSaysSo() {
        WorkflowService service = serviceWithRoot("");

        String promptSeed = startAndCaptureSeed(service,
                "git clone --branch {branchName} {repoUrl} {workspaceRoot}; read {workspaceRoot}/spec/spec.md");

        assertThat(promptSeed).doesNotContain("{workspaceRoot");
        assertThat(promptSeed).contains("git clone --branch {branchName} {repoUrl} repo");
        assertThat(promptSeed).contains("repo/spec/spec.md");
        // "say so": the substitution explains that the relative path is rooted at the
        // step working directory, so the agent does not guess an absolute location.
        assertThat(promptSeed).contains("relative");
        assertThat(promptSeed).contains("current working directory");
        assertThat(promptSeed).doesNotContain("/workspace");
    }

    @Test
    void configuredWorkspaceRoot_isSubstitutedVerbatimWithoutTheRelativeNote() {
        WorkflowService service = serviceWithRoot("C:/runs/chain-1/repo");

        String promptSeed = startAndCaptureSeed(service,
                "git clone --branch {branchName} {repoUrl} {workspaceRoot}; read {workspaceRoot}/spec/spec.md");

        assertThat(promptSeed).contains("git clone --branch {branchName} {repoUrl} C:/runs/chain-1/repo");
        assertThat(promptSeed).contains("C:/runs/chain-1/repo/spec/spec.md");
        assertThat(promptSeed).doesNotContain("current working directory");
    }

    @Test
    void promptWithoutPlaceholder_isLeftUntouched() {
        WorkflowService service = serviceWithRoot("C:/runs/chain-1/repo");

        String promptSeed = startAndCaptureSeed(service, "run the tests and report the output");

        assertThat(promptSeed).isEqualTo("run the tests and report the output");
    }

    @Test
    void previousOutputPlaceholder_isStillSubstitutedAlongsideWorkspaceRoot() {
        WorkflowService service = serviceWithRoot("repo");
        CreateWorkflowRequest request = CreateWorkflowRequest.builder()
                .name("two steps")
                .steps(List.of(
                        step("first step"),
                        step("previous: {previousOutput} in {workspaceRoot}")))
                .build();

        service.createAndStart(request);

        // The second step is started by advanceWorkflow, which carries the first
        // step's output into {previousOutput}.
        ArgumentCaptor<WorkflowChain> chainCaptor = ArgumentCaptor.forClass(WorkflowChain.class);
        verify(workflowChainRepository, org.mockito.Mockito.atLeastOnce()).save(chainCaptor.capture());
        WorkflowChain saved = chainCaptor.getValue();
        when(workflowChainRepository.findById(saved.getId())).thenReturn(java.util.Optional.of(saved));

        service.advanceWorkflow(saved.getId(), 0, "first output");

        ArgumentCaptor<CreateRunRequest> runCaptor = ArgumentCaptor.forClass(CreateRunRequest.class);
        verify(runService, org.mockito.Mockito.atLeast(2)).createRun(runCaptor.capture());
        List<CreateRunRequest> runs = runCaptor.getAllValues();
        String secondSeed = runs.get(runs.size() - 1).getPromptSeed();
        assertThat(secondSeed).contains("previous: first output in repo");
        assertThat(secondSeed).doesNotContain("{previousOutput}").doesNotContain("{workspaceRoot");
    }

    private WorkflowService serviceWithRoot(String workspaceRoot) {
        return new WorkflowService(workflowChainRepository, runService, objectMapper,
                eventPublisher, workspaceRoot);
    }

    private String startAndCaptureSeed(WorkflowService service, String prompt) {
        CreateWorkflowRequest request = CreateWorkflowRequest.builder()
                .name("prompt substitution")
                .steps(List.of(step(prompt)))
                .build();

        service.createAndStart(request);

        ArgumentCaptor<CreateRunRequest> captor = ArgumentCaptor.forClass(CreateRunRequest.class);
        verify(runService).createRun(captor.capture());
        return captor.getValue().getPromptSeed();
    }

    private CreateWorkflowRequest.StepDef step(String prompt) {
        return CreateWorkflowRequest.StepDef.builder()
                .agentId(AGENT_ID)
                .promptTemplate(prompt)
                .maxIterations(3)
                .build();
    }
}

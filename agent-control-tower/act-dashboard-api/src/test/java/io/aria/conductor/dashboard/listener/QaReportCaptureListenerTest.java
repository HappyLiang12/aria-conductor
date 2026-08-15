package io.aria.conductor.dashboard.listener;

import io.aria.conductor.agent.repository.WorkflowChainRepository;
import io.aria.conductor.agent.service.WorkflowService;
import io.aria.conductor.common.event.WorkflowAdvancedEvent;
import io.aria.conductor.common.model.WorkflowChain;
import io.aria.conductor.common.model.WorkflowStep;
import io.aria.conductor.dashboard.report.ReportArtifact;
import io.aria.conductor.dashboard.report.ReportService;
import io.aria.conductor.execution.adk.opencode.OpenCodeAdkProvider;
import io.aria.conductor.execution.git.GitBranchService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class QaReportCaptureListenerTest {

    private static final String REPO_URL = "https://github.com/acme/repo.git";
    private static final UUID ARTIFACT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private WorkflowChainRepository chainRepository;
    private WorkflowService workflowService;
    private GitBranchService gitBranchService;
    private OpenCodeAdkProvider openCodeAdkProvider;
    private ReportService reportService;
    private QaReportCaptureListener listener;

    private UUID chainId;

    @BeforeEach
    void setUp() {
        chainRepository = mock(WorkflowChainRepository.class);
        workflowService = mock(WorkflowService.class);
        gitBranchService = mock(GitBranchService.class);
        openCodeAdkProvider = mock(OpenCodeAdkProvider.class);
        reportService = mock(ReportService.class);
        listener = new QaReportCaptureListener(chainRepository, workflowService,
                gitBranchService, openCodeAdkProvider, reportService);
        chainId = UUID.randomUUID();
    }

    @Test
    void onCompletedEvent_withBranchReport_capturesAndAttaches() {
        WorkflowChain chain = gitChain();
        when(chainRepository.findById(chainId)).thenReturn(Optional.of(chain));
        when(gitBranchService.getFile(REPO_URL, "sdd/" + chainId, "qa_report.md"))
                .thenReturn(Optional.of("# QA Report\n\nAll checks passed."));
        when(reportService.capture("SDD QA Report", chainId.toString(), "# QA Report\n\nAll checks passed."))
                .thenReturn(ReportArtifact.builder()
                        .id(ARTIFACT_ID.toString())
                        .title("SDD QA Report")
                        .owner(chainId.toString())
                        .version(1)
                        .status("GENERATED")
                        .build());

        listener.onWorkflowAdvanced(completedEvent());

        verify(reportService).capture("SDD QA Report", chainId.toString(), "# QA Report\n\nAll checks passed.");
        assertThat(chain.getReportArtifactId()).isEqualTo(ARTIFACT_ID);
        verify(chainRepository).save(chain);
        // Branch hit, so the sandbox fallback is never attempted.
        verify(openCodeAdkProvider, never()).runSandboxCommand(any(), anyString());
    }

    @Test
    void onCompletedEvent_withoutBranchReport_fallsBackToSandbox() {
        WorkflowChain chain = gitChain();
        UUID qaAgentId = UUID.randomUUID();
        when(chainRepository.findById(chainId)).thenReturn(Optional.of(chain));
        when(gitBranchService.getFile(REPO_URL, "sdd/" + chainId, "qa_report.md"))
                .thenReturn(Optional.empty());
        when(workflowService.deserializeSteps(chain.getStepsJson())).thenReturn(List.of(
                WorkflowStep.builder().kind(WorkflowStep.StepKind.QA).agentId(qaAgentId).build()));
        when(openCodeAdkProvider.runSandboxCommand(qaAgentId, "cat /workspace/qa_report.md"))
                .thenReturn("sandbox report content");
        when(reportService.capture("SDD QA Report", chainId.toString(), "sandbox report content"))
                .thenReturn(ReportArtifact.builder()
                        .id(ARTIFACT_ID.toString())
                        .title("SDD QA Report")
                        .owner(chainId.toString())
                        .version(1)
                        .status("GENERATED")
                        .build());

        listener.onWorkflowAdvanced(completedEvent());

        verify(openCodeAdkProvider).runSandboxCommand(qaAgentId, "cat /workspace/qa_report.md");
        verify(reportService).capture("SDD QA Report", chainId.toString(), "sandbox report content");
        assertThat(chain.getReportArtifactId()).isEqualTo(ARTIFACT_ID);
    }

    @Test
    void failedChain_withQaReport_stillCapturesArtifact() {
        WorkflowChain chain = gitChain();
        chain.setStatus(WorkflowChain.Status.FAILED);
        when(chainRepository.findById(chainId)).thenReturn(Optional.of(chain));
        when(gitBranchService.getFile(REPO_URL, "sdd/" + chainId, "qa_report.md"))
                .thenReturn(Optional.of("# QA Report\n\nTests failed: 2 defects found."));
        when(reportService.capture("SDD QA Report", chainId.toString(),
                "# QA Report\n\nTests failed: 2 defects found."))
                .thenReturn(ReportArtifact.builder()
                        .id(ARTIFACT_ID.toString())
                        .title("SDD QA Report")
                        .owner(chainId.toString())
                        .version(1)
                        .status("GENERATED")
                        .build());

        listener.onWorkflowAdvanced(failedEvent());

        verify(reportService).capture("SDD QA Report", chainId.toString(),
                "# QA Report\n\nTests failed: 2 defects found.");
        assertThat(chain.getReportArtifactId()).isEqualTo(ARTIFACT_ID);
        verify(chainRepository).save(chain);
    }

    @Test
    void onCompletedEvent_nonGitChain_skipsCapture() {
        WorkflowChain chain = WorkflowChain.builder()
                .id(chainId)
                .name("generic")
                .status(WorkflowChain.Status.COMPLETED)
                .currentStepIndex(0)
                .stepsJson("[]")
                .templateParams("{}")
                .createdAt(Instant.now())
                .build();
        when(chainRepository.findById(chainId)).thenReturn(Optional.of(chain));

        listener.onWorkflowAdvanced(completedEvent());

        verify(reportService, never()).capture(anyString(), anyString(), anyString());
        verify(chainRepository, never()).save(any());
        assertThat(chain.getReportArtifactId()).isNull();
    }

    @Test
    void onNonCompletedEvent_isNoop() {
        WorkflowAdvancedEvent running = new WorkflowAdvancedEvent(
                this, chainId, "chain", 1, 2, WorkflowChain.Status.RUNNING);

        listener.onWorkflowAdvanced(running);

        verify(chainRepository, never()).findById(any());
    }

    private WorkflowAdvancedEvent completedEvent() {
        return new WorkflowAdvancedEvent(
                this, chainId, "development-workflow-instance", 2, -1, WorkflowChain.Status.COMPLETED);
    }

    private WorkflowAdvancedEvent failedEvent() {
        return new WorkflowAdvancedEvent(
                this, chainId, "development-workflow-instance", 2, -1, WorkflowChain.Status.FAILED);
    }

    private WorkflowChain gitChain() {
        return WorkflowChain.builder()
                .id(chainId)
                .name("development-workflow-instance")
                .status(WorkflowChain.Status.COMPLETED)
                .currentStepIndex(2)
                .stepsJson("[{\"kind\":\"QA\"}]")
                .templateParams("{\"repoUrl\":\"" + REPO_URL + "\"}")
                .createdAt(Instant.now())
                .build();
    }
}

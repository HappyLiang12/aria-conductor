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
import io.aria.conductor.execution.git.GitHandoffMetadata;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Captures the SDD QA report into a platform report artifact when a git-handoff
 * chain completes (R8-F4).
 *
 * <p>Preferred path: pull {@code qa_report.md} from the sdd branch (the QA prompt
 * commits it there). Fallback: run {@code cat /workspace/qa_report.md} in the QA
 * agent's live sandbox. The captured artifact is attached to the chain via
 * {@code WorkflowChain.reportArtifactId}. Failures are logged and never crash the
 * chain — a missing report is simply skipped.
 */
@Slf4j
@Component
public class QaReportCaptureListener {

    private final WorkflowChainRepository chainRepository;
    private final WorkflowService workflowService;
    private final GitBranchService gitBranchService;
    private final OpenCodeAdkProvider openCodeAdkProvider;
    private final ReportService reportService;

    public QaReportCaptureListener(WorkflowChainRepository chainRepository,
                                   WorkflowService workflowService,
                                   GitBranchService gitBranchService,
                                   OpenCodeAdkProvider openCodeAdkProvider,
                                   ReportService reportService) {
        this.chainRepository = chainRepository;
        this.workflowService = workflowService;
        this.gitBranchService = gitBranchService;
        this.openCodeAdkProvider = openCodeAdkProvider;
        this.reportService = reportService;
    }

    @EventListener
    public void onWorkflowAdvanced(WorkflowAdvancedEvent event) {
        // Capture on ANY terminal state (COMPLETED or FAILED): a chain that failed at
        // Dev rework or a DEFECT/SPEC_GAP loop still produced a qa_report.md worth
        // surfacing to the platform (R9-F4).
        if (event.getChainStatus() != WorkflowChain.Status.COMPLETED
                && event.getChainStatus() != WorkflowChain.Status.FAILED) {
            return;
        }
        try {
            captureQaReport(event.getWorkflowId());
        } catch (Exception e) {
            log.warn("QA report capture failed for chain {}: {}",
                    event.getWorkflowId(), e.getMessage());
        }
    }

    private void captureQaReport(UUID chainId) {
        WorkflowChain chain = chainRepository.findById(chainId).orElse(null);
        if (chain == null) {
            return;
        }
        Map<String, String> meta = GitHandoffMetadata.parse(chain.getTemplateParams());
        String repoUrl = meta.get(GitHandoffMetadata.KEY_REPO_URL);
        if (repoUrl == null || repoUrl.isBlank()) {
            return; // not a git-handoff chain
        }
        String branchName = GitHandoffMetadata.branchName(chainId);

        String content = readFromBranch(repoUrl, branchName);
        if (content == null) {
            content = readFromQaSandbox(chain);
        }
        if (content == null || content.isBlank()) {
            log.info("No qa_report.md available for chain {}; skipping report capture", chainId);
            return;
        }

        ReportArtifact artifact = reportService.capture("SDD QA Report", chainId.toString(), content);
        chain.setReportArtifactId(UUID.fromString(artifact.getId()));
        chainRepository.save(chain);
        log.info("QA report captured for chain {} as report artifact {}", chainId, artifact.getId());
    }

    private String readFromBranch(String repoUrl, String branchName) {
        try {
            Optional<String> file = gitBranchService.getFile(repoUrl, branchName, "qa_report.md");
            return file.orElse(null);
        } catch (Exception e) {
            log.warn("Cannot read qa_report.md from branch {}: {}", branchName, e.getMessage());
            return null;
        }
    }

    private String readFromQaSandbox(WorkflowChain chain) {
        UUID qaAgentId = findQaAgentId(chain);
        if (qaAgentId == null) {
            return null;
        }
        try {
            return openCodeAdkProvider.runSandboxCommand(qaAgentId, "cat /workspace/qa_report.md");
        } catch (Exception e) {
            log.warn("Cannot cat /workspace/qa_report.md in QA sandbox for chain {}: {}",
                    chain.getId(), e.getMessage());
            return null;
        }
    }

    private UUID findQaAgentId(WorkflowChain chain) {
        try {
            List<WorkflowStep> steps = workflowService.deserializeSteps(chain.getStepsJson());
            for (WorkflowStep step : steps) {
                if (step.getKind() == WorkflowStep.StepKind.QA) {
                    return step.getAgentId();
                }
            }
        } catch (Exception e) {
            log.warn("Cannot resolve QA agent for chain {}: {}", chain.getId(), e.getMessage());
        }
        return null;
    }
}

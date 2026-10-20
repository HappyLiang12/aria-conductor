package io.aria.conductor.dashboard.listener;

import io.aria.conductor.agent.repository.WorkflowChainRepository;
import io.aria.conductor.common.event.WorkflowAdvancedEvent;
import io.aria.conductor.common.model.WorkflowChain;
import io.aria.conductor.dashboard.report.ReportArtifact;
import io.aria.conductor.dashboard.report.ReportService;
import io.aria.conductor.execution.git.GitBranchService;
import io.aria.conductor.execution.git.GitHandoffMetadata;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Captures the SDD QA report into a platform report artifact when a git-handoff
 * chain completes (R8-F4).
 *
 * <p>The report is pulled from {@code qa_report.md} on the sdd branch (the QA
 * prompt commits it there). The captured artifact is attached to the chain via
 * {@code WorkflowChain.reportArtifactId}. Failures are logged and never crash the
 * chain — a missing report is simply skipped.
 */
@Slf4j
@Component
public class QaReportCaptureListener {

    private final WorkflowChainRepository chainRepository;
    private final GitBranchService gitBranchService;
    private final ReportService reportService;

    public QaReportCaptureListener(WorkflowChainRepository chainRepository,
                                   GitBranchService gitBranchService,
                                   ReportService reportService) {
        this.chainRepository = chainRepository;
        this.gitBranchService = gitBranchService;
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
}

package io.aria.conductor.app.sdd;

import io.aria.conductor.agent.dto.WorkflowResponse;
import io.aria.conductor.agent.repository.AgentRepository;
import io.aria.conductor.agent.repository.WorkflowChainRepository;
import io.aria.conductor.agent.service.WorkflowService;
import io.aria.conductor.app.BaseH2IntegrationTest;
import io.aria.conductor.common.model.*;
import io.aria.conductor.dashboard.report.ReportArtifact;
import io.aria.conductor.dashboard.report.ReportService;
import io.aria.conductor.execution.adk.AdkProvider;
import io.aria.conductor.execution.adk.AdkProviderRegistry;
import io.aria.conductor.execution.adk.opencode.OpenCodeAdkProvider;
import io.aria.conductor.execution.approval.ApprovalGate;
import io.aria.conductor.execution.dod.DoDRecord;
import io.aria.conductor.execution.dod.DoDService;
import io.aria.conductor.execution.git.GitBranchService;
import io.aria.conductor.execution.git.GitHubIssue;
import io.aria.conductor.execution.git.GitHubIssueClient;
import io.aria.conductor.execution.llm.LlmResponse;
import io.aria.conductor.execution.repository.ApprovalRepository;
import io.aria.conductor.knowledge.repository.KnowledgeItemRepository;
import io.aria.conductor.knowledge.repository.KnowledgeVersionRepository;
import io.aria.conductor.knowledge.service.WorkflowTemplateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * FULL-CYCLE integration tests for the SDD git-pipeline artifact flow (plan Task 7).
 *
 * <p>Proves the whole loop end-to-end with a mocked ADK and mocked GitHub channel:
 * instantiateTemplate (with issueRef/issueRepo/repoUrl) -> branchName injection -> BA
 * spec draft -> SPEC_REVIEW approval -> Git branch handoff (createBranch + commit
 * spec/spec.md) -> Dev completion backend-push fallback -> QA verdict routing on the
 * {@code VERDICT=} marker (no submit_dod_review tool call).
 *
 * <p>Mocking strategy:
 * <ul>
 *   <li>{@code @MockBean AdkProviderRegistry} — resolves a scripted {@link AdkProvider}
 *       whose {@code call()} returns per-agent outputs (BA/DEV specs, QA verdict markers).</li>
 *   <li>{@code @MockBean GitBranchService} — records createBranch/putFile/branchHeadSha;
 *       branchHeadSha always returns the spec-commit sha so the backend-push fallback
 *       path is exercised (Dev "forgot" to push).</li>
 *   <li>{@code @MockBean OpenCodeAdkProvider} — verifies {@code runSandboxCommand} carries
 *       the {@code git push origin sdd/&lt;chainId&gt;} fallback command.</li>
 * </ul>
 *
 * <p>The real {@link io.aria.conductor.knowledge.sdd.SpecReviewCoordinator} and
 * {@link io.aria.conductor.execution.listener.WorkflowAutoChainer} are used untouched;
 * only the outer ADK and GitHub boundaries are mocked.
 */
class GitPipelineIntegrationTest extends BaseH2IntegrationTest {

    @Autowired
    private WorkflowService workflowService;
    @Autowired
    private WorkflowChainRepository workflowChainRepository;
    @Autowired
    private ApprovalRepository approvalRepository;
    @Autowired
    private ApprovalGate approvalGate;
    @Autowired
    private DoDService dodService;
    @Autowired
    private AgentRepository agentRepository;
    @Autowired
    private KnowledgeItemRepository knowledgeItemRepository;
    @Autowired
    private KnowledgeVersionRepository knowledgeVersionRepository;
    @Autowired
    private WorkflowTemplateService workflowTemplateService;
    @Autowired
    private ReportService reportService;

    @MockBean
    private AdkProviderRegistry adkProviderRegistry;
    @MockBean
    private GitBranchService gitBranchService;
    @MockBean
    private GitHubIssueClient gitHubIssueClient;
    @MockBean
    private OpenCodeAdkProvider openCodeAdkProvider;

    private UUID baAgentId;
    private UUID devAgentId;
    private UUID qaAgentId;

    private static final String REPO_URL = "https://github.com/acme/repo.git";
    private static final String ISSUE_REPO = "acme/repo";
    private static final String ISSUE_REF = "ISSUE-42";
    private static final UUID REPORT_ID = UUID.fromString("00000000-0000-0000-0000-0000000000ab");

    /** BA output is already spec-shaped (starts with "# Spec") so cleanSpecContent leaves it verbatim. */
    private static final String BA_OUTPUT = "# Spec Document\n\n## Requirements\n- Feature A\n- Feature B";
    private static final String DEV_OUTPUT = "Implementation complete for all features.";
    private static final String QA_MARKER_PASS = "All tests passed.\nVERDICT=PASS\nREPORT_ID=" + REPORT_ID;
    private static final String QA_MARKER_DEFECT = "Parser crashes on empty input.\nVERDICT=DEFECT";
    private static final String QA_MARKER_SPEC_GAP = "Error handling not specified.\nVERDICT=SPEC_GAP";
    private static final String QA_NO_VERDICT = "QA completed but forgot to submit a verdict.";
    private static final String QA_REPORT_CONTENT =
            "# SDD QA Report\n\nAll checks passed.\n\n## Summary\n- Unit tests green";

    @BeforeEach
    void setUp() {
        baAgentId = createAgent("BA-Agent-" + shortUuid());
        devAgentId = createAgent("DEV-Agent-" + shortUuid());
        qaAgentId = createAgent("QA-Agent-" + shortUuid());

        // The spec-commit sha is recorded on approval (branchHeadSha), and the same sha is
        // returned after Dev completes -> branch did NOT advance -> backend-push fallback runs.
        when(gitBranchService.branchHeadSha(anyString(), anyString()))
                .thenReturn(Optional.of("spec-sha-abc"));

        // R9-F2: the SDD spec task grounds its issue reference at instantiation; the mock
        // GitHub channel resolves the fixture issue (issue #42) so the BA message is inlined.
        when(gitHubIssueClient.resolveIssue(ISSUE_REPO, ISSUE_REF))
                .thenReturn(new GitHubIssue(ISSUE_REPO, 42, "ISSUE-42",
                        "Ground the issue reference before dispatch.", List.of("sdd", "fixture")));
    }

    // ================================================================
    //  FULL CYCLE 1: VERDICT=PASS marker -> chain COMPLETED
    // ================================================================

    @Test
    void fullCycle_ba_approve_devFallback_qaMarkerPass_completesChain() {
        configureMockAdk(QA_MARKER_PASS);

        WorkflowResponse created = createSddGitChain();
        UUID chainId = created.getId();
        String branch = "sdd/" + chainId;

        // 1. branchName system placeholder injected into DEV/QA prompts at instantiation
        WorkflowChain chain = workflowChainRepository.findById(chainId).orElseThrow();
        List<WorkflowStep> steps = workflowService.deserializeSteps(chain.getStepsJson());
        assertThat(steps.get(1).getPromptTemplate()).contains(branch);
        assertThat(steps.get(2).getPromptTemplate()).contains(branch);
        assertThat(chain.getStepsJson()).doesNotContain("{branchName}");

        // 2. BA step completes (mock ADK) -> SPEC_REVIEW approval PENDING -> WAITING_APPROVAL
        awaitChainStatus(chainId, WorkflowChain.Status.WAITING_APPROVAL);

        // 3. approve via coordinator -> createBranch + commit spec/spec.md
        approveSpecReview(chainId);

        // 4-5. Dev completes (branchHeadSha unchanged -> backend fallback) -> QA marker
        //      VERDICT=PASS (no tool verdict) -> chain COMPLETED
        awaitChainStatus(chainId, WorkflowChain.Status.COMPLETED);

        // Branch handoff: branch created and cleaned spec committed to spec/spec.md
        verify(gitBranchService).createBranch(eq(REPO_URL), eq(branch));
        verify(gitBranchService).putFile(eq(REPO_URL), eq(branch), eq("spec/spec.md"),
                eq(BA_OUTPUT), anyString());

        // Backend push fallback: Dev did not advance the branch, so the sandbox command ran
        verify(openCodeAdkProvider).runSandboxCommand(eq(devAgentId),
                contains("git push origin " + branch));

        // DoD qa review recorded via the VERDICT=PASS marker path (R8-F2) -> DoD completed + PASSED
        DoDRecord dod = dodService.getStatus(chainId.toString());
        assertThat(dod.getOverallStatus()).isEqualTo("PASSED");

        // REPORT_ID captured from the QA marker output (no tool verdict was submitted)
        WorkflowChain completed = workflowChainRepository.findById(chainId).orElseThrow();
        assertThat(completed.getReportArtifactId()).isEqualTo(REPORT_ID);
    }

    // ================================================================
    //  FULL CYCLE 2: VERDICT=DEFECT marker -> Dev re-scheduled
    // ================================================================

    @Test
    void fullCycle_verdictDefect_reschedulesDev() {
        // First QA run emits DEFECT, second (after Dev rework) emits PASS.
        configureMockAdk(QA_MARKER_DEFECT, QA_MARKER_PASS);

        WorkflowResponse created = createSddGitChain();
        UUID chainId = created.getId();

        awaitChainStatus(chainId, WorkflowChain.Status.WAITING_APPROVAL);
        approveSpecReview(chainId);

        // QA DEFECT -> Dev rescheduled (attemptCount=1) -> Dev rework -> QA PASS -> COMPLETED
        awaitChainStatus(chainId, WorkflowChain.Status.COMPLETED);

        List<WorkflowStep> steps = workflowService.deserializeSteps(
                workflowChainRepository.findById(chainId).orElseThrow().getStepsJson());
        assertThat(steps.get(1).getAttemptCount())
                .as("Dev step re-scheduled once after the DEFECT marker")
                .isEqualTo(1);
    }

    // ================================================================
    //  FULL CYCLE 3: VERDICT=SPEC_GAP marker -> BA re-scheduled
    // ================================================================

    @Test
    void fullCycle_verdictSpecGap_reschedulesBa() {
        // First QA run emits SPEC_GAP, second (after BA re-spec + re-approval) emits PASS.
        configureMockAdk(QA_MARKER_SPEC_GAP, QA_MARKER_PASS);

        WorkflowResponse created = createSddGitChain();
        UUID chainId = created.getId();

        // First BA -> approve -> DEV -> QA (SPEC_GAP) -> BA rescheduled
        awaitChainStatus(chainId, WorkflowChain.Status.WAITING_APPROVAL);
        approveSpecReview(chainId);

        // QA SPEC_GAP -> BA re-runs -> coordinator re-opens the SPEC_REVIEW approval
        awaitChainStatus(chainId, WorkflowChain.Status.WAITING_APPROVAL);

        List<WorkflowStep> steps = workflowService.deserializeSteps(
                workflowChainRepository.findById(chainId).orElseThrow().getStepsJson());
        assertThat(steps.get(0).getAttemptCount())
                .as("BA step re-scheduled once after the SPEC_GAP marker")
                .isEqualTo(1);

        // Second approval -> DEV -> QA (PASS) -> COMPLETED
        approveSpecReview(chainId);
        awaitChainStatus(chainId, WorkflowChain.Status.COMPLETED);
    }

    // ================================================================
    //  FULL CYCLE 4: no marker, no tool verdict -> chain FAILED + hint
    // ================================================================

    @Test
    void fullCycle_noMarkerNoTool_failsChainWithHint() {
        configureMockAdk(QA_NO_VERDICT);

        WorkflowResponse created = createSddGitChain();
        UUID chainId = created.getId();

        awaitChainStatus(chainId, WorkflowChain.Status.WAITING_APPROVAL);
        approveSpecReview(chainId);

        // QA completes with neither a submit_dod_review verdict nor a VERDICT= marker
        awaitChainStatus(chainId, WorkflowChain.Status.FAILED);

        List<WorkflowStep> steps = workflowService.deserializeSteps(
                workflowChainRepository.findById(chainId).orElseThrow().getStepsJson());
        WorkflowStep qaStep = steps.get(2);
        assertThat(qaStep.getStatus()).isEqualTo(WorkflowStep.Status.FAILED);
        assertThat(qaStep.getOutput()).contains("QA completed but no verdict submitted");
        assertThat(qaStep.getOutput()).contains("submit_dod_review");
    }

    // ================================================================
    //  FULL CYCLE 5: VERDICT=PASS -> QA report captured into a platform report artifact
    // ================================================================

    @Test
    void fullCycle_qaMarkerPass_capturesReportArtifactFromBranch() {
        configureMockAdk(QA_MARKER_PASS);

        WorkflowResponse created = createSddGitChain();
        UUID chainId = created.getId();
        String branch = "sdd/" + chainId;

        // The QA agent committed qa_report.md to the sdd branch, so the backend pulls it
        // from the branch when the chain completes (R8-F4).
        when(gitBranchService.getFile(eq(REPO_URL), eq(branch), eq("qa_report.md")))
                .thenReturn(Optional.of(QA_REPORT_CONTENT));

        awaitChainStatus(chainId, WorkflowChain.Status.WAITING_APPROVAL);
        approveSpecReview(chainId);
        awaitChainStatus(chainId, WorkflowChain.Status.COMPLETED);

        // The capture listener runs synchronously on the run-completion thread just after
        // the chain is marked COMPLETED; wait for it to attach the real artifact id.
        await().atMost(10, TimeUnit.SECONDS).until(() -> {
            WorkflowChain c = workflowChainRepository.findById(chainId).orElse(null);
            return c != null && c.getReportArtifactId() != null
                    && !REPORT_ID.equals(c.getReportArtifactId());
        });

        // The report artifact is created from the branch's qa_report.md content.
        verify(gitBranchService).getFile(eq(REPO_URL), eq(branch), eq("qa_report.md"));
        WorkflowChain completed = workflowChainRepository.findById(chainId).orElseThrow();
        assertThat(completed.getReportArtifactId()).isNotNull();
        assertThat(completed.getReportArtifactId()).isNotEqualTo(REPORT_ID);

        ReportArtifact artifact = reportService.get(completed.getReportArtifactId().toString());
        assertThat(artifact.getTitle()).isEqualTo("SDD QA Report");
        assertThat(reportService.getHtml(artifact.getId())).contains("All checks passed");
    }

    // ================================================================
    //  PRIVATE HELPERS
    // ================================================================

    /** Create a test agent and return its ID. */
    private UUID createAgent(String name) {
        Agent agent = Agent.builder()
                .name(name)
                .role("test-role")
                .agentType(AgentType.NATIVE)
                .adkProvider("langchain")
                .config("{}")
                .healthStatus(HealthStatus.HEALTHY)
                .build();
        return agentRepository.save(agent).getId();
    }

    /** Instantiate a git-handoff SDD chain (BA/DEV/QA) with issueRef/issueRepo/repoUrl. */
    private WorkflowResponse createSddGitChain() {
        UUID templateId = createSddGitTemplate();
        return workflowTemplateService.instantiateTemplate(templateId,
                Map.of("issueRef", ISSUE_REF, "issueRepo", ISSUE_REPO, "repoUrl", REPO_URL));
    }

    /**
     * Create an APPROVED development-workflow template whose prompts declare the git-handoff
     * parameters (issueRef/issueRepo/repoUrl) and use the {branchName}/{specRef} system
     * placeholders in the DEV/QA steps (mirrors the V45 seeded prompt shape).
     */
    private UUID createSddGitTemplate() {
        UUID templateId = UUID.randomUUID();

        knowledgeItemRepository.save(KnowledgeItem.builder()
                .id(templateId)
                .name("development-workflow")
                .type(KnowledgeType.WORKFLOW)
                .status(KnowledgeStatus.APPROVED)
                .sensitivity(Sensitivity.INTERNAL)
                .currentVersion("v1")
                .escalationCount(0)
                .createdAt(Instant.now())
                .build());

        String yamlContent = """
                schema_version: "1.0"
                name: development-workflow
                steps:
                  - agent_id: "%s"
                    prompt_template: "Write spec for {issueRef} in {issueRepo} at {repoUrl}"
                    max_iterations: 3
                    kind: BA
                  - agent_id: "%s"
                    prompt_template: "git clone --branch {branchName} {repoUrl} /workspace/repo; implement {specRef}"
                    max_iterations: 5
                    kind: DEV
                  - agent_id: "%s"
                    prompt_template: "git clone --branch {branchName} {repoUrl} /workspace/repo; verify {specRef}"
                    max_iterations: 3
                    kind: QA
                """.formatted(baAgentId.toString(), devAgentId.toString(), qaAgentId.toString());

        knowledgeVersionRepository.save(KnowledgeVersion.builder()
                .knowledgeItemId(templateId)
                .version("v1")
                .status(VersionStatus.APPROVED)
                .yamlContent(yamlContent)
                .createdAt(Instant.now())
                .approvedAt(Instant.now())
                .build());

        return templateId;
    }

    /** Poll until the chain reaches the given status. */
    private void awaitChainStatus(UUID chainId, WorkflowChain.Status expected) {
        try {
            await().atMost(30, TimeUnit.SECONDS).until(() -> {
                WorkflowChain c = workflowChainRepository.findById(chainId).orElse(null);
                return c != null && c.getStatus() == expected;
            });
        } catch (org.awaitility.core.ConditionTimeoutException e) {
            WorkflowChain c = workflowChainRepository.findById(chainId).orElse(null);
            System.out.println("DIAG: await " + expected + " timed out; chain="
                    + (c != null ? c.getStatus() + " step=" + c.getCurrentStepIndex()
                    + " stepsJson=" + c.getStepsJson() : "NOT FOUND"));
            throw e;
        }
    }

    /**
     * Find the SPEC_REVIEW approval associated with the chain's BA run and approve it.
     * The approval decision is published synchronously, so createBranch + putFile happen
     * before this method returns.
     */
    private void approveSpecReview(UUID chainId) {
        WorkflowChain chain = workflowChainRepository.findById(chainId).orElseThrow();
        List<WorkflowStep> steps = workflowService.deserializeSteps(chain.getStepsJson());
        UUID baRunId = steps.get(0).getRunId();
        assertThat(baRunId).as("BA step must have a runId").isNotNull();

        await().atMost(10, TimeUnit.SECONDS).until(() -> {
            List<Approval> approvals = approvalRepository.findByRunId(baRunId);
            return approvals.stream().anyMatch(
                    a -> a.getApprovalType() == Approval.ApprovalType.SPEC_REVIEW
                            && a.getStatus() == ApprovalStatus.PENDING);
        });

        Approval specApproval = approvalRepository.findByRunId(baRunId).stream()
                .filter(a -> a.getApprovalType() == Approval.ApprovalType.SPEC_REVIEW)
                .findFirst().orElseThrow();
        approvalGate.decideApproval(specApproval.getId(), true, "Spec approved by test");
    }

    /**
     * Configure the mock ADK. {@code qaOutputs} is the scripted sequence of QA outputs
     * (one consumed per QA run; the last entry is repeated for any further runs).
     * BA/DEV always return their fixed outputs.
     */
    private void configureMockAdk(String... qaOutputs) {
        AdkProvider mockAdk = mock(AdkProvider.class);
        when(adkProviderRegistry.resolve(any())).thenReturn(mockAdk);
        when(mockAdk.isHealthy(any())).thenReturn(true);
        when(mockAdk.parseActionsFromResponse(any())).thenReturn(List.of());
        AtomicInteger qaCall = new AtomicInteger(0);
        when(mockAdk.call(any(), any(), any(), any())).thenAnswer(inv -> {
            UUID agentId = inv.getArgument(0);
            String output;
            if (qaAgentId.equals(agentId)) {
                int idx = Math.min(qaCall.getAndIncrement(), qaOutputs.length - 1);
                output = qaOutputs[idx];
            } else if (devAgentId.equals(agentId)) {
                output = DEV_OUTPUT;
            } else {
                output = BA_OUTPUT;
            }
            return new LlmResponse(output, 10, 20, "stop", List.of());
        });
    }

    /** Short random suffix for unique names. */
    private static String shortUuid() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}

package io.aria.conductor.app.sdd;

import io.aria.conductor.agent.dto.CreateWorkflowRequest;
import io.aria.conductor.agent.dto.WorkflowResponse;
import io.aria.conductor.agent.repository.AgentRepository;
import io.aria.conductor.agent.repository.WorkflowChainRepository;
import io.aria.conductor.agent.service.WorkflowService;
import io.aria.conductor.app.BaseH2IntegrationTest;
import io.aria.conductor.common.model.*;
import io.aria.conductor.execution.adk.AdkProvider;
import io.aria.conductor.execution.adk.AdkProviderRegistry;
import io.aria.conductor.execution.approval.ApprovalGate;
import io.aria.conductor.execution.dod.DoDRecord;
import io.aria.conductor.execution.dod.DoDService;
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
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Integration tests for the SDD workflow (BA → DEV → QA) lifecycle.
 *
 * <p>Key design:
 * <ul>
 *   <li><b>Async run execution:</b> {@code AgentLoopEngine.startRun()} launches each agent
 *       run on a virtual thread. The engine calls the (mocked) {@link AdkProvider} on that
 *       virtual thread, so run completion is asynchronous from the test thread.</li>
 *   <li><b>QA verdict gate:</b> The mock ADK's {@code call()} blocks on a
 *       {@link CompletableFuture} for the 3rd, 5th, 7th… invocation (i.e. every QA-run ADK
 *       call), giving the test thread a window to submit the stage review with the desired
 *       verdict before the QA virtual thread completes.</li>
 *   <li><b>No &#64;Transactional:</b> Extends {@link BaseH2IntegrationTest} (no
 *       &#64;Transactional), so data committed by service-layer transactions is immediately
 *       visible to asynchronous virtual threads.</li>
 *   <li><b>Awaits via Awaitility:</b> All chain-status transitions are polled with
 *       {@code await().atMost(…)} guards.</li>
 * </ul>
 */
class SddWorkflowIntegrationTest extends BaseH2IntegrationTest {

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

    @MockBean
    private AdkProviderRegistry adkProviderRegistry;

    /** Shared agent IDs created in setUp. */
    private UUID baAgentId;
    private UUID devAgentId;
    private UUID qaAgentId;

    // Standardised mock ADK outputs used across tests
    private static final String BA_OUTPUT = "# Spec Document\n\n## Requirements\n- Feature A\n- Feature B";
    private static final String DEV_OUTPUT = "Implementation complete for all features.";
    private static final String QA_OUTPUT_PASS =
            "All tests passed.\nREPORT_ID=00000000-0000-0000-0000-0000000000ab";


    // --------------- Setup ---------------

    @BeforeEach
    void setUp() {
        baAgentId = createAgent("BA-Agent-" + shortUuid());
        devAgentId = createAgent("DEV-Agent-" + shortUuid());
        qaAgentId = createAgent("QA-Agent-" + shortUuid());
    }

    // ================================================================
    //  HAPPY PATH
    // ================================================================

    @Test
    void happyPath_baApproval_dev_qaPass_completesChain() throws Exception {
        BlockingQueue<CompletableFuture<Void>> qaGates = new LinkedBlockingQueue<>();
        AtomicInteger adkCalls = new AtomicInteger(0);

        configureMockAdk(qaGates, adkCalls);

        // Create SDD chain (BA / DEV / QA) and initialise DoD record
        WorkflowResponse created = createSddChain("happy-path");
        UUID chainId = created.getId();

        // Stage 1: BA step completes → SpecReviewCoordinator → WAITING_APPROVAL
        awaitChainStatus(chainId, WorkflowChain.Status.WAITING_APPROVAL);

        // Approve the SPEC_REVIEW → Coordinator advances to DEV
        approveSpecReview(chainId);

        // Stage 2: DEV step completes → auto-submits dev DoD review → DoD advances to "qa"
        // QA step starts → mock ADK blocks on qaGates
        CompletableFuture<Void> gate1 = qaGates.poll(15, TimeUnit.SECONDS);
        assertThat(gate1).as("QA gate should be offered").isNotNull();

        // Submit QA PASS verdict before QA run completes
        dodService.review(chainId.toString(), qaAgentId.toString(), "QA Tester",
                true, "verified", "All criteria met", "PASS");
        gate1.complete(null);

        // Stage 3: QA run completes → chainer finds PASS → chain COMPLETED
        awaitChainStatus(chainId, WorkflowChain.Status.COMPLETED);

        // Verify final state
        WorkflowChain chain = workflowChainRepository.findById(chainId).orElseThrow();
        assertThat(chain.getReportArtifactId())
                .as("REPORT_ID captured from QA finalOutput")
                .isEqualTo(UUID.fromString("00000000-0000-0000-0000-0000000000ab"));
        DoDRecord dod = dodService.getStatus(chainId.toString());
        assertThat(dod.getOverallStatus()).isEqualTo("PASSED");
    }

    // ================================================================
    //  DEFECT LOOP
    // ================================================================

    @Test
    void defectLoop_qaDefect_reschedulesDevWithFeedback_thenPasses() throws Exception {
        BlockingQueue<CompletableFuture<Void>> qaGates = new LinkedBlockingQueue<>();
        AtomicInteger adkCalls = new AtomicInteger(0);

        configureMockAdk(qaGates, adkCalls);

        WorkflowResponse created = createSddChain("defect-loop");
        UUID chainId = created.getId();

        // BA → WAITING_APPROVAL → approve
        awaitChainStatus(chainId, WorkflowChain.Status.WAITING_APPROVAL);
        approveSpecReview(chainId);

        // First QA gate: submit DEFECT
        CompletableFuture<Void> gate1 = qaGates.poll(15, TimeUnit.SECONDS);
        assertThat(gate1).isNotNull();
        dodService.review(chainId.toString(), qaAgentId.toString(), "QA Tester",
                false, "login missing", "Fix the login validation", "DEFECT");
        gate1.complete(null);

        // Chainer reschedules DEV (attemptCount=1) → DEV runs → QA runs again

        // Second QA gate: submit PASS
        CompletableFuture<Void> gate2 = qaGates.poll(15, TimeUnit.SECONDS);
        assertThat(gate2).isNotNull();
        dodService.review(chainId.toString(), qaAgentId.toString(), "QA Tester",
                true, "fixed", "Login validation now works", "PASS");
        gate2.complete(null);

        // Chain should complete
        awaitChainStatus(chainId, WorkflowChain.Status.COMPLETED);

        // Verify DoD PASSED
        DoDRecord dod = dodService.getStatus(chainId.toString());
        assertThat(dod.getOverallStatus()).isEqualTo("PASSED");
    }

    // ================================================================
    //  SPEC GAP LOOP
    // ================================================================

    @Test
    void specGapLoop_qaSpecGap_reschedulesBa_createsNewSpecVersion_reapproval() throws Exception {
        BlockingQueue<CompletableFuture<Void>> qaGates = new LinkedBlockingQueue<>();
        AtomicInteger adkCalls = new AtomicInteger(0);

        configureMockAdk(qaGates, adkCalls);

        WorkflowResponse created = createSddChain("spec-gap-loop");
        UUID chainId = created.getId();

        // BA → WAITING_APPROVAL → approve
        awaitChainStatus(chainId, WorkflowChain.Status.WAITING_APPROVAL);
        approveSpecReview(chainId);

        // First QA gate: submit SPEC_GAP
        CompletableFuture<Void> gate1 = qaGates.poll(15, TimeUnit.SECONDS);
        assertThat(gate1).isNotNull();
        dodService.review(chainId.toString(), qaAgentId.toString(), "QA Tester",
                false, "spec missing", "Error handling not specified", "SPEC_GAP");
        gate1.complete(null);

        // Chainer reschedules BA → BA re-runs → SpecReviewCoordinator creates new spec →
        // chain WAITING_APPROVAL (re-approval required)
        awaitChainStatus(chainId, WorkflowChain.Status.WAITING_APPROVAL);

        // Approve the new SPEC_REVIEW (associated with the new BA run)
        approveSpecReview(chainId);

        // DEV runs → QA runs → second QA gate
        CompletableFuture<Void> gate2 = qaGates.poll(15, TimeUnit.SECONDS);
        assertThat(gate2).isNotNull();
        dodService.review(chainId.toString(), qaAgentId.toString(), "QA Tester",
                true, "verified", "Spec updated, all good", "PASS");
        gate2.complete(null);

        // Chain should complete
        awaitChainStatus(chainId, WorkflowChain.Status.COMPLETED);
    }

    // ================================================================
    //  BOUNDARY: max attempts exhausted
    // ================================================================

    @Test
    void boundaries_maxAttempts_failsChain() throws Exception {
        BlockingQueue<CompletableFuture<Void>> qaGates = new LinkedBlockingQueue<>();
        AtomicInteger adkCalls = new AtomicInteger(0);

        configureMockAdk(qaGates, adkCalls);

        WorkflowResponse created = createSddChain("max-attempts");
        UUID chainId = created.getId();

        // BA → WAITING_APPROVAL → approve
        awaitChainStatus(chainId, WorkflowChain.Status.WAITING_APPROVAL);
        approveSpecReview(chainId);

        // Submit DEFECT 4 times (QA runs 1-4). Each triggers DEV reschedule.
        // The 4th reschedule attempt exhausts DEFAULT_MAX_ATTEMPTS=3 → chain FAILED.
        for (int i = 0; i < 4; i++) {
            CompletableFuture<Void> gate = qaGates.poll(15, TimeUnit.SECONDS);
            assertThat(gate).as("QA gate #%d should be offered", i + 1).isNotNull();
            dodService.review(chainId.toString(), qaAgentId.toString(), "QA Tester",
                    false, "defect", "Still failing #" + (i + 1), "DEFECT");
            gate.complete(null);
        }

        // Chain should end up FAILED after max attempts
        await().atMost(30, TimeUnit.SECONDS).until(() -> {
            WorkflowChain c = workflowChainRepository.findById(chainId).orElse(null);
            return c != null && c.getStatus() == WorkflowChain.Status.FAILED;
        });
    }

    // ================================================================
    //  BOUNDARY: QA completes without any verdict
    // ================================================================

    @Test
    void boundaries_qaWithoutVerdict_failsChain() throws Exception {
        // For this test the mock ADK returns instantly for ALL calls — no gating needed.
        AtomicInteger adkCalls = new AtomicInteger(0);
        configureMockAdkNoGating(adkCalls);

        WorkflowResponse created = createSddChain("no-verdict");
        UUID chainId = created.getId();

        // BA → WAITING_APPROVAL → approve
        awaitChainStatus(chainId, WorkflowChain.Status.WAITING_APPROVAL);
        approveSpecReview(chainId);

        // DEV completes → auto-submits dev DoD review → DoD advances to "qa"
        // QA completes → chainer calls routeOnQaVerdict → no QA review → markStepFailed
        await().atMost(30, TimeUnit.SECONDS).until(() -> {
            WorkflowChain c = workflowChainRepository.findById(chainId).orElse(null);
            return c != null && c.getStatus() == WorkflowChain.Status.FAILED;
        });

        // Verify the error message on the QA step
        WorkflowChain chain = workflowChainRepository.findById(chainId).orElseThrow();
        List<WorkflowStep> steps = workflowService.deserializeSteps(chain.getStepsJson());
        WorkflowStep qaStep = steps.get(2);
        assertThat(qaStep.getStatus()).isEqualTo(WorkflowStep.Status.FAILED);
        assertThat(qaStep.getOutput()).contains("QA completed but no verdict submitted");
    }

    // ================================================================
    //  SEED INTEGRITY: template exists and instantiates
    // ================================================================

    @Test
    void seedIntegrity_templateExistsAndInstantiates() throws Exception {
        // Configure mock ADK to return instantly (no gating needed for this test)
        AtomicInteger adkCalls = new AtomicInteger(0);
        configureMockAdkNoGating(adkCalls);

        UUID templateId = UUID.randomUUID();

        // Create APPROVED WORKFLOW knowledge item
        KnowledgeItem templateItem = KnowledgeItem.builder()
                .id(templateId)
                .name("development-workflow")
                .type(KnowledgeType.WORKFLOW)
                .status(KnowledgeStatus.APPROVED)
                .sensitivity(Sensitivity.INTERNAL)
                .currentVersion("v1")
                .escalationCount(0)
                .createdAt(Instant.now())
                .build();
        knowledgeItemRepository.save(templateItem);

        // Create KnowledgeVersion with 3-step YAML
        String yamlContent = """
                schema_version: "1.0"
                name: development-workflow
                steps:
                  - agent_id: "%s"
                    prompt_template: "Write spec for {issueRef}"
                    max_iterations: 3
                    kind: BA
                  - agent_id: "%s"
                    prompt_template: "Implement {specRef}"
                    max_iterations: 5
                    kind: DEV
                  - agent_id: "%s"
                    prompt_template: "Verify {specRef}"
                    max_iterations: 3
                    kind: QA
                """.formatted(baAgentId.toString(), devAgentId.toString(), qaAgentId.toString());

        KnowledgeVersion version = KnowledgeVersion.builder()
                .knowledgeItemId(templateId)
                .version("v1")
                .status(VersionStatus.APPROVED)
                .yamlContent(yamlContent)
                .createdAt(Instant.now())
                .approvedAt(Instant.now())
                .build();
        knowledgeVersionRepository.save(version);

        // Verify the template item exists with APPROVED status
        KnowledgeItem found = knowledgeItemRepository.findById(templateId).orElseThrow();
        assertThat(found.getType()).isEqualTo(KnowledgeType.WORKFLOW);
        assertThat(found.getStatus()).isEqualTo(KnowledgeStatus.APPROVED);

        // Verify the version row contains the 3-step YAML
        KnowledgeVersion ver = knowledgeVersionRepository
                .findByKnowledgeItemIdAndVersion(templateId, "v1").orElseThrow();
        assertThat(ver.getYamlContent()).contains("kind: BA");
        assertThat(ver.getYamlContent()).contains("kind: DEV");
        assertThat(ver.getYamlContent()).contains("kind: QA");

        // Instantiate the template
        // specRef is a system placeholder (reserved for the coordinator to inject the
        // approved spec UUID); callers must NOT pass it - the whitelist rejects it.
        Map<String, String> params = Map.of("issueRef", "ISSUE-42");
        WorkflowResponse response = workflowTemplateService.instantiateTemplate(templateId, params);

        // Verify chain created
        UUID chainId = response.getId();
        WorkflowChain chain = workflowChainRepository.findById(chainId).orElseThrow();
        assertThat(chain.getName()).contains("development-workflow-instance");
        assertThat(chain.getSourceKnowledgeItemId()).isEqualTo(templateId);
        assertThat(chain.getStepsJson()).contains("ISSUE-42");

        // Verify DoD record initialised (SDD wiring from template service)
        DoDRecord dod = dodService.getStatus(chainId.toString());
        assertThat(dod).isNotNull();
        assertThat(dod.getCurrentStage()).isEqualTo("dev");
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

    /** Create an SDD chain with BA/DEV/QA steps and initialise the DoD record. */
    private WorkflowResponse createSddChain(String name) {
        WorkflowResponse response = workflowService.createAndStart(
                CreateWorkflowRequest.builder()
                        .name("sdd-" + name)
                        .steps(List.of(
                                stepDef(baAgentId, "Write spec for {issueRef}", 3,
                                        WorkflowStep.StepKind.BA),
                                stepDef(devAgentId, "Implement {specRef}", 5,
                                        WorkflowStep.StepKind.DEV),
                                stepDef(qaAgentId, "Verify {specRef}", 3,
                                        WorkflowStep.StepKind.QA)
                        ))
                        .build()
        );
        // Initialise DoD with custom stages [dev, qa] — this is normally done by
        // WorkflowTemplateService.instantiateTemplate but createAndStart does not do it.
        dodService.init(response.getId().toString(), "SDD", List.of("dev", "qa"));
        return response;
    }

    private static CreateWorkflowRequest.StepDef stepDef(UUID agentId, String prompt,
                                                          int maxIterations,
                                                          WorkflowStep.StepKind kind) {
        return CreateWorkflowRequest.StepDef.builder()
                .agentId(agentId)
                .promptTemplate(prompt)
                .maxIterations(maxIterations)
                .kind(kind)
                .build();
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
     * After the BA step completes, {@link io.aria.conductor.knowledge.sdd.SpecReviewCoordinator}
     * creates a SPEC_REVIEW approval for the BA run and sets the chain WAITING_APPROVAL.
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

    /** Configure mock ADK with QA gating via a gate queue. */
    private void configureMockAdk(BlockingQueue<CompletableFuture<Void>> qaGates,
                                  AtomicInteger adkCalls) {
        AdkProvider mock = org.mockito.Mockito.mock(AdkProvider.class);
        when(adkProviderRegistry.resolve(any())).thenReturn(mock);
        when(mock.isHealthy(any())).thenReturn(true);
        when(mock.parseActionsFromResponse(any())).thenReturn(List.of());
        when(mock.call(any(), any(), any())).thenAnswer(inv -> {
            // Gate ONLY runs of this test's QA agent: agent UUIDs are unique per test, so
            // leftover virtual threads from earlier tests (same shared mock) never interfere.
            UUID agentId = inv.getArgument(0);
            String output;
            if (qaAgentId.equals(agentId)) {
                output = QA_OUTPUT_PASS;
                CompletableFuture<Void> gate = new CompletableFuture<>();
                qaGates.offer(gate);
                gate.get(30, TimeUnit.SECONDS);
            } else if (devAgentId.equals(agentId)) {
                output = DEV_OUTPUT;
            } else {
                output = BA_OUTPUT;
            }
            return new LlmResponse(output, 10, 20, "stop", List.of());
        });
    }

    /** Configure mock ADK without any gating — all calls return immediately. */
    private void configureMockAdkNoGating(AtomicInteger adkCalls) {
        AdkProvider mock = org.mockito.Mockito.mock(AdkProvider.class);
        when(adkProviderRegistry.resolve(any())).thenReturn(mock);
        when(mock.isHealthy(any())).thenReturn(true);
        when(mock.parseActionsFromResponse(any())).thenReturn(List.of());
        when(mock.call(any(), any(), any())).thenAnswer(inv -> {
            UUID agentId = inv.getArgument(0);
            String output = qaAgentId.equals(agentId) ? QA_OUTPUT_PASS
                    : devAgentId.equals(agentId) ? DEV_OUTPUT : BA_OUTPUT;
            return new LlmResponse(output, 10, 20, "stop", List.of());
        });
    }

    /** Short random suffix for unique names. */
    private static String shortUuid() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
package io.aria.conductor.execution.listener;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aria.conductor.agent.repository.WorkflowChainRepository;
import io.aria.conductor.agent.service.WorkflowService;
import io.aria.conductor.common.event.RunCompletedEvent;
import io.aria.conductor.common.event.WorkflowAdvancedEvent;
import io.aria.conductor.common.model.RunStatus;
import io.aria.conductor.common.model.WorkflowChain;
import io.aria.conductor.common.model.WorkflowStep;
import io.aria.conductor.execution.dod.DoDRecord;
import io.aria.conductor.execution.dod.DoDService;
import io.aria.conductor.execution.git.GitHandoffMetadata;
import io.aria.conductor.execution.listener.ReportStepTreeGuard.ChangeType;
import io.aria.conductor.execution.listener.ReportStepTreeGuard.ChangedFile;
import io.aria.conductor.execution.listener.ReportStepTreeGuard.TreeDiff;
import io.aria.conductor.execution.listener.ReportStepTreeGuard.TreeDiffSupplier;
import io.aria.conductor.test.TestDataBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * F19: a QA report commit that removes source files must fail the QA step instead of
 * advancing on its {@code VERDICT=PASS}. The live chain accepted commit 984df552
 * ("sdd qa report", parent 451c4867), which removed 300 files and left only
 * {@code qa_report.md} on the chain branch.
 *
 * <p>The guard runs only on the PASS path of a QA-kind step, only for a chain that
 * carries a {@code repoUrl}, and never on the strength of an unreadable tree: a
 * supplier that fails or throws leaves the step's previous behaviour intact.
 */
@ExtendWith(MockitoExtension.class)
class WorkflowAutoChainerQaTreeGuardTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String REPO_URL = "https://github.com/owner/repo.git";
    private static final String TEMPLATE_PARAMS =
            "{\"issueRef\":\"#81\",\"repoUrl\":\"" + REPO_URL + "\"}";

    @Mock private WorkflowService workflowService;
    @Mock private DoDService dodService;
    @Mock private WorkflowChainRepository chainRepository;
    @Mock private ApplicationEventPublisher eventPublisher;

    private final UUID runId = UUID.randomUUID();
    private final UUID agentId = UUID.randomUUID();
    private final AtomicReference<String> requestedRepo = new AtomicReference<>();
    private final AtomicReference<String> requestedBranch = new AtomicReference<>();
    private WorkflowChain chain;

    @BeforeEach
    void setUp() {
        requestedRepo.set(null);
        requestedBranch.set(null);
    }

    private static final String CLEAN_QA_OUTPUT = """
            QA report

            - act-dashboard-api: 90 tests, 0 errors, 0 failures
            - AC1: PASS
            - AC2: PASS

            VERDICT=PASS
            """;

    @Test
    void passWithATreeThatRemovedSourceFiles_failsTheStepWithoutAdvancing() {
        prepareQaStep(TEMPLATE_PARAMS);
        WorkflowAutoChainer chainer = chainerSupplying(removalDiff(300));

        chainer.onRunCompleted(completed(CLEAN_QA_OUTPUT));

        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        verify(workflowService).markStepFailed(eq(chain.getId()), eq(0), message.capture());
        assertThat(message.getValue())
                .contains("removed 300")
                .contains(GitHandoffMetadata.branchName(chain.getId()))
                .contains("outside qa_report.md");
        assertThat(requestedRepo).hasValue(REPO_URL);
        assertThat(requestedBranch).hasValue(GitHandoffMetadata.branchName(chain.getId()));
        verify(workflowService, never()).advanceWorkflow(any(), anyInt(), any());
        verify(eventPublisher, never()).publishEvent(any(WorkflowAdvancedEvent.class));
        // No PASS is recorded on the DoD either.
        verify(dodService, never()).review(anyString(), anyString(), anyString(), anyBoolean(),
                any(), anyString(), anyString());
    }

    @Test
    void passWithACleanReportOnlyDiff_advances() {
        prepareQaStep(TEMPLATE_PARAMS);
        WorkflowAutoChainer chainer = chainerSupplying(Optional.of(new TreeDiff(
                GitHandoffMetadata.branchName(chain.getId()), "451c4867", "984df552",
                List.of(new ChangedFile("qa_report.md", ChangeType.ADDED)))));
        when(workflowService.advanceWorkflow(chain.getId(), 0, CLEAN_QA_OUTPUT)).thenReturn(true);

        chainer.onRunCompleted(completed(CLEAN_QA_OUTPUT));

        verify(workflowService).advanceWorkflow(chain.getId(), 0, CLEAN_QA_OUTPUT);
        verify(workflowService, never()).markStepFailed(any(), anyInt(), any());
    }

    /** A chain that is not Git-backed (no repoUrl) keeps its previous behaviour. */
    @Test
    void chainWithoutRepoUrl_advancesWithoutAskingTheSupplier() {
        prepareQaStep("{\"issueRef\":\"#81\"}");
        WorkflowAutoChainer chainer = chainerSupplying(removalDiff(300));
        when(workflowService.advanceWorkflow(chain.getId(), 0, CLEAN_QA_OUTPUT)).thenReturn(true);

        chainer.onRunCompleted(completed(CLEAN_QA_OUTPUT));

        assertThat(requestedRepo).hasValue(null);
        verify(workflowService).advanceWorkflow(chain.getId(), 0, CLEAN_QA_OUTPUT);
        verify(workflowService, never()).markStepFailed(any(), anyInt(), any());
    }

    /** An unreadable tree is an infrastructure condition, not a step failure. */
    @Test
    void unreadableTreeDiff_advancesUnguarded() {
        prepareQaStep(TEMPLATE_PARAMS);
        WorkflowAutoChainer chainer = chainerSupplying(Optional.empty());
        when(workflowService.advanceWorkflow(chain.getId(), 0, CLEAN_QA_OUTPUT)).thenReturn(true);

        chainer.onRunCompleted(completed(CLEAN_QA_OUTPUT));

        assertThat(requestedRepo).hasValue(REPO_URL);
        verify(workflowService).advanceWorkflow(chain.getId(), 0, CLEAN_QA_OUTPUT);
        verify(workflowService, never()).markStepFailed(any(), anyInt(), any());
    }

    @Test
    void supplierThatThrows_advancesUnguarded() {
        prepareQaStep(TEMPLATE_PARAMS);
        WorkflowAutoChainer chainer = new WorkflowAutoChainer(
                workflowService, dodService, chainRepository, eventPublisher,
                (repoUrl, branchName) -> {
                    requestedRepo.set(repoUrl);
                    throw new IllegalStateException("GitHub API request failed");
                });
        when(workflowService.advanceWorkflow(chain.getId(), 0, CLEAN_QA_OUTPUT)).thenReturn(true);

        chainer.onRunCompleted(completed(CLEAN_QA_OUTPUT));

        verify(workflowService).advanceWorkflow(chain.getId(), 0, CLEAN_QA_OUTPUT);
        verify(workflowService, never()).markStepFailed(any(), anyInt(), any());
    }

    /** DEFECT keeps rescheduling the DEV step; the report-tree guard does not apply. */
    @Test
    void defectVerdict_isNotGatedByTheTreeGuard() {
        prepareQaStep(TEMPLATE_PARAMS);
        WorkflowAutoChainer chainer = chainerSupplying(removalDiff(300));
        when(workflowService.findStepIndexByKind(chain, WorkflowStep.StepKind.DEV)).thenReturn(0);

        chainer.onRunCompleted(completed(CLEAN_QA_OUTPUT.replace("VERDICT=PASS", "VERDICT=DEFECT")));

        assertThat(requestedRepo).hasValue(null);
        verify(workflowService).rescheduleStep(eq(chain.getId()), eq(0), argThat(reason -> reason.contains("VERDICT=DEFECT")));
        verify(workflowService, never()).advanceWorkflow(any(), anyInt(), any());
    }

    // ---- helpers ----

    private WorkflowAutoChainer chainerSupplying(Optional<TreeDiff> diff) {
        return new WorkflowAutoChainer(workflowService, dodService, chainRepository, eventPublisher,
                recording(diff));
    }

    private TreeDiffSupplier recording(Optional<TreeDiff> diff) {
        return (repoUrl, branchName) -> {
            requestedRepo.set(repoUrl);
            requestedBranch.set(branchName);
            return diff;
        };
    }

    /** A diff exactly like the observed commit, labelled with the chain's real branch. */
    private Optional<TreeDiff> removalDiff(int removals) {
        List<ChangedFile> files = new ArrayList<>(IntStream.range(0, removals)
                .mapToObj(i -> new ChangedFile("agent-control-tower/src/main/File" + i + ".java",
                        ChangeType.REMOVED))
                .toList());
        files.add(new ChangedFile("qa_report.md", ChangeType.ADDED));
        return Optional.of(new TreeDiff(GitHandoffMetadata.branchName(chain.getId()),
                "451c4867", "984df552", files));
    }

    private void prepareQaStep(String templateParams) {
        WorkflowStep qaStep = WorkflowStep.builder()
                .agentId(agentId)
                .promptTemplate("qa prompt")
                .maxIterations(3)
                .kind(WorkflowStep.StepKind.QA)
                .runId(runId)
                .status(WorkflowStep.Status.RUNNING)
                .build();
        chain = TestDataBuilder.aWorkflowChain()
                .withName("sdd-loop")
                .withStatus(WorkflowChain.Status.RUNNING)
                .withStepsJson(stepsJson(List.of(qaStep)))
                .withTemplateParams(templateParams)
                .build();
        when(workflowService.findChainByRunId(runId)).thenReturn(chain);
        when(workflowService.findStepIndex(chain, runId)).thenReturn(0);
        when(workflowService.stepAt(chain, 0)).thenReturn(qaStep);
        when(dodService.getStatus(chain.getId().toString())).thenReturn(record());
    }

    private DoDRecord record() {
        return DoDRecord.builder()
                .id("dod-1")
                .taskId(chain.getId().toString())
                .taskType("sdd")
                .currentStage("qa")
                .overallStatus("IN_PROGRESS")
                .build();
    }

    private RunCompletedEvent completed(String finalOutput) {
        return new RunCompletedEvent(this, runId, agentId, RunStatus.COMPLETED, finalOutput);
    }

    private String stepsJson(List<WorkflowStep> steps) {
        try {
            return MAPPER.writeValueAsString(steps);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }
}

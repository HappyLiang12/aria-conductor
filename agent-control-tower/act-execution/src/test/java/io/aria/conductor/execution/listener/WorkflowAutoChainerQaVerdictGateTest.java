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
import io.aria.conductor.execution.dod.DoDStageReview;
import io.aria.conductor.test.TestDataBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * F22 gate integrity: a QA run may not end with {@code VERDICT=PASS} while its own
 * output records an acceptance criterion as {@code NOT VERIFIED}. The live run
 * 8c51fa8f did exactly that (AC4 "NOT VERIFIED (environment limitation)", tests
 * reporting 85 and 57 errors) and the chain completed.
 *
 * <p>The rule is deliberately narrow: it needs an AC context ({@code AC<n>} or
 * "Acceptance Criteria") on the same line as the phrase. A PASS that merely
 * mentions "NOT VERIFIED" somewhere else is still accepted.
 */
@ExtendWith(MockitoExtension.class)
class WorkflowAutoChainerQaVerdictGateTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Mock private WorkflowService workflowService;
    @Mock private DoDService dodService;
    @Mock private WorkflowChainRepository chainRepository;
    @Mock private ApplicationEventPublisher eventPublisher;

    private WorkflowAutoChainer chainer;

    private final UUID runId = UUID.randomUUID();
    private final UUID agentId = UUID.randomUUID();
    private WorkflowChain chain;

    @BeforeEach
    void setUp() {
        chainer = new WorkflowAutoChainer(workflowService, dodService, chainRepository, eventPublisher);
    }

    /**
     * The exact shape of the 8c51fa8f QA output: errored test runs, AC4 NOT VERIFIED
     * for an "environment limitation", and a PASS marker.
     */
    private static final String LIVED_QA_OUTPUT = """
            QA report

            - act-dashboard-api: 90 tests, 85 errors, 0 failures
            - *Sandbox*: 75 tests, 57 errors, 0 failures
            - AC1: PASS
            - AC2: PASS
            - AC3: PASS
            - AC4: NOT VERIFIED (environment limitation)

            VERDICT=PASS
            """;

    @Test
    void passWithAnUnverifiedAcceptanceCriterion_failsTheStep() {
        prepareQaStep(0);

        chainer.onRunCompleted(completed(LIVED_QA_OUTPUT));

        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        verify(workflowService).markStepFailed(eq(chain.getId()), eq(0), message.capture());
        assertThat(message.getValue())
                .contains("AC4")
                .contains("is not verified")
                .contains("VERDICT=DEFECT");
        verify(workflowService, never()).advanceWorkflow(any(), anyInt(), any());
        verify(eventPublisher, never()).publishEvent(any(WorkflowAdvancedEvent.class));
    }

    @Test
    void passWithAnUnverifiedAcceptanceCriterion_toolVerdictPathIsGatedToo() {
        // No marker: the verdict comes from submit_dod_review. The gate must read
        // the submitted review's comment, not just the run output.
        prepareQaStep(0);
        DoDRecord record = record();
        when(dodService.latestQaReview(record))
                .thenReturn(review("PASS", "AC2: NOT VERIFIED - the sandbox would not start"));

        chainer.onRunCompleted(completed("QA verification complete."));

        verify(workflowService).markStepFailed(eq(chain.getId()), eq(0), contains("AC2"));
        verify(workflowService, never()).advanceWorkflow(any(), anyInt(), any());
    }

    @Test
    void passMarkerWithAnAgreeingToolReviewThatRecordsNotVerified_failsTheStep() {
        // The marker is authoritative for routing, but a review submitted by the same
        // run must not smuggle an unverified criterion past the gate.
        prepareQaStep(0);
        DoDRecord record = record();
        when(dodService.latestQaReview(record))
                .thenReturn(review("PASS", "AC4: NOT VERIFIED (environment limitation)"));
        String output = "QA report\nAC1: PASS\nAC4: NOT VERIFIED\nVERDICT=PASS\n";

        chainer.onRunCompleted(completed(output));

        verify(workflowService).markStepFailed(eq(chain.getId()), eq(0), contains("AC4"));
        verify(workflowService, never()).advanceWorkflow(any(), anyInt(), any());
    }

    @Test
    void passMarkerWithAStaleDefectReview_isNotBlockedByItsOldComment() {
        // A previous DEFECT attempt may have recorded "AC4 NOT VERIFIED"; a fresh
        // PASS marker from a later run must not be gated on that stale text.
        prepareQaStep(0);
        DoDRecord record = record();
        when(dodService.latestQaReview(record))
                .thenReturn(review("DEFECT", "AC4: NOT VERIFIED on the earlier attempt"));
        String output = "QA report\nAC1: PASS\nAC4: PASS\nVERDICT=PASS\n";
        when(workflowService.advanceWorkflow(chain.getId(), 0, output)).thenReturn(true);

        chainer.onRunCompleted(completed(output));

        verify(workflowService).advanceWorkflow(chain.getId(), 0, output);
        verify(workflowService, never()).markStepFailed(any(), anyInt(), any());
    }

    @Test
    void passWithAnUnverifiedCriterionAndAPartialPassClause_stillFails() {
        // "but AC1-AC3 passed" is not the criterion being resolved; only a note
        // about the criterion's own re-run may exempt it.
        prepareQaStep(0);
        String output = """
                - AC4: NOT VERIFIED, but AC1-AC3 passed
                VERDICT=PASS
                """;

        chainer.onRunCompleted(completed(output));

        verify(workflowService).markStepFailed(eq(chain.getId()), eq(0), contains("AC4"));
        verify(workflowService, never()).advanceWorkflow(any(), anyInt(), any());
    }

    @Test
    void passWithAnUnverifiedCriterionInTheReviewEvidence_failsTheStep() {
        prepareQaStep(0);
        DoDRecord record = record();
        when(dodService.latestQaReview(record))
                .thenReturn(reviewWithEvidence("PASS", "all good", "AC4 not yet verified"));

        chainer.onRunCompleted(completed("QA verification complete."));

        verify(workflowService).markStepFailed(eq(chain.getId()), eq(0), contains("AC4"));
        verify(workflowService, never()).advanceWorkflow(any(), anyInt(), any());
    }

    @Test
    void passWithTheReversedPhraseOrder_failsTheStep() {
        prepareQaStep(0);
        String output = """
                NOT VERIFIED: AC3 (the browser matrix was unavailable)
                VERDICT=PASS
                """;

        chainer.onRunCompleted(completed(output));

        verify(workflowService).markStepFailed(eq(chain.getId()), eq(0), contains("AC3"));
        verify(workflowService, never()).advanceWorkflow(any(), anyInt(), any());
    }

    @Test
    void passWithAnAcsRangeAndNotYetVerified_failsTheStep() {
        prepareQaStep(0);
        String output = """
                - ACs 1-4 are not yet verified: the sandbox would not start
                VERDICT=PASS
                """;

        chainer.onRunCompleted(completed(output));

        verify(workflowService).markStepFailed(eq(chain.getId()), eq(0), contains("ACs 1-4"));
        verify(workflowService, never()).advanceWorkflow(any(), anyInt(), any());
    }

    @Test
    void passWithACriterionStatusOnTheNextLine_failsTheStep() {
        prepareQaStep(0);
        String output = """
                - AC4
                  Status: NOT VERIFIED (environment limitation)
                VERDICT=PASS
                """;

        chainer.onRunCompleted(completed(output));

        verify(workflowService).markStepFailed(eq(chain.getId()), eq(0), contains("AC4"));
        verify(workflowService, never()).advanceWorkflow(any(), anyInt(), any());
    }

    @Test
    void passWithAHistoricResolvedNote_advances() {
        // Negative control: a criterion that was unverified on an earlier attempt
        // but passed on the re-run is history, not a live gap.
        prepareQaStep(0);
        String output = """
                - AC4 was NOT VERIFIED on the first attempt but passed on re-run
                VERDICT=PASS
                """;
        when(workflowService.advanceWorkflow(chain.getId(), 0, output)).thenReturn(true);

        chainer.onRunCompleted(completed(output));

        verify(workflowService).advanceWorkflow(chain.getId(), 0, output);
        verify(workflowService, never()).markStepFailed(any(), anyInt(), any());
    }

    @Test
    void passWithACContextPhraseButNoNotVerified_advances() {
        prepareQaStep(0);
        String output = """
                - act-dashboard-api: 90 tests, 0 errors, 0 failures
                - AC1: PASS
                - AC2: PASS
                - AC3: PASS
                - AC4: PASS (verified with mvn -pl act-dashboard-api test)

                VERDICT=PASS
                """;
        when(workflowService.advanceWorkflow(chain.getId(), 0, output)).thenReturn(true);

        chainer.onRunCompleted(completed(output));

        verify(workflowService).advanceWorkflow(chain.getId(), 0, output);
        verify(workflowService, never()).markStepFailed(any(), anyInt(), any());
    }

    @Test
    void passThatMentionsNotVerifiedWithoutAnAcceptanceContext_advances() {
        // Negative control: the phrase alone is not a criterion, and must not block a
        // legitimate PASS (a report may discuss the phrase or another run).
        prepareQaStep(0);
        String output = """
                - AC1: PASS
                - Note: the unrelated legacy spike stays NOT VERIFIED, out of scope here.
                - Sandbox warmup logs: verified.

                VERDICT=PASS
                """;
        when(workflowService.advanceWorkflow(chain.getId(), 0, output)).thenReturn(true);

        chainer.onRunCompleted(completed(output));

        verify(workflowService).advanceWorkflow(chain.getId(), 0, output);
        verify(workflowService, never()).markStepFailed(any(), anyInt(), any());
    }

    @Test
    void defectWithUnverifiedCriterion_stillReschedulesDev() {
        prepareQaStep(1);
        when(workflowService.findStepIndexByKind(chain, WorkflowStep.StepKind.DEV)).thenReturn(0);

        chainer.onRunCompleted(completed(LIVED_QA_OUTPUT.replace("VERDICT=PASS", "VERDICT=DEFECT")));

        // F26: the retry prompt's feedback must carry what the QA actually
        // reported, not the routing placeholder alone.
        verify(workflowService).rescheduleStep(eq(chain.getId()), eq(0),
                argThat(reason -> reason.contains("VERDICT=DEFECT")
                        && reason.contains("AC4: NOT VERIFIED")));
        verify(workflowService, never()).markStepFailed(any(), anyInt(), any());
        verify(workflowService, never()).advanceWorkflow(any(), anyInt(), any());
    }

    @Test
    void noVerdictAnywhere_stillFailsWithTheRetryHint() {
        prepareQaStep(0);
        DoDRecord record = record();
        when(dodService.latestQaReview(record)).thenReturn(review(null, null));

        chainer.onRunCompleted(completed("no marker, no tool verdict"));

        verify(workflowService).markStepFailed(eq(chain.getId()), eq(0), contains("submit_dod_review"));
        verify(workflowService, never()).advanceWorkflow(any(), anyInt(), any());
    }

    // ---- helpers ----

    private void prepareQaStep(int qaIndex) {
        WorkflowStep qaStep = WorkflowStep.builder()
                .agentId(agentId)
                .promptTemplate("qa prompt")
                .maxIterations(3)
                .kind(WorkflowStep.StepKind.QA)
                .runId(runId)
                .status(WorkflowStep.Status.RUNNING)
                .build();
        WorkflowStep devStep = WorkflowStep.builder()
                .agentId(agentId)
                .promptTemplate("dev prompt")
                .maxIterations(3)
                .kind(WorkflowStep.StepKind.DEV)
                .status(WorkflowStep.Status.COMPLETED)
                .build();
        List<WorkflowStep> steps = qaIndex == 0 ? List.of(qaStep) : List.of(devStep, qaStep);
        chain = TestDataBuilder.aWorkflowChain()
                .withName("sdd-loop")
                .withStatus(WorkflowChain.Status.RUNNING)
                .withStepsJson(stepsJson(steps))
                .build();
        when(workflowService.findChainByRunId(runId)).thenReturn(chain);
        when(workflowService.findStepIndex(chain, runId)).thenReturn(qaIndex);
        when(workflowService.stepAt(chain, qaIndex)).thenReturn(qaStep);
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

    private DoDStageReview review(String verdict, String comment) {
        return reviewWithEvidence(verdict, comment, null);
    }

    private DoDStageReview reviewWithEvidence(String verdict, String comment, String evidence) {
        return DoDStageReview.builder()
                .id(UUID.randomUUID().toString())
                .dodId("dod-1")
                .stage("qa")
                .reviewerId("qa-agent")
                .passed("PASS".equals(verdict))
                .verdict(verdict)
                .comment(comment)
                .evidence(evidence)
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

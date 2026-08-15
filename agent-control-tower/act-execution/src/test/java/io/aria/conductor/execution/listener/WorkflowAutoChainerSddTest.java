package io.aria.conductor.execution.listener;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aria.conductor.agent.repository.WorkflowChainRepository;
import io.aria.conductor.agent.service.WorkflowService;
import io.aria.conductor.common.event.BaStepCompletedEvent;
import io.aria.conductor.common.event.RunCompletedEvent;
import io.aria.conductor.common.event.WorkflowAdvancedEvent;
import io.aria.conductor.common.model.RunStatus;
import io.aria.conductor.common.model.WorkflowChain;
import io.aria.conductor.common.model.WorkflowStep;
import io.aria.conductor.execution.adk.opencode.OpenCodeAdkProvider;
import io.aria.conductor.execution.dod.DoDRecord;
import io.aria.conductor.execution.dod.DoDService;
import io.aria.conductor.execution.dod.DoDStageReview;
import io.aria.conductor.execution.git.GitBranchService;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SDD kind-routing matrix for {@link WorkflowAutoChainer} (9 cases):
 * BA hands off to the spec coordinator via {@link BaStepCompletedEvent} (no advance),
 * DEV auto-submits the dev-stage DoD review then advances,
 * QA routes on its recorded verdict (PASS advance / DEFECT re-schedule Dev /
 * SPEC_GAP re-schedule BA / missing verdict fails the chain),
 * GENERIC keeps the existing linear advance, and failed runs mark the step failed.
 */
@ExtendWith(MockitoExtension.class)
class WorkflowAutoChainerSddTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final UUID REPORT_ARTIFACT_ID = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");

    @Mock private WorkflowService workflowService;
    @Mock private DoDService dodService;
    @Mock private WorkflowChainRepository chainRepository;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private GitBranchService gitBranchService;
    @Mock private OpenCodeAdkProvider openCodeAdkProvider;

    private WorkflowAutoChainer chainer;

    private final UUID runId = UUID.randomUUID();
    private final UUID agentId = UUID.randomUUID();
    private WorkflowChain chain;

    @BeforeEach
    void setUp() {
        chainer = new WorkflowAutoChainer(workflowService, dodService, chainRepository, eventPublisher,
                gitBranchService, openCodeAdkProvider);
    }

    // ---- 1. BA: hand off to the coordinator, do NOT advance ----

    @Test
    void baCompletion_publishesBaStepCompletedEvent_doesNotAdvance() {
        WorkflowStep baStep = step(WorkflowStep.StepKind.BA, runId);
        chain = chainWith(baStep);
        when(workflowService.findChainByRunId(runId)).thenReturn(chain);
        when(workflowService.findStepIndex(chain, runId)).thenReturn(0);
        when(workflowService.stepAt(chain, 0)).thenReturn(baStep);

        chainer.onRunCompleted(completed(RunStatus.COMPLETED, "spec draft markdown"));

        ArgumentCaptor<BaStepCompletedEvent> captor = ArgumentCaptor.forClass(BaStepCompletedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        BaStepCompletedEvent event = captor.getValue();
        assertThat(event.getChainId()).isEqualTo(chain.getId());
        assertThat(event.getBaStepIndex()).isEqualTo(0);
        assertThat(event.getBaRunId()).isEqualTo(runId);
        assertThat(event.getFinalOutput()).isEqualTo("spec draft markdown");

        verify(workflowService, never()).advanceWorkflow(any(), anyInt(), any());
        verify(eventPublisher, never()).publishEvent(any(WorkflowAdvancedEvent.class));
    }

    // ---- 2. DEV: DoD at dev stage -> auto-submit dev review + advance ----

    @Test
    void devCompletion_whenDoDAtDev_submitsDevReviewAndAdvances() {
        WorkflowStep devStep = step(WorkflowStep.StepKind.DEV, runId);
        chain = chainWith(devStep);
        when(workflowService.findChainByRunId(runId)).thenReturn(chain);
        when(workflowService.findStepIndex(chain, runId)).thenReturn(0);
        when(workflowService.stepAt(chain, 0)).thenReturn(devStep);
        when(dodService.getStatus(chain.getId().toString()))
                .thenReturn(record("dev"));
        when(workflowService.advanceWorkflow(chain.getId(), 0, "implementation done"))
                .thenReturn(true);

        chainer.onRunCompleted(completed(RunStatus.COMPLETED, "implementation done"));

        verify(dodService).submitStageReview(chain.getId().toString(),
                "engine", "SDD Engine", true, "auto: dev step completed");
        verify(workflowService).advanceWorkflow(chain.getId(), 0, "implementation done");
        verifyAdvancedEvent(0, 1, WorkflowChain.Status.RUNNING);
    }

    // ---- 3. DEV rework: DoD already at qa -> skip dev review, still advance ----

    @Test
    void devCompletion_whenDoDAtQa_skipsDevReviewAndAdvances() {
        WorkflowStep devStep = step(WorkflowStep.StepKind.DEV, runId);
        chain = chainWith(devStep);
        when(workflowService.findChainByRunId(runId)).thenReturn(chain);
        when(workflowService.findStepIndex(chain, runId)).thenReturn(0);
        when(workflowService.stepAt(chain, 0)).thenReturn(devStep);
        when(dodService.getStatus(chain.getId().toString()))
                .thenReturn(record("qa"));
        when(workflowService.advanceWorkflow(chain.getId(), 0, "rework done"))
                .thenReturn(true);

        chainer.onRunCompleted(completed(RunStatus.COMPLETED, "rework done"));

        verify(dodService, never()).submitStageReview(anyString(), anyString(), anyString(), anyBoolean(), anyString());
        verify(workflowService).advanceWorkflow(chain.getId(), 0, "rework done");
        verifyAdvancedEvent(0, 1, WorkflowChain.Status.RUNNING);
    }

    // ---- 3b. DEV backend push fallback: no new commit -> sandbox git commit+push ----

    @Test
    void devCompletion_noNewCommit_triggersBackendPush() {
        WorkflowStep devStep = step(WorkflowStep.StepKind.DEV, runId);
        String repoUrl = "https://github.com/acme/repo.git";
        chain = chainWithTemplateParams(devStep,
                "{\"repoUrl\":\"" + repoUrl + "\",\"specCommitSha\":\"base-sha-1\"}");
        when(workflowService.findChainByRunId(runId)).thenReturn(chain);
        when(workflowService.findStepIndex(chain, runId)).thenReturn(0);
        when(workflowService.stepAt(chain, 0)).thenReturn(devStep);
        when(dodService.getStatus(chain.getId().toString())).thenReturn(record("dev"));
        when(workflowService.advanceWorkflow(chain.getId(), 0, "dev done")).thenReturn(true);
        when(gitBranchService.branchHeadSha(repoUrl, "sdd/" + chain.getId()))
                .thenReturn(Optional.of("base-sha-1"));

        chainer.onRunCompleted(completed(RunStatus.COMPLETED, "dev done"));

        verify(openCodeAdkProvider).runSandboxCommand(eq(agentId),
                contains("git push origin sdd/" + chain.getId()));
        verify(workflowService).advanceWorkflow(chain.getId(), 0, "dev done");
    }

    @Test
    void devCompletion_branchHeadAdvanced_skipsFallback() {
        WorkflowStep devStep = step(WorkflowStep.StepKind.DEV, runId);
        String repoUrl = "https://github.com/acme/repo.git";
        chain = chainWithTemplateParams(devStep,
                "{\"repoUrl\":\"" + repoUrl + "\",\"specCommitSha\":\"base-sha-1\"}");
        when(workflowService.findChainByRunId(runId)).thenReturn(chain);
        when(workflowService.findStepIndex(chain, runId)).thenReturn(0);
        when(workflowService.stepAt(chain, 0)).thenReturn(devStep);
        when(dodService.getStatus(chain.getId().toString())).thenReturn(record("dev"));
        when(workflowService.advanceWorkflow(chain.getId(), 0, "dev done")).thenReturn(true);
        when(gitBranchService.branchHeadSha(repoUrl, "sdd/" + chain.getId()))
                .thenReturn(Optional.of("advanced-sha-2"));

        chainer.onRunCompleted(completed(RunStatus.COMPLETED, "dev done"));

        verify(openCodeAdkProvider, never()).runSandboxCommand(any(), anyString());
        verify(workflowService).advanceWorkflow(chain.getId(), 0, "dev done");
    }

    // ---- 4. QA: verdict PASS -> advance (and capture REPORT_ID) ----

    @Test
    void qaCompletion_verdictPass_advances() {
        WorkflowStep qaStep = step(WorkflowStep.StepKind.QA, runId);
        chain = chainWith(qaStep);
        when(workflowService.findChainByRunId(runId)).thenReturn(chain);
        when(workflowService.findStepIndex(chain, runId)).thenReturn(0);
        when(workflowService.stepAt(chain, 0)).thenReturn(qaStep);
        DoDRecord record = record("qa");
        when(dodService.getStatus(chain.getId().toString())).thenReturn(record);
        when(dodService.latestQaReview(record)).thenReturn(review("PASS", "looks good"));
        when(workflowService.advanceWorkflow(chain.getId(), 0, outputWithReportId()))
                .thenReturn(true);

        chainer.onRunCompleted(completed(RunStatus.COMPLETED, outputWithReportId()));

        verify(workflowService).advanceWorkflow(chain.getId(), 0, outputWithReportId());
        assertThat(chain.getReportArtifactId()).isEqualTo(REPORT_ARTIFACT_ID);
        verify(chainRepository).save(chain);
        verifyAdvancedEvent(0, 1, WorkflowChain.Status.RUNNING);
    }

    // ---- 5. QA: verdict DEFECT -> re-schedule the DEV step with feedback ----

    @Test
    void qaCompletion_verdictDefect_reschedulesDevStep() {
        WorkflowStep devStep = step(WorkflowStep.StepKind.DEV, UUID.randomUUID());
        WorkflowStep qaStep = step(WorkflowStep.StepKind.QA, runId);
        chain = chainWith(devStep, qaStep);
        when(workflowService.findChainByRunId(runId)).thenReturn(chain);
        when(workflowService.findStepIndex(chain, runId)).thenReturn(1);
        when(workflowService.stepAt(chain, 1)).thenReturn(qaStep);
        DoDRecord record = record("qa");
        when(dodService.getStatus(chain.getId().toString())).thenReturn(record);
        when(dodService.latestQaReview(record)).thenReturn(review("DEFECT", "parser crashes on empty input"));
        when(workflowService.findStepIndexByKind(chain, WorkflowStep.StepKind.DEV)).thenReturn(0);

        chainer.onRunCompleted(completed(RunStatus.COMPLETED, "qa output"));

        verify(workflowService).rescheduleStep(chain.getId(), 0, "parser crashes on empty input");
        verify(workflowService, never()).advanceWorkflow(any(), anyInt(), any());
        verify(eventPublisher, never()).publishEvent(any(WorkflowAdvancedEvent.class));
    }

    // ---- 5b. QA: verdict DEFECT -> QA step must be COMPLETED, not left RUNNING ----

    @Test
    void qaCompletion_defect_updatesQaStepStatus() {
        WorkflowStep devStep = step(WorkflowStep.StepKind.DEV, UUID.randomUUID());
        WorkflowStep qaStep = step(WorkflowStep.StepKind.QA, runId);
        chain = chainWith(devStep, qaStep);
        when(workflowService.findChainByRunId(runId)).thenReturn(chain);
        when(workflowService.findStepIndex(chain, runId)).thenReturn(1);
        when(workflowService.stepAt(chain, 1)).thenReturn(qaStep);
        when(dodService.getStatus(chain.getId().toString())).thenReturn(record("qa"));
        when(dodService.latestQaReview(any())).thenReturn(review("DEFECT", "parser crashes"));
        when(workflowService.findStepIndexByKind(chain, WorkflowStep.StepKind.DEV)).thenReturn(0);
        // Stub for completeQaStep — the fix adds step-status update before rescheduling
        when(workflowService.deserializeSteps(chain.getStepsJson()))
                .thenReturn(new ArrayList<>(List.of(devStep, qaStep)));
        when(workflowService.serializeSteps(any())).thenReturn(chain.getStepsJson());

        chainer.onRunCompleted(completed(RunStatus.COMPLETED, "qa output"));

        // QA step must be COMPLETED, not still RUNNING
        assertThat(qaStep.getStatus()).isEqualTo(WorkflowStep.Status.COMPLETED);
        assertThat(qaStep.getOutput()).isEqualTo("qa output");
        // DEFECT branch still reschedules DEV step
        verify(workflowService).rescheduleStep(chain.getId(), 0, "parser crashes");
        verify(chainRepository).save(chain);
    }

    // ---- 5c. QA: verdict SPEC_GAP -> QA step must be COMPLETED ----

    @Test
    void qaCompletion_specGap_updatesQaStepStatus() {
        WorkflowStep baStep = step(WorkflowStep.StepKind.BA, UUID.randomUUID());
        WorkflowStep devStep = step(WorkflowStep.StepKind.DEV, UUID.randomUUID());
        WorkflowStep qaStep = step(WorkflowStep.StepKind.QA, runId);
        chain = chainWith(baStep, devStep, qaStep);
        when(workflowService.findChainByRunId(runId)).thenReturn(chain);
        when(workflowService.findStepIndex(chain, runId)).thenReturn(2);
        when(workflowService.stepAt(chain, 2)).thenReturn(qaStep);
        when(dodService.getStatus(chain.getId().toString())).thenReturn(record("qa"));
        when(dodService.latestQaReview(any())).thenReturn(review("SPEC_GAP", "auth flows missing"));
        when(workflowService.findStepIndexByKind(chain, WorkflowStep.StepKind.BA)).thenReturn(0);
        when(workflowService.deserializeSteps(chain.getStepsJson()))
                .thenReturn(new ArrayList<>(List.of(baStep, devStep, qaStep)));
        when(workflowService.serializeSteps(any())).thenReturn(chain.getStepsJson());

        chainer.onRunCompleted(completed(RunStatus.COMPLETED, "qa output"));

        assertThat(qaStep.getStatus()).isEqualTo(WorkflowStep.Status.COMPLETED);
        assertThat(qaStep.getOutput()).isEqualTo("qa output");
        // SPEC_GAP branch still reschedules BA step
        verify(workflowService).rescheduleStep(chain.getId(), 0, "auth flows missing");
        verify(chainRepository).save(chain);
    }

    // ---- 6. QA: verdict SPEC_GAP -> re-schedule the BA step with feedback ----

    @Test
    void qaCompletion_verdictSpecGap_reschedulesBaStep() {
        WorkflowStep baStep = step(WorkflowStep.StepKind.BA, UUID.randomUUID());
        WorkflowStep devStep = step(WorkflowStep.StepKind.DEV, UUID.randomUUID());
        WorkflowStep qaStep = step(WorkflowStep.StepKind.QA, runId);
        chain = chainWith(baStep, devStep, qaStep);
        when(workflowService.findChainByRunId(runId)).thenReturn(chain);
        when(workflowService.findStepIndex(chain, runId)).thenReturn(2);
        when(workflowService.stepAt(chain, 2)).thenReturn(qaStep);
        DoDRecord record = record("qa");
        when(dodService.getStatus(chain.getId().toString())).thenReturn(record);
        when(dodService.latestQaReview(record)).thenReturn(review("SPEC_GAP", "auth flows not covered"));
        when(workflowService.findStepIndexByKind(chain, WorkflowStep.StepKind.BA)).thenReturn(0);

        chainer.onRunCompleted(completed(RunStatus.COMPLETED, "qa output"));

        verify(workflowService).rescheduleStep(chain.getId(), 0, "auth flows not covered");
        verify(workflowService, never()).advanceWorkflow(any(), anyInt(), any());
        verify(eventPublisher, never()).publishEvent(any(WorkflowAdvancedEvent.class));
    }

    // ---- 7. QA: no verdict submitted -> fail the chain explicitly ----

    @Test
    void qaCompletion_noVerdict_failsChain() {
        WorkflowStep qaStep = step(WorkflowStep.StepKind.QA, runId);
        chain = chainWith(qaStep);
        when(workflowService.findChainByRunId(runId)).thenReturn(chain);
        when(workflowService.findStepIndex(chain, runId)).thenReturn(0);
        when(workflowService.stepAt(chain, 0)).thenReturn(qaStep);
        DoDRecord record = record("qa");
        when(dodService.getStatus(chain.getId().toString())).thenReturn(record);
        when(dodService.latestQaReview(record)).thenReturn(review(null, null));

        chainer.onRunCompleted(completed(RunStatus.COMPLETED, "qa output"));

        verify(workflowService).markStepFailed(eq(chain.getId()), eq(0), contains("submit_dod_review"));
        verify(workflowService, never()).advanceWorkflow(any(), anyInt(), any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    // ---- 7b. QA: VERDICT= marker (no tool verdict) routes like the tool path ----

    @Test
    void qaCompletion_verdictMarkerPass_routesToPassWithoutToolCall() {
        WorkflowStep qaStep = step(WorkflowStep.StepKind.QA, runId);
        chain = chainWith(qaStep);
        when(workflowService.findChainByRunId(runId)).thenReturn(chain);
        when(workflowService.findStepIndex(chain, runId)).thenReturn(0);
        when(workflowService.stepAt(chain, 0)).thenReturn(qaStep);
        DoDRecord record = record("qa");
        when(dodService.getStatus(chain.getId().toString())).thenReturn(record);
        // No tool verdict recorded — the chainer must fall back to the output marker.
        when(dodService.latestQaReview(record)).thenReturn(review(null, null));
        String output = "QA complete.\nVERDICT=PASS\n";
        when(workflowService.advanceWorkflow(chain.getId(), 0, output)).thenReturn(true);

        chainer.onRunCompleted(completed(RunStatus.COMPLETED, output));

        verify(workflowService).advanceWorkflow(chain.getId(), 0, output);
        verifyAdvancedEvent(0, 1, WorkflowChain.Status.RUNNING);
    }

    // ---- 7c. QA: VERDICT=PASS marker must record the DoD qa review (R8-F2) ----

    @Test
    void qaCompletion_verdictMarkerPass_recordsDoDQaReview() {
        WorkflowStep qaStep = step(WorkflowStep.StepKind.QA, runId);
        chain = chainWith(qaStep);
        when(workflowService.findChainByRunId(runId)).thenReturn(chain);
        when(workflowService.findStepIndex(chain, runId)).thenReturn(0);
        when(workflowService.stepAt(chain, 0)).thenReturn(qaStep);
        DoDRecord record = record("qa");
        when(dodService.getStatus(chain.getId().toString())).thenReturn(record);
        // No tool verdict recorded — the marker path must record the qa review itself.
        when(dodService.latestQaReview(record)).thenReturn(review(null, null));
        String output = "QA complete.\nVERDICT=PASS\n";
        when(workflowService.advanceWorkflow(chain.getId(), 0, output)).thenReturn(true);

        chainer.onRunCompleted(completed(RunStatus.COMPLETED, output));

        verify(dodService).submitStageReview(chain.getId().toString(),
                "engine", "SDD Engine", true, "verdict from output marker");
        verify(workflowService).advanceWorkflow(chain.getId(), 0, output);
        verifyAdvancedEvent(0, 1, WorkflowChain.Status.RUNNING);
    }

    @Test
    void qaCompletion_verdictMarkerDefect_reschedulesDev() {
        WorkflowStep devStep = step(WorkflowStep.StepKind.DEV, UUID.randomUUID());
        WorkflowStep qaStep = step(WorkflowStep.StepKind.QA, runId);
        chain = chainWith(devStep, qaStep);
        when(workflowService.findChainByRunId(runId)).thenReturn(chain);
        when(workflowService.findStepIndex(chain, runId)).thenReturn(1);
        when(workflowService.stepAt(chain, 1)).thenReturn(qaStep);
        DoDRecord record = record("qa");
        when(dodService.getStatus(chain.getId().toString())).thenReturn(record);
        when(dodService.latestQaReview(record)).thenReturn(review(null, null));
        when(workflowService.findStepIndexByKind(chain, WorkflowStep.StepKind.DEV)).thenReturn(0);

        chainer.onRunCompleted(completed(RunStatus.COMPLETED, "Parser crashes.\nVERDICT=DEFECT"));

        verify(workflowService).rescheduleStep(chain.getId(), 0, "verdict from output marker");
        verify(workflowService, never()).advanceWorkflow(any(), anyInt(), any());
        verify(eventPublisher, never()).publishEvent(any(WorkflowAdvancedEvent.class));
    }

    @Test
    void qaCompletion_verdictMarkerDefect_recordsDoDQaReview() {
        WorkflowStep devStep = step(WorkflowStep.StepKind.DEV, UUID.randomUUID());
        WorkflowStep qaStep = step(WorkflowStep.StepKind.QA, runId);
        chain = chainWith(devStep, qaStep);
        when(workflowService.findChainByRunId(runId)).thenReturn(chain);
        when(workflowService.findStepIndex(chain, runId)).thenReturn(1);
        when(workflowService.stepAt(chain, 1)).thenReturn(qaStep);
        DoDRecord record = record("qa");
        when(dodService.getStatus(chain.getId().toString())).thenReturn(record);
        when(dodService.latestQaReview(record)).thenReturn(review(null, null));
        when(workflowService.findStepIndexByKind(chain, WorkflowStep.StepKind.DEV)).thenReturn(0);

        chainer.onRunCompleted(completed(RunStatus.COMPLETED, "Parser crashes.\nVERDICT=DEFECT"));

        verify(dodService).submitStageReview(chain.getId().toString(),
                "engine", "SDD Engine", false, "verdict from output marker");
        verify(workflowService).rescheduleStep(chain.getId(), 0, "verdict from output marker");
        verify(workflowService, never()).advanceWorkflow(any(), anyInt(), any());
    }

    @Test
    void qaCompletion_verdictMarkerSpecGap_reschedulesBa() {
        WorkflowStep baStep = step(WorkflowStep.StepKind.BA, UUID.randomUUID());
        WorkflowStep devStep = step(WorkflowStep.StepKind.DEV, UUID.randomUUID());
        WorkflowStep qaStep = step(WorkflowStep.StepKind.QA, runId);
        chain = chainWith(baStep, devStep, qaStep);
        when(workflowService.findChainByRunId(runId)).thenReturn(chain);
        when(workflowService.findStepIndex(chain, runId)).thenReturn(2);
        when(workflowService.stepAt(chain, 2)).thenReturn(qaStep);
        DoDRecord record = record("qa");
        when(dodService.getStatus(chain.getId().toString())).thenReturn(record);
        when(dodService.latestQaReview(record)).thenReturn(review(null, null));
        when(workflowService.findStepIndexByKind(chain, WorkflowStep.StepKind.BA)).thenReturn(0);

        // Lowercase + spaces exercise the case-insensitive, whitespace-tolerant regex.
        chainer.onRunCompleted(completed(RunStatus.COMPLETED, "Auth flows missing.\nverdict = spec_gap"));

        verify(workflowService).rescheduleStep(chain.getId(), 0, "verdict from output marker");
        verify(workflowService, never()).advanceWorkflow(any(), anyInt(), any());
        verify(eventPublisher, never()).publishEvent(any(WorkflowAdvancedEvent.class));
    }

    @Test
    void qaCompletion_noMarkerNoToolCall_failsWithRetryHint() {
        WorkflowStep qaStep = step(WorkflowStep.StepKind.QA, runId);
        chain = chainWith(qaStep);
        when(workflowService.findChainByRunId(runId)).thenReturn(chain);
        when(workflowService.findStepIndex(chain, runId)).thenReturn(0);
        when(workflowService.stepAt(chain, 0)).thenReturn(qaStep);
        DoDRecord record = record("qa");
        when(dodService.getStatus(chain.getId().toString())).thenReturn(record);
        when(dodService.latestQaReview(record)).thenReturn(review(null, null));

        chainer.onRunCompleted(completed(RunStatus.COMPLETED, "no verdict anywhere"));

        verify(workflowService).markStepFailed(eq(chain.getId()), eq(0), contains("submit_dod_review"));
        verify(workflowService, never()).advanceWorkflow(any(), anyInt(), any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    // ---- 8. GENERIC: existing linear advance, DoD untouched ----

    @Test
    void genericCompletion_advancesUnchanged() {
        WorkflowStep genericStep = step(WorkflowStep.StepKind.GENERIC, runId);
        chain = chainWith(genericStep);
        when(workflowService.findChainByRunId(runId)).thenReturn(chain);
        when(workflowService.findStepIndex(chain, runId)).thenReturn(0);
        when(workflowService.stepAt(chain, 0)).thenReturn(genericStep);
        when(workflowService.advanceWorkflow(chain.getId(), 0, "generic out")).thenReturn(true);

        chainer.onRunCompleted(completed(RunStatus.COMPLETED, "generic out"));

        verify(workflowService).advanceWorkflow(chain.getId(), 0, "generic out");
        verify(dodService, never()).getStatus(anyString());
        verifyAdvancedEvent(0, 1, WorkflowChain.Status.RUNNING);
    }

    // ---- 9. FAILED run: mark failed unchanged (even for BA-kind steps) ----

    @Test
    void failedRun_marksStepFailed_unchanged() {
        WorkflowStep baStep = step(WorkflowStep.StepKind.BA, runId);
        chain = chainWith(baStep);
        when(workflowService.findChainByRunId(runId)).thenReturn(chain);
        when(workflowService.findStepIndex(chain, runId)).thenReturn(0);

        chainer.onRunCompleted(completed(RunStatus.FAILED, "LLM quota exceeded"));

        verify(workflowService).markStepFailed(chain.getId(), 0, "Run failed: LLM quota exceeded");
        verify(workflowService, never()).advanceWorkflow(any(), anyInt(), any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    // ---- 10. SDD chain without DoD record: chainer must init DoD on first step ----

    @Test
    void firstSddStep_initsDoDWhenAbsent() {
        // BA step completes on a chain created via API (createAndStart), which has
        // no DoD record. The chainer must initialize DoD before routing the step.
        WorkflowStep baStep = step(WorkflowStep.StepKind.BA, runId);
        chain = chainWith(baStep);
        when(workflowService.findChainByRunId(runId)).thenReturn(chain);
        when(workflowService.findStepIndex(chain, runId)).thenReturn(0);
        when(workflowService.stepAt(chain, 0)).thenReturn(baStep);
        // No DoD record exists — dodService.getStatus throws
        when(dodService.getStatus(chain.getId().toString()))
                .thenThrow(new IllegalStateException("No DoD record"));

        chainer.onRunCompleted(completed(RunStatus.COMPLETED, "spec draft"));

        // MUST initialize DoD for SDD chains without an existing record
        verify(dodService).init(chain.getId().toString(), "SDD", List.of("dev", "qa"));
        // After DoD init, BA routing proceeds as normal
        verify(eventPublisher).publishEvent(any(BaStepCompletedEvent.class));
    }

    // ---- helpers ----

    private RunCompletedEvent completed(RunStatus status, String finalOutput) {
        return new RunCompletedEvent(this, runId, agentId, status, finalOutput);
    }

    private WorkflowStep step(WorkflowStep.StepKind kind, UUID stepRunId) {
        return WorkflowStep.builder()
                .agentId(agentId)
                .promptTemplate("prompt for " + kind)
                .maxIterations(3)
                .kind(kind)
                .runId(stepRunId)
                .status(WorkflowStep.Status.RUNNING)
                .build();
    }

    private WorkflowChain chainWith(WorkflowStep... steps) {
        return TestDataBuilder.aWorkflowChain()
                .withName("sdd-loop")
                .withStatus(WorkflowChain.Status.RUNNING)
                .withStepsJson(stepsJson(steps))
                .build();
    }

    private WorkflowChain chainWithTemplateParams(WorkflowStep step, String templateParams) {
        return TestDataBuilder.aWorkflowChain()
                .withName("sdd-loop")
                .withStatus(WorkflowChain.Status.RUNNING)
                .withStepsJson(stepsJson(step))
                .withTemplateParams(templateParams)
                .build();
    }

    private String stepsJson(WorkflowStep... steps) {
        try {
            return MAPPER.writeValueAsString(List.of(steps));
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    private DoDRecord record(String currentStage) {
        return DoDRecord.builder()
                .id("dod-1")
                .taskId(chain.getId().toString())
                .taskType("sdd")
                .currentStage(currentStage)
                .overallStatus("IN_PROGRESS")
                .build();
    }

    private DoDStageReview review(String verdict, String comment) {
        return DoDStageReview.builder()
                .id(UUID.randomUUID().toString())
                .dodId("dod-1")
                .stage("qa")
                .reviewerId("qa-agent")
                .passed("PASS".equals(verdict))
                .verdict(verdict)
                .comment(comment)
                .build();
    }

    private String outputWithReportId() {
        return "Report generated.\nREPORT_ID=" + REPORT_ARTIFACT_ID + "\nAll checks green.";
    }

    private void verifyAdvancedEvent(int completedStep, int nextStep, WorkflowChain.Status chainStatus) {
        ArgumentCaptor<WorkflowAdvancedEvent> eventCaptor =
                ArgumentCaptor.forClass(WorkflowAdvancedEvent.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        WorkflowAdvancedEvent event = eventCaptor.getValue();
        assertThat(event.getWorkflowId()).isEqualTo(chain.getId());
        assertThat(event.getCompletedStep()).isEqualTo(completedStep);
        assertThat(event.getNextStep()).isEqualTo(nextStep);
        assertThat(event.getChainStatus()).isEqualTo(chainStatus);
    }
}

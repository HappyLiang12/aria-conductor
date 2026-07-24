package io.aria.conductor.execution.pipeline;

import io.aria.conductor.agent.repository.RunRepository;
import io.aria.conductor.common.model.RiskTier;
import io.aria.conductor.common.model.Run;
import io.aria.conductor.common.model.RunStatus;
import io.aria.conductor.execution.approval.ApprovalDecision;
import io.aria.conductor.execution.approval.ApprovalGate;
import io.aria.conductor.execution.engine.RunContext;
import io.aria.conductor.test.TestDataBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit-level complement to {@code FullPipelineIntegrationTest}: focuses on the run-status
 * side-effects and error handling of the Stage-4 approval gate that the integration test
 * does not assert — specifically the cosmetic {@code Run.status} PAUSED→RUNNING transition,
 * restoration after an approval-gate exception, suppression of the RUNNING restore when the
 * run was cancelled while blocked, and that a FAILED executor result is still audited.
 */
@ExtendWith(MockitoExtension.class)
class ActionExecutionPipelineTest {

    @Mock private RuleVerifier ruleVerifier;
    @Mock private AiVerificationAgent aiVerificationAgent;
    @Mock private ApprovalGate approvalGate;
    @Mock private ShadowCopyManager shadowCopyManager;
    @Mock private ActionExecutor executor;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private ToolRiskResolver riskResolver;
    @Mock private RunRepository runRepository;

    private ActionExecutionPipeline pipeline;
    private final UUID runId = UUID.randomUUID();
    private final List<RunStatus> savedStatuses = new ArrayList<>();

    @BeforeEach
    void setUp() {
        ActionClassifier classifier = new ActionClassifier(riskResolver);
        AuditRecorder auditRecorder = new AuditRecorder(eventPublisher);
        lenient().when(riskResolver.resolve(any())).thenReturn(RiskTier.READ);
        lenient().when(ruleVerifier.verify(any(), any(), any())).thenReturn(RuleVerificationResult.allow());
        lenient().when(aiVerificationAgent.verify(any(), any(), any())).thenReturn(AiVerificationResult.pass("ok"));
        lenient().when(approvalGate.requestApproval(any(), any(), any())).thenReturn(ApprovalDecision.approve("ok"));
        lenient().when(executor.execute(any(), any())).thenReturn(ActionResult.success("ran"));
        pipeline = new ActionExecutionPipeline(
                classifier, ruleVerifier, aiVerificationAgent, approvalGate,
                shadowCopyManager, executor, auditRecorder, runRepository);
    }

    /** Make setRunStatus observable: record the status each save() persists, in order. */
    private void trackRunStatus() {
        Run run = TestDataBuilder.aRun().withId(runId).withStatus(RunStatus.RUNNING).build();
        when(runRepository.findById(runId)).thenReturn(Optional.of(run));
        when(runRepository.save(any())).thenAnswer(inv -> {
            savedStatuses.add(((Run) inv.getArgument(0)).getStatus());
            return inv.getArgument(0);
        });
    }

    private RunContext ctx() {
        return new RunContext(runId, UUID.randomUUID(), null, null, 50);
    }

    private Action highRisk() {
        return new Action("drop_db", ActionType.HIGH_RISK, "{}", "tc-1");
    }

    @Test
    void approvalRequired_setsRunPausedThenRunning_aroundBlockingGate() {
        trackRunStatus();

        ActionResult result = pipeline.execute(highRisk(), ctx());

        assertThat(result.status()).isEqualTo(ActionResult.Status.SUCCESS);
        assertThat(savedStatuses).containsExactly(RunStatus.PAUSED, RunStatus.RUNNING);
        verify(executor, times(1)).execute(any(), any());
    }

    @Test
    void approvalGateThrows_returnsDenied_restoresRunning_andAuditsWithoutExecuting() {
        trackRunStatus();
        when(approvalGate.requestApproval(any(), any(), any()))
                .thenThrow(new RuntimeException("gate exploded"));

        ActionResult result = pipeline.execute(highRisk(), ctx());

        assertThat(result.status()).isEqualTo(ActionResult.Status.DENIED);
        assertThat(result.error()).contains("Approval process failed:").contains("gate exploded");
        // Even on exception the RUNNING status must be restored (PAUSED then RUNNING).
        assertThat(savedStatuses).containsExactly(RunStatus.PAUSED, RunStatus.RUNNING);
        verify(executor, never()).execute(any(), any());
        verify(shadowCopyManager, never()).createShadowCopy(any(), any(), any(), any());
    }

    @Test
    void cancelledWhileBlocked_doesNotRestoreRunning() {
        trackRunStatus();
        // Simulate the run being cancelled during the blocking approval wait.
        when(approvalGate.requestApproval(any(), any(), any())).thenAnswer(inv -> {
            RunContext c = inv.getArgument(1);
            c.setCancelled(true);
            return ApprovalDecision.approve("ok");
        });

        pipeline.execute(highRisk(), ctx());

        // Only the PAUSED write happens; RUNNING restore is suppressed because ctx.isCancelled().
        assertThat(savedStatuses).containsExactly(RunStatus.PAUSED);
    }

    @Test
    void executorFailure_isStillAuditedAsActionFailed() {
        // Low-risk read: no approval, no run-status writes; executor returns FAILED.
        when(executor.execute(any(), any())).thenReturn(ActionResult.failed("disk error"));
        Action read = new Action("read_file", ActionType.READ, "{}", "tc-r");

        ActionResult result = pipeline.execute(read, ctx());

        assertThat(result.status()).isEqualTo(ActionResult.Status.FAILED);
        io.aria.conductor.common.event.AuditLogEvent event = captureAudit();
        assertThat(event.getEventType()).isEqualTo("ACTION_FAILED");
        // No approval gate ⇒ no run-status mutation.
        verify(runRepository, never()).save(any());
    }

    @Test
    void reversibleWriteWithoutApproval_capturesShadowCopyThenExecutes() {
        Action write = new Action("upsert", ActionType.WRITE, "{\"id\":1}", "tc-w");

        pipeline.execute(write, ctx());

        verify(shadowCopyManager, times(1))
                .createShadowCopy(any(String.class), any(String.class), any(), any());
        verify(approvalGate, never()).requestApproval(any(), any(), any());
        verify(executor, times(1)).execute(any(), any());
    }

    private io.aria.conductor.common.event.AuditLogEvent captureAudit() {
        org.mockito.ArgumentCaptor<io.aria.conductor.common.event.AuditLogEvent> captor =
                org.mockito.ArgumentCaptor.forClass(io.aria.conductor.common.event.AuditLogEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        return captor.getValue();
    }
}

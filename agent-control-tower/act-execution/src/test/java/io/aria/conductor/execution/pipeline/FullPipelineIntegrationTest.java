package io.aria.conductor.execution.pipeline;

import io.aria.conductor.agent.repository.RunRepository;
import io.aria.conductor.common.event.AuditLogEvent;
import io.aria.conductor.common.model.Agent;
import io.aria.conductor.common.model.AgentSession;
import io.aria.conductor.common.model.RiskTier;
import io.aria.conductor.execution.approval.ApprovalDecision;
import io.aria.conductor.execution.approval.ApprovalGate;
import io.aria.conductor.execution.engine.RunContext;
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
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * End-to-end coverage of the 6-stage {@link ActionExecutionPipeline}.
 *
 * <p>Wires real instances of {@link ActionClassifier} / {@link AuditRecorder} /
 * {@link ActionExecutionPipeline}, with mocked external collaborators
 * ({@link RuleVerifier} so we can assert pre-stage blocking,
 * {@link AiVerificationAgent}, {@link ApprovalGate}, {@link ActionExecutor},
 * {@link ShadowCopyManager}).
 */
@ExtendWith(MockitoExtension.class)
class FullPipelineIntegrationTest {

    @Mock private RuleVerifier ruleVerifier;
    @Mock private AiVerificationAgent aiVerificationAgent;
    @Mock private ApprovalGate approvalGate;
    @Mock private ShadowCopyManager shadowCopyManager;
    @Mock private ActionExecutor executor;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private ToolRiskResolver riskResolver;
    @Mock private RunRepository runRepository;

    private ActionClassifier classifier;
    private AuditRecorder auditRecorder;
    private ActionExecutionPipeline pipeline;

    @BeforeEach
    void setUp() {
        classifier = new ActionClassifier(riskResolver);
        auditRecorder = new AuditRecorder(eventPublisher);

        // Default: all tools are READ risk (no approval needed) unless test overrides
        lenient().when(riskResolver.resolve(any())).thenReturn(RiskTier.READ);

        // Default benign collaborators. Each test re-stubs only what it needs.
        lenient().when(ruleVerifier.verify(any(), any(), any())).thenReturn(RuleVerificationResult.allow());
        lenient().when(aiVerificationAgent.verify(any(), any(), any()))
                .thenReturn(AiVerificationResult.pass("test-default"));
        lenient().when(approvalGate.requestApproval(any(), any())).thenReturn(ApprovalDecision.approve("ok"));
        lenient().when(executor.execute(any(), any())).thenReturn(ActionResult.success("ran"));

        pipeline = new ActionExecutionPipeline(
                classifier, ruleVerifier, aiVerificationAgent, approvalGate,
                shadowCopyManager, executor, auditRecorder, runRepository);
    }

    @Test
    void lowRiskRead_skipsAiAndApproval_executesAndAudits() {
        Action read = new Action("list_runs", ActionType.READ, "{}", "tc-1");

        ActionResult result = pipeline.execute(read, ctx());

        assertThat(result.status()).isEqualTo(ActionResult.Status.SUCCESS);
        verify(aiVerificationAgent, times(1)).verify(any(), any(), any()); // agent always invoked, but skips internally
        verify(approvalGate, never()).requestApproval(any(), any());
        verify(shadowCopyManager, never()).createShadowCopy(any(), any(), any(), any());
        verifyAuditEventEmitted("ACTION_EXECUTED");
    }

    @Test
    void highRiskAction_runsAiVerifyAndApprovalAndShadowCopyAndAudit() {
        Action drop = new Action("drop_table", ActionType.HIGH_RISK, "{\"table\":\"x\"}", "tc-h");
        when(aiVerificationAgent.verify(any(), any(), any()))
                .thenReturn(AiVerificationResult.pass("looks safe"));

        ActionResult result = pipeline.execute(drop, ctx());

        assertThat(result.status()).isEqualTo(ActionResult.Status.SUCCESS);
        verify(aiVerificationAgent, times(1)).verify(any(), any(), any());
        verify(approvalGate, times(1)).requestApproval(any(), any());
        verify(shadowCopyManager, times(1))
                .createShadowCopy(any(String.class), any(String.class), any(), any());
        verify(executor, times(1)).execute(any(), any());
        verifyAuditEventEmitted("ACTION_EXECUTED");
    }

    @Test
    void ruleBlocked_stopsAtStage2_doesNotInvokeAiOrApprovalOrExecutor() {
        when(ruleVerifier.verify(any(), any(), any()))
                .thenReturn(RuleVerificationResult.deny("budget exhausted"));

        ActionResult result = pipeline.execute(
                new Action("write_thing", ActionType.WRITE, "{}", "tc-w"), ctx());

        assertThat(result.status()).isEqualTo(ActionResult.Status.BLOCKED);
        assertThat(result.error()).isEqualTo("budget exhausted");
        verify(aiVerificationAgent, never()).verify(any(), any(), any());
        verify(approvalGate, never()).requestApproval(any(), any());
        verify(executor, never()).execute(any(), any());
        verify(shadowCopyManager, never()).createShadowCopy(any(), any(), any(), any());
        verifyAuditEventEmitted("ACTION_BLOCKED");
    }

    @Test
    void aiFail_stopsAtStage3_doesNotApproveOrExecute() {
        when(aiVerificationAgent.verify(any(), any(), any()))
                .thenReturn(AiVerificationResult.fail("destructive", 0.95));

        ActionResult result = pipeline.execute(
                new Action("rm_rf", ActionType.HIGH_RISK, "/", "tc-x"), ctx());

        assertThat(result.status()).isEqualTo(ActionResult.Status.BLOCKED);
        assertThat(result.error()).contains("AI safety:").contains("destructive");
        verify(approvalGate, never()).requestApproval(any(), any());
        verify(executor, never()).execute(any(), any());
        verify(shadowCopyManager, never()).createShadowCopy(any(), any(), any(), any());
        verifyAuditEventEmitted("ACTION_BLOCKED");
    }

    @Test
    void approvalDenied_stopsAtStage4_doesNotExecute() {
        when(approvalGate.requestApproval(any(), any()))
                .thenReturn(ApprovalDecision.deny("operator denied"));

        ActionResult result = pipeline.execute(
                new Action("drop_db", ActionType.HIGH_RISK, "{}", "tc-d"), ctx());

        assertThat(result.status()).isEqualTo(ActionResult.Status.DENIED);
        assertThat(result.error()).isEqualTo("operator denied");
        verify(executor, never()).execute(any(), any());
        verify(shadowCopyManager, never()).createShadowCopy(any(), any(), any(), any());
        verifyAuditEventEmitted("ACTION_DENIED");
    }

    @Test
    void aiWarn_doesNotBlock_pipelineContinuesToExecute() {
        when(aiVerificationAgent.verify(any(), any(), any()))
                .thenReturn(AiVerificationResult.warn("ambiguous", 0.6));

        ActionResult result = pipeline.execute(
                new Action("upsert_record", ActionType.WRITE, "{}", "tc-w"), ctx());

        assertThat(result.status()).isEqualTo(ActionResult.Status.SUCCESS);
        verify(executor, times(1)).execute(any(), any());
    }

    @Test
    void writeAction_capturesShadowCopy_evenWithoutApproval() {
        Action write = new Action("upsert_record", ActionType.WRITE, "{\"id\":1}", "tc-w");

        pipeline.execute(write, ctx());

        verify(shadowCopyManager, times(1))
                .createShadowCopy(any(String.class), any(String.class), any(), any());
        verify(approvalGate, never()).requestApproval(any(), any());
    }

    @Test
    void aiEscalate_forcesApprovalGate_evenWhenNotRequiredByPolicy() {
        // A WRITE action does not statically require approval, but an AI ESCALATE must force the gate.
        when(aiVerificationAgent.verify(any(), any(), any()))
                .thenReturn(AiVerificationResult.escalate("looks risky — human should confirm", 0.85));

        ActionResult result = pipeline.execute(
                new Action("write_config", ActionType.WRITE, "{}", "tc-esc"), ctx());

        assertThat(result.status()).isEqualTo(ActionResult.Status.SUCCESS);
        verify(approvalGate, times(1)).requestApproval(any(), any());
        verify(executor, times(1)).execute(any(), any());
    }

    // ---- helpers ----

    private RunContext ctx() {
        UUID runId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        Agent agent = Agent.builder().id(agentId).name("test").build();
        AgentSession session = AgentSession.builder().runId(runId).agentId(agentId).build();
        return new RunContext(runId, agentId, agent, session);
    }

    private void verifyAuditEventEmitted(String expectedEventType) {
        ArgumentCaptor<AuditLogEvent> captor = ArgumentCaptor.forClass(AuditLogEvent.class);
        verify(eventPublisher, atLeastOnce()).publishEvent(captor.capture());
        List<AuditLogEvent> events = captor.getAllValues();
        assertThat(events)
                .as("expected an audit event of type %s", expectedEventType)
                .anyMatch(e -> expectedEventType.equals(e.getEventType()));
    }
}

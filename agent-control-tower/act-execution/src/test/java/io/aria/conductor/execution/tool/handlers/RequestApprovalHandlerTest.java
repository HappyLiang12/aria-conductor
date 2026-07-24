package io.aria.conductor.execution.tool.handlers;

import io.aria.conductor.agent.repository.RunRepository;
import io.aria.conductor.common.model.Run;
import io.aria.conductor.common.model.RunStatus;
import io.aria.conductor.execution.approval.ApprovalDecision;
import io.aria.conductor.execution.approval.ApprovalGate;
import io.aria.conductor.execution.engine.RunContext;
import io.aria.conductor.execution.pipeline.Action;
import io.aria.conductor.test.TestDataBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Behaviour tests for {@link RequestApprovalHandler} — the HITL tool that lets a worker agent
 * request human approval mid-run. Verifies parameter validation, the run-context guard, the
 * PAUSED→RUNNING cosmetic status transition around the blocking gate (suppressed when the run
 * is cancelled while blocked), and the APPROVED/DENIED result rendering including reason defaults.
 */
@ExtendWith(MockitoExtension.class)
class RequestApprovalHandlerTest {

    @Mock private ApprovalGate approvalGate;
    @Mock private RunRepository runRepository;

    private RequestApprovalHandler handler;
    private final UUID runId = UUID.randomUUID();
    private final List<RunStatus> savedStatuses = new ArrayList<>();

    @BeforeEach
    void setUp() {
        handler = new RequestApprovalHandler(approvalGate, runRepository);
    }

    private RunContext ctx() {
        return new RunContext(runId, UUID.randomUUID(), null, null, 50);
    }

    private void trackRunStatus() {
        Run run = TestDataBuilder.aRun().withId(runId).withStatus(RunStatus.RUNNING).build();
        lenient().when(runRepository.findById(runId)).thenReturn(Optional.of(run));
        lenient().when(runRepository.save(any())).thenAnswer(inv -> {
            savedStatuses.add(((Run) inv.getArgument(0)).getStatus());
            return inv.getArgument(0);
        });
    }

    private Map<String, Object> args(String summary, RunContext ctx) {
        Map<String, Object> m = new HashMap<>();
        if (summary != null) m.put("summary", summary);
        if (ctx != null) m.put("_runContext", ctx);
        return m;
    }

    @Test
    void execute_rejectsMissingSummary() {
        assertThat(handler.execute(args(null, ctx())))
                .isEqualTo("Error: Missing required parameter: summary");
        verifyNoInteractions(approvalGate);
    }

    @Test
    void execute_rejectsWhenNoRunContext() {
        assertThat(handler.execute(args("please approve", null)))
                .isEqualTo("Error: request_approval can only be invoked within an agent run context");
        verifyNoInteractions(approvalGate);
    }

    @Test
    void execute_rejectsWhenRunContextWrongType() {
        Map<String, Object> m = new HashMap<>();
        m.put("summary", "s");
        m.put("_runContext", "not-a-context");
        assertThat(handler.execute(m))
                .isEqualTo("Error: request_approval can only be invoked within an agent run context");
        verifyNoInteractions(approvalGate);
    }

    @Test
    void execute_approved_rendersReason_andTogglesRunStatusPausedThenRunning() {
        trackRunStatus();
        when(approvalGate.requestApproval(any(Action.class), any(RunContext.class)))
                .thenReturn(ApprovalDecision.approve("looks good"));

        String result = handler.execute(args("deploy prod", ctx()));

        assertThat(result).isEqualTo("APPROVED: looks good");
        assertThat(savedStatuses).containsExactly(RunStatus.PAUSED, RunStatus.RUNNING);
    }

    @Test
    void execute_denied_rendersReason() {
        trackRunStatus();
        when(approvalGate.requestApproval(any(Action.class), any(RunContext.class)))
                .thenReturn(ApprovalDecision.deny("too risky"));

        assertThat(handler.execute(args("rm data", ctx()))).isEqualTo("DENIED: too risky");
    }

    @Test
    void execute_approved_usesDefaultMessage_whenReasonNull() {
        trackRunStatus();
        when(approvalGate.requestApproval(any(Action.class), any(RunContext.class)))
                .thenReturn(ApprovalDecision.approve(null));

        assertThat(handler.execute(args("x", ctx()))).isEqualTo("APPROVED: Human approved the request.");
    }

    @Test
    void execute_cancelledWhileBlocked_suppressesRunningRestore() {
        trackRunStatus();
        when(approvalGate.requestApproval(any(Action.class), any(RunContext.class)))
                .thenAnswer(inv -> {
                    RunContext c = inv.getArgument(1);
                    c.setCancelled(true);
                    return ApprovalDecision.approve("ok");
                });

        handler.execute(args("s", ctx()));

        // PAUSED is written, but the RUNNING restore is skipped because the run was cancelled.
        assertThat(savedStatuses).containsExactly(RunStatus.PAUSED);
        verify(runRepository, never()).save(argThatIsRunning());
    }

    private static Run argThatIsRunning() {
        return org.mockito.ArgumentMatchers.argThat(r -> r != null && r.getStatus() == RunStatus.RUNNING);
    }
}

package io.aria.conductor.execution.pipeline;

import io.aria.conductor.common.event.ApprovalRequestedEvent;
import io.aria.conductor.common.model.Agent;
import io.aria.conductor.common.model.AgentSession;
import io.aria.conductor.common.model.Approval;
import io.aria.conductor.common.model.ApprovalStatus;
import io.aria.conductor.common.model.ToolCall;
import io.aria.conductor.execution.approval.ApprovalDecision;
import io.aria.conductor.execution.approval.ApprovalGate;
import io.aria.conductor.execution.engine.RunContext;
import io.aria.conductor.execution.repository.ApprovalRepository;
import io.aria.conductor.execution.repository.ToolCallRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TurnLevelApprovalTest {

    @Mock private ApprovalRepository approvalRepository;
    @Mock private ToolCallRepository toolCallRepository;
    @Mock private ApplicationEventPublisher eventPublisher;

    private ApprovalGate gate;

    @BeforeEach
    void setUp() {
        // Save returns the same record (with assigned id) — mimic JPA echo behaviour.
        lenient().when(toolCallRepository.save(any(ToolCall.class))).thenAnswer(inv -> {
            ToolCall tc = inv.getArgument(0);
            if (tc.getId() == null) tc.setId(UUID.randomUUID());
            return tc;
        });
        lenient().when(approvalRepository.save(any(Approval.class))).thenAnswer(inv -> {
            Approval a = inv.getArgument(0);
            if (a.getId() == null) a.setId(UUID.randomUUID());
            return a;
        });
        lenient().when(approvalRepository.findById(any(UUID.class))).thenAnswer(inv -> {
            UUID id = inv.getArgument(0);
            Approval a = Approval.builder().id(id).status(ApprovalStatus.PENDING).build();
            return Optional.of(a);
        });

        gate = new ApprovalGate(approvalRepository, toolCallRepository, eventPublisher);
    }

    @Test
    void requestTurnApproval_singleAction_fallsThroughToSingleFlow() throws Exception {
        Action action = new Action("delete_x", ActionType.HIGH_RISK, "{}", "tc-1");
        RunContext ctx = ctx();

        CompletableFuture<ApprovalDecision> future = runAsync(() ->
                gate.requestTurnApproval(ctx, List.of(action)));

        UUID approvalId = waitForApprovalId();
        gate.decideApproval(approvalId, true, "ok");

        ApprovalDecision decision = future.get(5, TimeUnit.SECONDS);
        assertThat(decision.isApproved()).isTrue();
        // Single-action flow saves exactly one tool call; the approval is saved at
        // creation time and again when the operator decides — both update the same row.
        verify(toolCallRepository, times(1)).save(any(ToolCall.class));
        verify(approvalRepository, atLeastOnce()).save(any(Approval.class));
    }

    @Test
    void requestTurnApproval_multipleActions_createsBatchedApprovalAndApprovesAll() throws Exception {
        Action a1 = new Action("delete_x", ActionType.HIGH_RISK, "{}", "tc-1");
        Action a2 = new Action("delete_y", ActionType.HIGH_RISK, "{}", "tc-2");
        Action a3 = new Action("delete_z", ActionType.HIGH_RISK, "{}", "tc-3");
        RunContext ctx = ctx();

        CompletableFuture<ApprovalDecision> future = runAsync(() ->
                gate.requestTurnApproval(ctx, List.of(a1, a2, a3)));

        UUID approvalId = waitForApprovalId();
        gate.decideApproval(approvalId, true, "Approve All");

        ApprovalDecision decision = future.get(5, TimeUnit.SECONDS);
        assertThat(decision.isApproved()).isTrue();

        // One ToolCall per action.
        verify(toolCallRepository, times(3)).save(any(ToolCall.class));

        // Single batched Approval whose reason mentions all three actions.
        // (save() is called twice on the same entity — once at creation, once at decision.)
        ArgumentCaptor<Approval> approvalCaptor = ArgumentCaptor.forClass(Approval.class);
        verify(approvalRepository, atLeastOnce()).save(approvalCaptor.capture());
        Approval saved = approvalCaptor.getAllValues().get(0);
        assertThat(saved.getReason())
                .contains("3 actions")
                .contains("delete_x")
                .contains("delete_y")
                .contains("delete_z")
                .contains("Approve All / Deny All / Review Each");
    }

    @Test
    void requestTurnApproval_denyAll_returnsDeniedDecisionForBatch() throws Exception {
        Action a1 = new Action("delete_x", ActionType.HIGH_RISK, "{}", "tc-1");
        Action a2 = new Action("delete_y", ActionType.HIGH_RISK, "{}", "tc-2");
        RunContext ctx = ctx();

        CompletableFuture<ApprovalDecision> future = runAsync(() ->
                gate.requestTurnApproval(ctx, List.of(a1, a2)));

        UUID approvalId = waitForApprovalId();
        gate.decideApproval(approvalId, false, "operator denied");

        ApprovalDecision decision = future.get(5, TimeUnit.SECONDS);
        assertThat(decision.isApproved()).isFalse();
        assertThat(decision.reason()).isEqualTo("operator denied");
    }

    @Test
    void requestTurnApproval_emptyList_autoApproves() {
        ApprovalDecision decision = gate.requestTurnApproval(ctx(), List.of());
        assertThat(decision.isApproved()).isTrue();
        verify(approvalRepository, never()).save(any());
        verify(toolCallRepository, never()).save(any());
    }

    @Test
    void requestApproval_singleActionLegacy_stillWorks() throws Exception {
        Action action = new Action("delete_x", ActionType.HIGH_RISK, "{}", "tc-1");
        RunContext ctx = ctx();

        CompletableFuture<ApprovalDecision> future = runAsync(() ->
                gate.requestApproval(action, ctx));

        UUID approvalId = waitForApprovalId();
        gate.decideApproval(approvalId, true, "ok");

        ApprovalDecision decision = future.get(5, TimeUnit.SECONDS);
        assertThat(decision.isApproved()).isTrue();
    }

    // ---- helpers ----

    private RunContext ctx() {
        UUID runId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        Agent agent = Agent.builder().id(agentId).name("test").build();
        AgentSession session = AgentSession.builder().runId(runId).agentId(agentId).build();
        return new RunContext(runId, agentId, agent, session);
    }

    /** Run an approval call on a separate thread so the main thread can publish a decision. */
    private CompletableFuture<ApprovalDecision> runAsync(java.util.concurrent.Callable<ApprovalDecision> call) {
        CompletableFuture<ApprovalDecision> future = new CompletableFuture<>();
        Executors.newSingleThreadExecutor().submit(() -> {
            try {
                future.complete(call.call());
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        return future;
    }

    /**
     * Spin until the approval gate has published an ApprovalRequestedEvent (i.e. created
     * the approval entity); capture the approval id via the event publisher mock.
     */
    private UUID waitForApprovalId() throws InterruptedException, ExecutionException, TimeoutException {
        ArgumentCaptor<ApprovalRequestedEvent> captor =
                ArgumentCaptor.forClass(ApprovalRequestedEvent.class);
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline) {
            try {
                verify(eventPublisher, times(1)).publishEvent(captor.capture());
                return captor.getValue().getApprovalId();
            } catch (AssertionError ignore) {
                Thread.sleep(20);
            }
        }
        throw new TimeoutException("ApprovalRequestedEvent was never published");
    }
}

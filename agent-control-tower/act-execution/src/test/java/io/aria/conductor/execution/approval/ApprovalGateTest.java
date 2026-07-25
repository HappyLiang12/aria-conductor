package io.aria.conductor.execution.approval;

import io.aria.conductor.common.event.ApprovalRequestedEvent;
import io.aria.conductor.common.model.Agent;
import io.aria.conductor.common.model.AgentSession;
import io.aria.conductor.common.model.Approval;
import io.aria.conductor.common.model.ApprovalStatus;
import io.aria.conductor.common.model.ToolCall;
import io.aria.conductor.common.model.ToolCallStatus;
import io.aria.conductor.execution.engine.RunContext;
import io.aria.conductor.execution.pipeline.Action;
import io.aria.conductor.execution.pipeline.ActionType;
import io.aria.conductor.execution.repository.ApprovalRepository;
import io.aria.conductor.execution.repository.ToolCallRepository;
import io.aria.conductor.test.TestDataBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Behaviour tests for {@link ApprovalGate} covering the single-action approval
 * lifecycle: PENDING creation with the 30-minute expiry window, approve/deny
 * decisions unblocking the waiting thread, idempotent double-decisions,
 * unknown-id error paths and run-level cancellation.
 *
 * <p>Repository mocks are backed by in-memory stores so tests can assert the
 * actual entity state after the gate mutates it (not just that save() ran).
 * The blocking {@code requestApproval} call is driven from a separate thread,
 * mirroring the established idiom in {@code TurnLevelApprovalTest}.
 */
@ExtendWith(MockitoExtension.class)
class ApprovalGateTest {

    @Mock private ApprovalRepository approvalRepository;
    @Mock private ToolCallRepository toolCallRepository;
    @Mock private ApplicationEventPublisher eventPublisher;

    private final Map<UUID, Approval> approvalStore = new ConcurrentHashMap<>();
    private final Map<UUID, ToolCall> toolCallStore = new ConcurrentHashMap<>();

    private ApprovalGate gate;

    @BeforeEach
    void setUp() {
        // Mimic JPA: save assigns an id and the entity remains readable via findById,
        // so decideApproval / cancelAllPendingForRun operate on real shared state.
        lenient().when(toolCallRepository.save(any(ToolCall.class))).thenAnswer(inv -> {
            ToolCall tc = inv.getArgument(0);
            if (tc.getId() == null) tc.setId(UUID.randomUUID());
            toolCallStore.put(tc.getId(), tc);
            return tc;
        });
        lenient().when(toolCallRepository.findById(any(UUID.class)))
                .thenAnswer(inv -> Optional.ofNullable(toolCallStore.get(inv.<UUID>getArgument(0))));
        lenient().when(approvalRepository.save(any(Approval.class))).thenAnswer(inv -> {
            Approval a = inv.getArgument(0);
            if (a.getId() == null) a.setId(UUID.randomUUID());
            approvalStore.put(a.getId(), a);
            return a;
        });
        lenient().when(approvalRepository.findById(any(UUID.class)))
                .thenAnswer(inv -> Optional.ofNullable(approvalStore.get(inv.<UUID>getArgument(0))));

        gate = new ApprovalGate(approvalRepository, toolCallRepository, eventPublisher);
    }

    @Test
    void requestApproval_createsPendingApprovalWithThirtyMinuteExpiry() throws Exception {
        Instant before = Instant.now();
        RunContext ctx = ctx();

        CompletableFuture<ApprovalDecision> future = runAsync(() ->
                gate.requestApproval(action("write_file", "{\"path\":\"a.txt\"}"), ctx));

        UUID approvalId = waitForApprovalId();
        Instant after = Instant.now();

        Approval pending = approvalStore.get(approvalId);
        assertThat(pending.getStatus()).isEqualTo(ApprovalStatus.PENDING);
        assertThat(pending.getRunId()).isEqualTo(ctx.getRunId());
        assertThat(pending.getToolCallId()).isNotNull();
        assertThat(pending.getReason())
                .contains("write_file")
                .contains("a.txt");
        // Expiry constant behaviour: expiresAt must be exactly request-time + 30 minutes
        // (bounded by the instants captured around the call — no sleeping involved).
        assertThat(pending.getExpiresAt())
                .isBetween(before.plus(Duration.ofMinutes(30)), after.plus(Duration.ofMinutes(30)));

        gate.decideApproval(approvalId, true, "ok");
        assertThat(future.get(5, TimeUnit.SECONDS).isApproved()).isTrue();
    }

    @Test
    void decideApproval_approved_unblocksCallerAndMarksEntitiesApproved() throws Exception {
        RunContext ctx = ctx();
        CompletableFuture<ApprovalDecision> future = runAsync(() ->
                gate.requestApproval(action("deploy", "{}"), ctx));
        UUID approvalId = waitForApprovalId();

        gate.decideApproval(approvalId, true, "operator says go");

        ApprovalDecision decision = future.get(5, TimeUnit.SECONDS);
        assertThat(decision.isApproved()).isTrue();
        assertThat(decision.reason()).isEqualTo("operator says go");

        Approval approval = approvalStore.get(approvalId);
        assertThat(approval.getStatus()).isEqualTo(ApprovalStatus.APPROVED);
        assertThat(approval.getReason()).isEqualTo("operator says go");
        assertThat(approval.getDecidedAt()).isNotNull();
        assertThat(toolCallStore.get(approval.getToolCallId()).getStatus())
                .isEqualTo(ToolCallStatus.EXECUTING);
    }

    @Test
    void decideApproval_denied_unblocksCallerAndMarksEntitiesDenied() throws Exception {
        RunContext ctx = ctx();
        CompletableFuture<ApprovalDecision> future = runAsync(() ->
                gate.requestApproval(action("drop_table", "{}"), ctx));
        UUID approvalId = waitForApprovalId();

        gate.decideApproval(approvalId, false, "too risky");

        ApprovalDecision decision = future.get(5, TimeUnit.SECONDS);
        assertThat(decision.isApproved()).isFalse();
        assertThat(decision.reason()).isEqualTo("too risky");

        Approval approval = approvalStore.get(approvalId);
        assertThat(approval.getStatus()).isEqualTo(ApprovalStatus.DENIED);
        assertThat(approval.getDecidedAt()).isNotNull();
        assertThat(toolCallStore.get(approval.getToolCallId()).getStatus())
                .isEqualTo(ToolCallStatus.DENIED);
    }

    @Test
    void decideApproval_unknownId_throwsIllegalArgument() {
        UUID unknownId = UUID.randomUUID();
        assertThatThrownBy(() -> gate.decideApproval(unknownId, true, "whatever"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Approval not found")
                .hasMessageContaining(unknownId.toString());
    }

    @Test
    void decideApproval_secondDecisionOnDecidedApproval_isIgnored() throws Exception {
        RunContext ctx = ctx();
        CompletableFuture<ApprovalDecision> future = runAsync(() ->
                gate.requestApproval(action("deploy", "{}"), ctx));
        UUID approvalId = waitForApprovalId();

        gate.decideApproval(approvalId, true, "first: approved");
        assertThat(future.get(5, TimeUnit.SECONDS).isApproved()).isTrue();

        // A late conflicting decision must not flip the recorded outcome.
        gate.decideApproval(approvalId, false, "late deny");

        Approval approval = approvalStore.get(approvalId);
        assertThat(approval.getStatus()).isEqualTo(ApprovalStatus.APPROVED);
        assertThat(approval.getReason()).isEqualTo("first: approved");
        assertThat(toolCallStore.get(approval.getToolCallId()).getStatus())
                .isEqualTo(ToolCallStatus.EXECUTING);
    }

    @Test
    void requestApproval_withEscalationNote_prefixesReasonWithAiEscalation() throws Exception {
        RunContext ctx = ctx();
        CompletableFuture<ApprovalDecision> future = runAsync(() ->
                gate.requestApproval(action("risky_tool", "{}"), ctx, "model was unsure about side effects"));
        UUID approvalId = waitForApprovalId();

        assertThat(approvalStore.get(approvalId).getReason())
                .startsWith("AI escalated for human review: model was unsure about side effects")
                .contains("Agent requests approval to execute risky_tool");

        gate.decideApproval(approvalId, true, "ok");
        assertThat(future.get(5, TimeUnit.SECONDS).isApproved()).isTrue();
    }

    @Test
    void requestApproval_truncatesOversizedArgumentsInReason() throws Exception {
        RunContext ctx = ctx();
        String hugeArgs = "x".repeat(250);
        CompletableFuture<ApprovalDecision> future = runAsync(() ->
                gate.requestApproval(action("write_file", hugeArgs), ctx));
        UUID approvalId = waitForApprovalId();

        String reason = approvalStore.get(approvalId).getReason();
        assertThat(reason)
                .contains("x".repeat(200) + "…")
                .doesNotContain("x".repeat(201));

        gate.decideApproval(approvalId, false, "no");
        assertThat(future.get(5, TimeUnit.SECONDS).isApproved()).isFalse();
    }

    @Test
    void cancelAllPendingForRun_expiresPendingAndLeavesDecidedUntouched() throws Exception {
        RunContext ctx = ctx();
        CompletableFuture<ApprovalDecision> future = runAsync(() ->
                gate.requestApproval(action("deploy", "{}"), ctx));
        UUID pendingId = waitForApprovalId();

        Approval alreadyDecided = TestDataBuilder.anApproval()
                .withRunId(ctx.getRunId())
                .withStatus(ApprovalStatus.APPROVED)
                .withReason("already approved")
                .withDecidedAt(Instant.now().minusSeconds(60))
                .build();
        approvalStore.put(alreadyDecided.getId(), alreadyDecided);
        when(approvalRepository.findByRunId(ctx.getRunId()))
                .thenReturn(List.of(approvalStore.get(pendingId), alreadyDecided));

        gate.cancelAllPendingForRun(ctx.getRunId());

        // The blocked caller is released with a denial.
        ApprovalDecision decision = future.get(5, TimeUnit.SECONDS);
        assertThat(decision.isApproved()).isFalse();
        assertThat(decision.reason()).isEqualTo("Run cancelled");

        // Pending approval is expired with audit fields set.
        Approval expired = approvalStore.get(pendingId);
        assertThat(expired.getStatus()).isEqualTo(ApprovalStatus.EXPIRED);
        assertThat(expired.getReason()).isEqualTo("Run cancelled");
        assertThat(expired.getDecidedAt()).isNotNull();

        // Already-decided approval is left completely untouched.
        assertThat(alreadyDecided.getStatus()).isEqualTo(ApprovalStatus.APPROVED);
        assertThat(alreadyDecided.getReason()).isEqualTo("already approved");
    }

    @Test
    void cancelPendingApproval_unknownId_doesNotAffectOtherPendingApprovals() throws Exception {
        RunContext ctx = ctx();
        CompletableFuture<ApprovalDecision> future = runAsync(() ->
                gate.requestApproval(action("deploy", "{}"), ctx));
        UUID approvalId = waitForApprovalId();

        gate.cancelPendingApproval(UUID.randomUUID());

        // The unrelated in-flight approval must still be decidable normally.
        gate.decideApproval(approvalId, true, "still fine");
        ApprovalDecision decision = future.get(5, TimeUnit.SECONDS);
        assertThat(decision.isApproved()).isTrue();
        assertThat(decision.reason()).isEqualTo("still fine");
    }

    @Test
    void requestTurnApproval_nullActionList_autoApprovesWithoutPersisting() {
        ApprovalDecision decision = gate.requestTurnApproval(ctx(), null);

        assertThat(decision.isApproved()).isTrue();
        assertThat(decision.reason()).isEqualTo("No actions requiring approval");
        assertThat(approvalStore).isEmpty();
        assertThat(toolCallStore).isEmpty();
    }

    // ---- helpers ----

    private static Action action(String name, String arguments) {
        return new Action(name, ActionType.HIGH_RISK, arguments, null);
    }

    private RunContext ctx() {
        UUID runId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        Agent agent = TestDataBuilder.anAgent().withId(agentId).build();
        AgentSession session = TestDataBuilder.anAgentSession().withRunId(runId).withAgentId(agentId).build();
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
     * the approval entity AND registered the blocking future); capture the approval id
     * via the event publisher mock.
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

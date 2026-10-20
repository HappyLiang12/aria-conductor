package io.aria.conductor.execution.approval;

import io.aria.conductor.common.event.ApprovalExpiredEvent;
import io.aria.conductor.common.model.Approval;
import io.aria.conductor.common.model.ApprovalSource;
import io.aria.conductor.common.model.ApprovalStatus;
import io.aria.conductor.common.model.ToolCall;
import io.aria.conductor.common.model.ToolCallStatus;
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

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Behaviour tests for {@link ApprovalGate}: operator decisions update the
 * approval row and its tool call idempotently, and the run-end sweep expires
 * only the run-gate rows it owns (never SPEC_REVIEW or ACP permission asks).
 *
 * <p>Repository mocks are backed by in-memory stores so tests can assert the
 * actual entity state after the gate mutates it (not just that save() ran).
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

    // --- decideApproval ---

    @Test
    void decideApproval_approved_marksApprovalAndToolCallApproved() {
        Approval approval = storePendingApproval(UUID.randomUUID(), "Agent requests approval to execute deploy {}");

        gate.decideApproval(approval.getId(), true, "operator says go");

        Approval decided = approvalStore.get(approval.getId());
        assertThat(decided.getStatus()).isEqualTo(ApprovalStatus.APPROVED);
        assertThat(decided.getReason()).isEqualTo("operator says go");
        assertThat(decided.getDecidedAt()).isNotNull();
        assertThat(toolCallStore.get(decided.getToolCallId()).getStatus())
                .isEqualTo(ToolCallStatus.EXECUTING);
    }

    @Test
    void decideApproval_denied_marksApprovalAndToolCallDenied() {
        Approval approval = storePendingApproval(UUID.randomUUID(), "Agent requests approval to execute drop_table {}");

        gate.decideApproval(approval.getId(), false, "too risky");

        Approval decided = approvalStore.get(approval.getId());
        assertThat(decided.getStatus()).isEqualTo(ApprovalStatus.DENIED);
        assertThat(decided.getReason()).isEqualTo("too risky");
        assertThat(decided.getDecidedAt()).isNotNull();
        assertThat(toolCallStore.get(decided.getToolCallId()).getStatus())
                .isEqualTo(ToolCallStatus.DENIED);
    }

    @Test
    void decideApproval_withoutToolCall_stillRecordsTheDecision() {
        Approval approval = TestDataBuilder.anApproval()
                .withRunId(UUID.randomUUID())
                .withStatus(ApprovalStatus.PENDING)
                .withReason("spec question")
                .build();
        approvalStore.put(approval.getId(), approval);

        gate.decideApproval(approval.getId(), true, "fine");

        assertThat(approval.getStatus()).isEqualTo(ApprovalStatus.APPROVED);
        verify(toolCallRepository, never()).findById(any());
    }

    @Test
    void decideApproval_unknownId_throwsIllegalArgument() {
        UUID unknownId = UUID.randomUUID();
        assertThatThrownBy(() -> gate.decideApproval(unknownId, true, "whatever"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Approval not found")
                .hasMessageContaining(unknownId.toString());
    }

    /**
     * Plan B task 2 pin: CLARIFICATION asks are provenance-stamped rows that are answered
     * via {@code /approvals/{id}/answer} or the run finalize path — the legacy decide flow
     * must refuse them outright instead of recording an approve/deny decision.
     */
    @Test
    void decideRefusesClarificationAsks() {
        Approval ask = Approval.builder()
                .id(UUID.randomUUID())
                .runId(UUID.randomUUID())
                .status(ApprovalStatus.PENDING)
                .askType(Approval.AskType.QUESTION)
                .source(ApprovalSource.CLARIFICATION)
                .build();
        approvalStore.put(ask.getId(), ask);

        assertThatThrownBy(() -> gate.decideApproval(ask.getId(), true, "why"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("/answer");

        // Refused, not decided: the row stays untouched.
        assertThat(ask.getStatus()).isEqualTo(ApprovalStatus.PENDING);
        verify(approvalRepository, never()).save(any());
    }

    @Test
    void decideApproval_secondDecisionOnDecidedApproval_isIgnored() {
        Approval approval = storePendingApproval(UUID.randomUUID(), "Agent requests approval to execute deploy {}");

        gate.decideApproval(approval.getId(), true, "first: approved");
        gate.decideApproval(approval.getId(), false, "late deny");

        Approval decided = approvalStore.get(approval.getId());
        assertThat(decided.getStatus()).isEqualTo(ApprovalStatus.APPROVED);
        assertThat(decided.getReason()).isEqualTo("first: approved");
        assertThat(toolCallStore.get(decided.getToolCallId()).getStatus())
                .isEqualTo(ToolCallStatus.EXECUTING);
    }

    // --- cancelAllPendingForRun ---

    @Test
    void cancelAllPendingForRun_expiresPendingAndLeavesDecidedUntouched() {
        UUID runId = UUID.randomUUID();
        Approval pending = storePendingApproval(runId, "Agent requests approval to execute deploy {}");
        Approval alreadyDecided = TestDataBuilder.anApproval()
                .withRunId(runId)
                .withStatus(ApprovalStatus.APPROVED)
                .withReason("already approved")
                .withDecidedAt(Instant.now().minusSeconds(60))
                .build();
        approvalStore.put(alreadyDecided.getId(), alreadyDecided);
        when(approvalRepository.findByRunId(runId))
                .thenReturn(List.of(approvalStore.get(pending.getId()), alreadyDecided));

        gate.cancelAllPendingForRun(runId);

        Approval expired = approvalStore.get(pending.getId());
        assertThat(expired.getStatus()).isEqualTo(ApprovalStatus.EXPIRED);
        assertThat(expired.getReason()).isEqualTo("Run cancelled");
        assertThat(expired.getDecidedAt()).isNotNull();

        assertThat(alreadyDecided.getStatus()).isEqualTo(ApprovalStatus.APPROVED);
        assertThat(alreadyDecided.getReason()).isEqualTo("already approved");
    }

    @Test
    void cancelAllPendingForRun_leavesSpecReviewApprovalsAlive() {
        UUID runId = UUID.randomUUID();
        Approval specReview = TestDataBuilder.anApproval()
                .withRunId(runId)
                .withApprovalType(Approval.ApprovalType.SPEC_REVIEW)
                .withStatus(ApprovalStatus.PENDING)
                .withReason("Spec ready for review")
                .build();
        approvalStore.put(specReview.getId(), specReview);
        when(approvalRepository.findByRunId(runId)).thenReturn(List.of(specReview));

        gate.cancelAllPendingForRun(runId);

        assertThat(specReview.getStatus()).isEqualTo(ApprovalStatus.PENDING);
        assertThat(specReview.getReason()).isEqualTo("Spec ready for review");
        verify(approvalRepository, never()).save(any());
    }

    @Test
    void cancelAllPendingForRun_leavesReviewRequestsAlive() {
        // REVIEW_REQUEST is a display-only post-run prompt (the review card); the
        // run ending must never expire it.
        UUID runId = UUID.randomUUID();
        Approval review = TestDataBuilder.anApproval()
                .withRunId(runId)
                .withStatus(ApprovalStatus.PENDING)
                .withReason("review the card")
                .build();
        review.setAskType(Approval.AskType.REVIEW_REQUEST);
        approvalStore.put(review.getId(), review);
        when(approvalRepository.findByRunId(runId)).thenReturn(List.of(review));

        gate.cancelAllPendingForRun(runId);

        assertThat(review.getStatus()).isEqualTo(ApprovalStatus.PENDING);
        verify(approvalRepository, never()).save(any());
    }

    /**
     * R20.5: ACP permission asks are cancelled by the ACP permission coordinator (their companion
     * row and delivery state must move with them), so this run sweep must skip them.
     */
    @Test
    void cancelAllPendingForRun_leavesAcpPermissionAsksAlive() {
        UUID runId = UUID.randomUUID();
        Approval acpAsk = TestDataBuilder.anApproval()
                .withRunId(runId)
                .withStatus(ApprovalStatus.PENDING)
                .withReason("ACP permission request: mcp__aria__write_file")
                .build();
        acpAsk.setSource(ApprovalSource.ACP_PERMISSION);
        approvalStore.put(acpAsk.getId(), acpAsk);
        when(approvalRepository.findByRunId(runId)).thenReturn(List.of(acpAsk));

        gate.cancelAllPendingForRun(runId);

        assertThat(acpAsk.getStatus()).isEqualTo(ApprovalStatus.PENDING);
        assertThat(acpAsk.getReason()).isEqualTo("ACP permission request: mcp__aria__write_file");
        verify(approvalRepository, never()).save(any());
    }

    /**
     * A CLARIFICATION ask is owned by the run-input lifecycle (the completion sweep,
     * restart adjudication), never by this legacy broad sweep: a finalized waiting run
     * must settle its question honestly ("finalized by operator" / "run cancelled")
     * instead of this sweep's "Run cancelled" expiry, and the drill proved the broad
     * sweep was winning the race against the completion sweep.
     */
    @Test
    void cancelAllPendingForRun_leavesClarificationAsksAlive() {
        UUID runId = UUID.randomUUID();
        Approval clarification = Approval.builder().id(UUID.randomUUID()).runId(runId)
                .status(ApprovalStatus.PENDING)
                .askType(Approval.AskType.QUESTION)
                .source(ApprovalSource.CLARIFICATION)
                .approvalType(Approval.ApprovalType.TOOL_CALL)
                .content("Which database?")
                .build();
        approvalStore.put(clarification.getId(), clarification);
        when(approvalRepository.findByRunId(runId)).thenReturn(List.of(clarification));

        gate.cancelAllPendingForRun(runId);

        assertThat(clarification.getStatus()).isEqualTo(ApprovalStatus.PENDING);
        assertThat(clarification.getReason()).isNull();
        verify(approvalRepository, never()).save(any());
    }

    /**
     * Task 3 pin: a native ask is stamped {@code ACP_PERMISSION} at registration
     * (PermissionCoordinator.register), so the run-end sweep must leave it to
     * the ACP permission coordinator's own {@code cancelPendingForRun}.
     */
    @Test
    void cancelAllPendingForRunLeavesNativeAsksAlone() {
        UUID runId = UUID.randomUUID();
        Approval nativeAsk = Approval.builder().id(UUID.randomUUID()).runId(runId)
                .status(ApprovalStatus.PENDING)
                .source(ApprovalSource.ACP_PERMISSION)
                .approvalType(Approval.ApprovalType.TOOL_CALL)
                .build();
        when(approvalRepository.findByRunId(runId)).thenReturn(List.of(nativeAsk));

        gate.cancelAllPendingForRun(runId);

        assertThat(nativeAsk.getStatus()).isEqualTo(ApprovalStatus.PENDING);
        verify(approvalRepository, never()).save(nativeAsk);
    }

    /**
     * R-RFUX6 pin: the run-end sweep is an expiry path, so every legacy TOOL_CALL
     * ask it settles must publish the same {@link ApprovalExpiredEvent} the
     * scheduled sweep publishes — the ask's {@code approval.requested}
     * notification must flip and an expiry notice must be raised (D6: no silent
     * lapse). SPEC_REVIEW asks are outside the sweep and must stay silent.
     */
    @Test
    void cancelAllPendingForRun_publishesExpiredEventForSettledLegacyAsk_only() {
        UUID runId = UUID.randomUUID();
        Approval legacyAsk = storePendingApproval(runId, "Agent requests approval to execute deploy {}");
        Approval specReview = TestDataBuilder.anApproval()
                .withRunId(runId)
                .withApprovalType(Approval.ApprovalType.SPEC_REVIEW)
                .withStatus(ApprovalStatus.PENDING)
                .withReason("Spec ready for review")
                .build();
        approvalStore.put(specReview.getId(), specReview);
        when(approvalRepository.findByRunId(runId)).thenReturn(List.of(legacyAsk, specReview));

        gate.cancelAllPendingForRun(runId);

        assertThat(legacyAsk.getStatus()).isEqualTo(ApprovalStatus.EXPIRED);
        assertThat(legacyAsk.getReason()).isEqualTo("Run cancelled");
        assertThat(specReview.getStatus()).isEqualTo(ApprovalStatus.PENDING);

        // Exactly one expiry event, for the settled legacy ask — none for the spec-review ask.
        ArgumentCaptor<ApprovalExpiredEvent> captor = ArgumentCaptor.forClass(ApprovalExpiredEvent.class);
        verify(eventPublisher, times(1)).publishEvent(captor.capture());
        ApprovalExpiredEvent event = captor.getValue();
        assertThat(event.getApprovalId()).isEqualTo(legacyAsk.getId());
        assertThat(event.getRunId()).isEqualTo(runId);
        assertThat(event.getReason()).isEqualTo("Run cancelled");
        assertThat(event.getToolName()).isNull();
    }

    // ---- helpers ----

    private Approval storePendingApproval(UUID runId, String reason) {
        ToolCall toolCall = toolCallRepository.save(ToolCall.builder()
                .runId(runId)
                .toolName("deploy")
                .arguments("{}")
                .status(ToolCallStatus.PENDING)
                .build());
        Approval approval = TestDataBuilder.anApproval()
                .withRunId(runId)
                .withToolCallId(toolCall.getId())
                .withStatus(ApprovalStatus.PENDING)
                .withReason(reason)
                .build();
        approvalStore.put(approval.getId(), approval);
        return approval;
    }
}

package io.aria.conductor.execution.engine;

import io.aria.conductor.common.event.ApprovalRequestedEvent;
import io.aria.conductor.common.event.MemoryProposalCreatedEvent;
import io.aria.conductor.common.event.RunWaitingForInputEvent;
import io.aria.conductor.common.model.Approval;
import io.aria.conductor.common.model.ApprovalSource;
import io.aria.conductor.common.model.ApprovalStatus;
import io.aria.conductor.common.tool.ToolPolicy;
import io.aria.conductor.execution.pipeline.ToolRiskResolver;
import io.aria.conductor.execution.repository.ApprovalRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Turns a decision point into an ordered signal on the turn's own SSE stream
 * (spec §5.1, decision A2): {@code approval_required} when a run needs a
 * decision, {@code waiting_input} when it asks a question. The panel renders a
 * card next to the tool call that caused it instead of the operator hunting for
 * the ask on another page.
 *
 * <p>Exactly one signal per approval: a {@code CLARIFICATION} approval is the
 * {@code waiting_input} card, every other source is the {@code approval_required}
 * card. The engine raises both events for a clarification ask (it publishes
 * {@code ApprovalRequestedEvent} for the row it just persisted and the run also
 * parks), so without that gate one decision would render two cards.
 *
 * <p>A signal is emitted only while the run has a live stream. The turn may be
 * a sync chat ({@code AriaService.chat} starts the run with no emitter) or a
 * panel that already closed; in both cases the ask still exists in the
 * database and the REST/WebSocket planes carry it.
 *
 * <p>Both listeners run after commit: {@code PermissionCoordinator.register}
 * publishes inside its own transaction, and a panel that fetched the approval
 * on the event would otherwise read nothing. An ACP ask is published through
 * the four-argument event constructor, which defaults the source to
 * {@code LEGACY_GATE} while the row says {@code ACP_PERMISSION}, so the row —
 * never the event — is the source of truth for the tool identity, the tier and
 * the question.
 *
 * <p>Neither listener may throw: an {@code ArrayList} escaping an after-commit
 * listener reaches the caller that just committed (the permission registrar and
 * the run loop's parking coordinator).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RunSignalSseListener {

    private final RunStreamRegistry registry;
    private final ApprovalRepository approvals;
    private final ToolRiskResolver toolRiskResolver;

    /** {@code approval_required}: the run needs an operator decision. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onApprovalRequested(ApprovalRequestedEvent event) {
        try {
            UUID runId = event.getRunId();
            if (runId == null || !registry.hasStream(runId)) {
                return;
            }
            approvals.findById(event.getApprovalId()).ifPresent(row -> {
                if (row.getSource() == ApprovalSource.CLARIFICATION) {
                    // One approval, one card. The engine publishes ApprovalRequestedEvent for the
                    // CLARIFICATION ask it just persisted and the run also parks on
                    // RunWaitingForInputEvent; onApprovalRequested runs first, so emitting here
                    // would hand the panel a second decision surface for the same approvalId.
                    // The waiting_input signal (below) is this approval's card.
                    return;
                }
                ToolPolicy policy = row.getToolName() != null
                        ? toolRiskResolver.policyOf(row.getToolName())
                        : null;
                Map<String, Object> payload = new HashMap<>();
                payload.put("runId", runId.toString());
                payload.put("approvalId", row.getId().toString());
                payload.put("toolName", row.getToolName());
                payload.put("riskTier", row.getRiskTier());
                payload.put("toolPolicy", policy != null ? policy.name() : null);
                payload.put("optionsJson", row.getOptionsJson());
                payload.put("expiresAt", row.getExpiresAt() != null ? row.getExpiresAt().toString() : null);
                registry.emit(runId, "approval_required", payload);
            });
        } catch (Exception e) {
            log.warn("Failed to emit the approval signal for run {}: {}",
                    event.getRunId(), e.getMessage(), e);
        }
    }

    /**
     * {@code waiting_input}: the run parked on a question. The engine's
     * bookkeeping listener creates the CLARIFICATION ask and runs first
     * ({@code AgentLoopEngine.onRunWaitingForInput} is ordered ahead of this
     * one), so the run's PENDING clarification ask is the one to name.
     */
    @Order(Ordered.LOWEST_PRECEDENCE)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onRunWaitingForInput(RunWaitingForInputEvent event) {
        try {
            UUID runId = event.getRunId();
            if (runId == null || !registry.hasStream(runId)) {
                return;
            }
            approvals.findByRunIdAndStatusAndSource(runId, ApprovalStatus.PENDING,
                            ApprovalSource.CLARIFICATION)
                    .stream()
                    .max(NEWEST_FIRST)
                    .ifPresent(ask -> registry.emit(runId, "waiting_input", Map.of(
                            "runId", runId.toString(),
                            "approvalId", ask.getId().toString(),
                            "question", questionOf(ask))));
        } catch (Exception e) {
            log.warn("Failed to emit the waiting-input signal for run {}: {}",
                    event.getRunId(), e.getMessage(), e);
        }
    }

    /**
     * {@code memory_proposal}: Aria proposed a memory subject the operator has to
     * decide (spec C1/C2). The frame carries exactly what the in-conversation
     * card renders and the id the decide route is addressed to, so the operator
     * never leaves the chat to find the proposal.
     *
     * <p>Same two gates as the ask signals: the run must own a live stream, and
     * a proposal with no decidable id is not emitted (a card the operator cannot
     * decide is worse than no card). Runs after commit because the PENDING row
     * is written in the memory service's own transaction.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onMemoryProposalCreated(MemoryProposalCreatedEvent event) {
        try {
            UUID runId = event.getRunId();
            UUID proposalId = event.getProposalId();
            if (runId == null || proposalId == null || !registry.hasStream(runId)) {
                return;
            }
            // HashMap, not Map.of: factKey/valueText/sensitivity are legitimately null
            // for some subject types and Map.of rejects null values.
            Map<String, Object> payload = new HashMap<>();
            payload.put("runId", runId.toString());
            payload.put("proposalId", proposalId.toString());
            payload.put("subjectType", event.getSubjectType());
            payload.put("entityId", event.getEntityId() != null ? event.getEntityId().toString() : null);
            payload.put("factKey", event.getFactKey());
            payload.put("valueText", event.getValueText());
            payload.put("sensitivity", event.getSensitivity());
            registry.emit(runId, "memory_proposal", payload);
        } catch (Exception e) {
            log.warn("Failed to emit the memory-proposal signal for run {}: {}",
                    event.getRunId(), e.getMessage(), e);
        }
    }

    /**
     * The ask a parked run names: the newest PENDING clarification row, ties
     * broken by id.
     *
     * <p>The finder is a derived query with no {@code ORDER BY}, so picking
     * {@code findFirst()} makes the emitted card depend on the row order the
     * database happens to return. A run can briefly own two PENDING
     * clarification rows — a duplicate waiting event, or the terminal-race ask
     * of the recorded residual beside a live park — and then an arbitrary pick
     * names a stale {@code approvalId} and question. The selection is resolved
     * here from the row's own identity instead: newest {@code requestedAt}
     * (the ask the operator is being asked now), and a total order even when
     * two rows share an instant or carry no timestamp at all.
     */
    private static final Comparator<Approval> NEWEST_FIRST =
            Comparator.comparing(Approval::getRequestedAt,
                            Comparator.nullsFirst(Comparator.naturalOrder()))
                    .thenComparing(Approval::getId,
                            Comparator.nullsFirst(Comparator.naturalOrder()));

    /** The persisted question, never null (the panel renders the field as text). */
    private static String questionOf(Approval ask) {
        return ask.getContent() != null ? ask.getContent() : "";
    }
}

package io.aria.conductor.execution.engine;

import io.aria.conductor.common.event.ApprovalRequestedEvent;
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
                            ApprovalSource.CLARIFICATION).stream()
                    .findFirst()
                    .ifPresent(ask -> registry.emit(runId, "waiting_input", Map.of(
                            "runId", runId.toString(),
                            "approvalId", ask.getId().toString(),
                            "question", questionOf(ask))));
        } catch (Exception e) {
            log.warn("Failed to emit the waiting-input signal for run {}: {}",
                    event.getRunId(), e.getMessage(), e);
        }
    }

    /** The persisted question, never null (the panel renders the field as text). */
    private static String questionOf(Approval ask) {
        return ask.getContent() != null ? ask.getContent() : "";
    }
}

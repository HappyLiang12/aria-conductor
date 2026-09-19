package io.aria.conductor.common.event;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

import java.util.UUID;

/**
 * Published when a PENDING approval expires without an operator decision.
 *
 * <p>Both expiry paths publish this event so the operator learns the ask is
 * gone: the legacy gate's in-memory timeout ({@code ApprovalGate.handleTimeout})
 * and the scheduled sweep ({@code ApprovalExpiryChecker}, whose ACP branch
 * expires through the ACP permission coordinator). It is deliberately distinct
 * from {@link ApprovalDecidedEvent} — an expiry is not a decision, and a new
 * type cannot be misread by existing consumers.
 *
 * <p>{@code reason} carries the expiry reason the path actually recorded on the
 * approval row (e.g. {@code Auto-rejected: approval timed out} for the gate
 * timeout, {@code expired before decision} for the scheduled sweep).
 */
@Getter
public class ApprovalExpiredEvent extends ApplicationEvent {

    private final UUID approvalId;
    private final UUID runId;
    private final String reason;

    public ApprovalExpiredEvent(Object source, UUID approvalId, UUID runId, String reason) {
        super(source);
        this.approvalId = approvalId;
        this.runId = runId;
        this.reason = reason;
    }
}

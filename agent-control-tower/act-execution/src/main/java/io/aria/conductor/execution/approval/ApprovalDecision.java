package io.aria.conductor.execution.approval;

/**
 * Decision from the approval gate — approved or denied with reason.
 */
public record ApprovalDecision(
        boolean approved,
        String reason
) {
    public boolean isApproved() {
        return approved;
    }

    public static ApprovalDecision approve() {
        return new ApprovalDecision(true, "Approved");
    }

    public static ApprovalDecision approve(String reason) {
        return new ApprovalDecision(true, reason);
    }

    public static ApprovalDecision deny(String reason) {
        return new ApprovalDecision(false, reason);
    }
}
package io.aria.conductor.common.exception;

import java.util.UUID;

public class ApprovalTimeoutException extends RuntimeException {

    public ApprovalTimeoutException(UUID approvalId) {
        super(String.format("Approval %s has timed out", approvalId));
    }

    public ApprovalTimeoutException(String message) {
        super(message);
    }
}

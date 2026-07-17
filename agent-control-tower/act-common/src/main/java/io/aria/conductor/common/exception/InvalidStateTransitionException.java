package io.aria.conductor.common.exception;

public class InvalidStateTransitionException extends RuntimeException {

    public InvalidStateTransitionException(String entityType, String currentState, String targetState) {
        super(String.format("Invalid state transition for %s: %s -> %s", entityType, currentState, targetState));
    }

    public InvalidStateTransitionException(String message) {
        super(message);
    }
}

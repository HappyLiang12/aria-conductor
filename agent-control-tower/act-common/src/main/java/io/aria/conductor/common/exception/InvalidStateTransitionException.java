package io.aria.conductor.common.exception;

import java.time.Instant;

public class InvalidStateTransitionException extends RuntimeException {

    public InvalidStateTransitionException(String entityType, String currentState, String targetState) {
        super(String.format("Invalid state transition for %s: %s -> %s", entityType, currentState, targetState));
    }

    public InvalidStateTransitionException(String message) {
        super(message);
    }

    /**
     * The refusal of an operator action on an entity that already reached a terminal state:
     * the state it is already in, when it got there (when that is known), and the fact that
     * the requested action changed nothing. The state-machine arrow
     * ({@code CANCELLED -> CANCELLED}) tells an operator nothing; this states the truth.
     *
     * <p>One factory for the whole class of already-terminal refusals, so a second call site
     * cannot drift back to the arrow text.
     */
    public static InvalidStateTransitionException alreadyTerminal(String entityType, String entityId,
                                                                  String currentState, Instant completedAt,
                                                                  String outcome) {
        String since = completedAt == null ? "" : String.format(" (completed at %s)", completedAt);
        return new InvalidStateTransitionException(String.format(
                "%s %s is already %s%s; nothing was %s.",
                entityType, entityId, currentState, since, outcome));
    }
}

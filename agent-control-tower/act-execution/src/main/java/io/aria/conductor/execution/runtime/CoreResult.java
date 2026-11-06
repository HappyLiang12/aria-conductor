package io.aria.conductor.execution.runtime;

/**
 * Terminal outcome of one core session: the core session identity, the final
 * output, observed usage and whether cancellation (rather than completion)
 * produced the outcome.
 *
 * <p>{@code turnAlreadyAccounted} marks a result that IS a turn the engine has
 * already accounted through its {@code TurnCompletedEvent} listener — the
 * finalized question turn of the waiting-input loop (and the same turn returned
 * on a sticky-intent wake) — so the engine's final recording skips it instead
 * of double-counting the turn's usage, iteration and trajectory row.
 */
public record CoreResult(String sessionId, String finalOutput,
        UsageSnapshot usage, boolean cancelled, boolean turnAlreadyAccounted) {

    /** The pre-loop shape: the returned turn has not been accounted elsewhere. */
    public CoreResult(String sessionId, String finalOutput,
            UsageSnapshot usage, boolean cancelled) {
        this(sessionId, finalOutput, usage, cancelled, false);
    }
}

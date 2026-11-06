package io.aria.conductor.execution.approval;

/**
 * The boundary a decided native permission reply is handed to: the run
 * coordinator, which owns the run's core session in this process.
 *
 * <p>{@link PermissionCoordinator#decide} records the decision and returns the
 * reply its owning session must answer with; recording alone is not delivery.
 * The coordinator implements this sink and hands the reply to the run-owned
 * session, so a decided ask can never leave its core waiting for an answer the
 * operator already gave. A decision for a run whose runtime this process does
 * not own has no session here, and the sink must ignore the reply -- never
 * route it to another run and never duplicate it (the held, manually-paused
 * path delivers its own reply after a resume re-validates it).
 */
@FunctionalInterface
public interface PermissionReplySink {

    /**
     * Hand one decided reply to its run's owning session. Implementations never
     * throw: the decision is already recorded, and a delivery failure is
     * reported, not escalated into a second decision.
     */
    void deliver(PermissionReply reply);
}

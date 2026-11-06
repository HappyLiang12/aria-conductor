package io.aria.conductor.execution.approval;

/**
 * Where a decided native permission request stands towards the execution
 * boundary it was raised for. Delivery is tracked separately from the approval's
 * own status: an operator decision can be recorded while the run is manually
 * paused, in which case it is HELD and delivered only after an explicit resume
 * with a fresh validity check (spec §5.4).
 */
public enum PermissionDeliveryState {

    /** Registered and displayed; no valid decision exists yet. */
    AWAITING_DECISION,

    /** Decided, but delivery is held by the run's manual pause. */
    HELD_MANUAL_PAUSE,

    /** Delivered to the owning session, or the one-use grant issued. */
    DELIVERED,

    /** Expired (or cancelled) before delivery; an expired grant is never replayed. */
    EXPIRED
}

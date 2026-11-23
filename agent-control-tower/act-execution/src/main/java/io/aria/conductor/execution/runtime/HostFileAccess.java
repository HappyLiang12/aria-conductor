package io.aria.conductor.execution.runtime;

/**
 * How far a HOST-placement core may go with the backend user's filesystem, and
 * who decides a native ask it raises.
 *
 * <p>{@link #READ_ONLY} is the default: the core's own workspace tools are
 * refused and a native write ask off the read allowlist is auto-denied, so the
 * deny list is enforced rather than offered to the operator. {@link #GATED}
 * keeps the same read-only workspace policy but surfaces the native ask and
 * leaves it PENDING until an operator decides — the explicit posture for a
 * workflow that wants a human on every HOST write. {@link #FULL} lets the core
 * edit, write and run shell commands as the backend user and auto-approves its
 * native asks, because a HOST run is not bounded by a run-owned container.
 * SANDBOX placements are unaffected by this policy.
 */
public enum HostFileAccess {
    READ_ONLY,
    GATED,
    FULL
}

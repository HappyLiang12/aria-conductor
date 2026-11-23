package io.aria.conductor.execution.runtime;

/**
 * How far a HOST-placement core may go with the backend user's filesystem.
 *
 * <p>{@link #READ_ONLY} is the default: the core's own workspace tools are
 * refused and a native write ask off the read allowlist is auto-denied, so the
 * deny list is enforced rather than offered to the operator. {@link #FULL} lets
 * the core edit, write and run shell commands as the backend user, because a
 * HOST run is not bounded by a run-owned container. SANDBOX placements are
 * unaffected by this policy.
 */
public enum HostFileAccess {
    READ_ONLY,
    FULL
}

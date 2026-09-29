package io.aria.conductor.execution.approval;

/**
 * The two decisions an operator may make about a native permission request
 * (spec §6.3). {@code ALLOW_ONCE} authorizes exactly the asked operation once;
 * {@code DENY} authorizes nothing. There is deliberately no
 * "allow for the session" value: an allow-always offer is never used as an
 * allow-once substitute.
 */
public enum PermissionChoice {
    ALLOW_ONCE,
    DENY
}

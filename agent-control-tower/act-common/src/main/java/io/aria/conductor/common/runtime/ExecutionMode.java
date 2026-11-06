package io.aria.conductor.common.runtime;

/**
 * Where a run executes: on the backend machine's {@code HOST} or in a
 * run-owned {@code SANDBOX} environment. Running each production core in
 * either placement is the product intent; which core/mode pairs are actually
 * supported is capability-gated per pair rather than asserted here. The mode
 * is selected explicitly and never silently substituted.
 */
public enum ExecutionMode {
    HOST,
    SANDBOX
}

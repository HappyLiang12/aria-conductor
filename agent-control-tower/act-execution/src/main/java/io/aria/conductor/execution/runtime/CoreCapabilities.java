package io.aria.conductor.execution.runtime;

/**
 * Capability matrix of one core in one mode, derived from verified protocol
 * evidence -- not from assumptions. Native control APIs are capability-gated:
 * {@code pauseStrategy}, native cancel, an enforced round limit and observable
 * usage must be recorded as observed, unknown values stay false.
 */
public record CoreCapabilities(ControlStrategy pauseStrategy, boolean nativeCancel,
        boolean enforcedRoundLimit, boolean usageObservable) {
}

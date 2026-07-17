package io.aria.conductor.execution.adk;

/**
 * Result of an ADK runtime health probe.
 */
public enum AdkHealthStatus {
    /** /health responded with a 2xx success status. */
    HEALTHY,
    /** /health responded with a non-2xx status. */
    UNHEALTHY,
    /** TCP connection failed or request timed out. */
    UNREACHABLE
}

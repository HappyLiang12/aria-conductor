package io.aria.conductor.execution.adk;

import java.time.Instant;
import java.util.UUID;

/**
 * Lifecycle state of an ADK subprocess instance.
 *
 * @param agentId            owning agent
 * @param port               TCP port the subprocess is bound to (0 in mock mode)
 * @param process            backing OS process (null in mock mode or after crash)
 * @param startedAt          when the subprocess was last started
 * @param lastHealthCheck    timestamp of the most recent health probe
 * @param healthy            most recent health probe outcome
 * @param consecutiveFailures count of consecutive failed health probes
 * @param restartAttempts    count of times this instance has been restarted (resets on healthy)
 * @param nextRestartAt      earliest time the next restart should happen (exp. backoff)
 */
public record AdkInstance(
        UUID agentId,
        int port,
        Process process,
        Instant startedAt,
        Instant lastHealthCheck,
        boolean healthy,
        int consecutiveFailures,
        int restartAttempts,
        Instant nextRestartAt
) {
    /** 7-arg legacy constructor (preserves existing call-sites and tests). */
    public AdkInstance(UUID agentId,
                       int port,
                       Process process,
                       Instant startedAt,
                       Instant lastHealthCheck,
                       boolean healthy,
                       int consecutiveFailures) {
        this(agentId, port, process, startedAt, lastHealthCheck, healthy, consecutiveFailures, 0, null);
    }

    public AdkInstance withHealthCheck(Instant checkTime, boolean isHealthy, int failures) {
        // A healthy probe clears the restart-backoff counters.
        int attempts = isHealthy ? 0 : restartAttempts;
        Instant nextRestart = isHealthy ? null : nextRestartAt;
        return new AdkInstance(agentId, port, process, startedAt, checkTime, isHealthy, failures, attempts, nextRestart);
    }

    public AdkInstance withRestart(Process newProcess, int newPort, Instant when, int newAttempts, Instant newNextRestartAt) {
        return new AdkInstance(agentId, newPort, newProcess, when, when, true, 0, newAttempts, newNextRestartAt);
    }

    public AdkInstance withNextRestartAt(Instant when, int attempts) {
        return new AdkInstance(agentId, port, process, startedAt, lastHealthCheck, healthy, consecutiveFailures, attempts, when);
    }
}

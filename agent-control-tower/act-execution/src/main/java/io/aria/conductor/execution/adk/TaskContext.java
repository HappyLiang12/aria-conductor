package io.aria.conductor.execution.adk;

import java.time.Duration;

/**
 * Task-level constraints passed to {@link AdkProvider#executeTask}.
 *
 * @param maxRounds   maximum number of internal agent rounds (may be {@code null} if unset)
 * @param maxDuration maximum wall-clock duration for the whole task (may be {@code null})
 */
public record TaskContext(int maxRounds, Duration maxDuration) {
}

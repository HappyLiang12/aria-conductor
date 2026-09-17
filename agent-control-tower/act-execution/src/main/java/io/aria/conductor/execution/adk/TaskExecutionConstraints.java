package io.aria.conductor.execution.adk;

import java.time.Duration;

/**
 * Task-level constraints a provider wants {@link AdkProvider#executeTask} to honor.
 *
 * <p>Frozen contract C0.6: the engine asks the provider for these constraints
 * instead of reading provider-specific properties itself. A provider that has no
 * opinion returns {@code null} from {@link AdkProvider#taskConstraints()}, or a
 * record whose {@link #maxTaskDuration()} is {@code null} — either way the engine
 * keeps its own fallback for the task deadline.
 *
 * @param maxTaskDuration maximum wall-clock duration of the whole task, or
 *                        {@code null} when the provider states none
 */
public record TaskExecutionConstraints(Duration maxTaskDuration) {
}

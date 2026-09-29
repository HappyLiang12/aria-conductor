package io.aria.conductor.execution.runtime;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * The run deadline policy of a coordinated execution attempt
 * ({@code aria.tasks.*}). One frozen deadline is read from here when a run's
 * {@link ExecutionSpec} is frozen, and it is the hard upper bound of the whole
 * attempt: every core session refuses a prompt without it (Task 11) and the
 * coordinator stops the runtime when the deadline elapses.
 *
 * <p>Task 13 replaces the previous use of the OpenCode-specific
 * {@code OpenCodeProperties#maxTaskMinutes} for this deadline: the deadline
 * belongs to the mode-neutral run, not to one provider. The default is 45
 * minutes.
 */
@Data
@Component
@ConfigurationProperties(prefix = "aria.tasks")
public class TaskDeadlineProperties {

    /** The documented default run deadline in minutes. */
    public static final int DEFAULT_DEADLINE_MINUTES = 45;

    /** Hard run deadline in minutes; the default is {@value #DEFAULT_DEADLINE_MINUTES}. */
    private int deadlineMinutes = DEFAULT_DEADLINE_MINUTES;

    /** The frozen run deadline as a duration. */
    public Duration deadline() {
        return Duration.ofMinutes(deadlineMinutes);
    }
}

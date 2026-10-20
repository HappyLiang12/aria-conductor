package io.aria.conductor.execution.runtime;

import io.aria.conductor.agent.service.SystemConfigService;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
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
@Slf4j
@Data
@Component
@ConfigurationProperties(prefix = "aria.tasks")
public class TaskDeadlineProperties {

    /** The documented default run deadline in minutes. */
    public static final int DEFAULT_DEADLINE_MINUTES = 45;

    /** Hard run deadline in minutes; the System Config key overrides it for new runs. */
    private int deadlineMinutes = DEFAULT_DEADLINE_MINUTES;

    private final SystemConfigService systemConfigService;

    /** Keeps the no-arg construction used by runtime convenience constructors. */
    public TaskDeadlineProperties() {
        this(null);
    }

    @Autowired
    public TaskDeadlineProperties(SystemConfigService systemConfigService) {
        this.systemConfigService = systemConfigService;
    }

    /** The frozen run deadline as a duration. */
    public Duration deadline() {
        int minutes = deadlineMinutes;
        try {
            if (systemConfigService != null) {
                minutes = systemConfigService.getInt(
                        "aria.tasks.deadline-minutes", deadlineMinutes, 1, 1440);
            }
        } catch (Exception e) {
            log.warn("Failed to read 'aria.tasks.deadline-minutes' from SystemConfig, using default {}",
                    deadlineMinutes, e);
        }
        return Duration.ofMinutes(minutes);
    }
}

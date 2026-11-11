package io.aria.conductor.execution.runtime;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Run admission limits ({@code aria.runs.*}): how many runs may execute at
 * once before the rest queue (status PENDING, mirror card in TODO). A value of
 * {@code 0} means unlimited for that pool. The Aria reservation keeps the
 * assistant's own runs from being starved by a worker flood; Aria runs also
 * serialize per conversation, so one parked conversation never blocks another
 * (operator decision, 2026-11-11: default raised 1 -> 3).
 */
@Data
@Component
@ConfigurationProperties(prefix = "aria.runs")
public class RunAdmissionProperties {

    /** Documented concurrent-run limit for non-Aria agents. */
    public static final int DEFAULT_MAX_ACTIVE = 6;

    /** Documented concurrent-run limit for the Aria assistant's own runs. */
    public static final int DEFAULT_ARIA_RESERVED = 3;

    /** Concurrent non-Aria runs; 0 = unlimited. */
    private int maxActive = DEFAULT_MAX_ACTIVE;

    /** Concurrent Aria-assistant runs; 0 = unlimited. Serialized per conversation. */
    private int ariaReserved = DEFAULT_ARIA_RESERVED;
}

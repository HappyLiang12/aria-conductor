package io.aria.conductor.common.config;

/**
 * Stable identities and permitted values of the System Config entries the
 * platform reads by name: the values frozen into other records -- a run's
 * deadline is written into its execution binding at admission and cannot be
 * re-resolved afterwards -- and the operator identity the context envelope
 * renders.
 *
 * <p>This is the one definition the admin API validates against, the runtime
 * reads through and the V73 migration guard asserts. The dashboard mirrors the
 * same metadata in {@code src/utils/runDeadline.ts}; its contract test fails
 * as soon as one side changes without the other.
 */
public final class SystemConfigKeys {

    /** Hard run deadline in minutes (range {@value #TASK_DEADLINE_MIN_MINUTES}-{@value #TASK_DEADLINE_MAX_MINUTES}). */
    public static final String TASK_DEADLINE_MINUTES = "aria.tasks.deadline-minutes";
    public static final int TASK_DEADLINE_DEFAULT_MINUTES = 45;
    public static final int TASK_DEADLINE_MIN_MINUTES = 1;
    public static final int TASK_DEADLINE_MAX_MINUTES = 1440;

    /**
     * Operator identity rendered by the context envelope's identity section
     * (spec §8.1): display name, IANA timezone and language tag. Durable
     * preferences live in memory, not here.
     */
    public static final String MEMORY_OPERATOR_NAME = "memory.operator.name";
    public static final String MEMORY_OPERATOR_TIMEZONE = "memory.operator.timezone";
    public static final String MEMORY_OPERATOR_LANGUAGE = "memory.operator.language";

    private SystemConfigKeys() {
    }
}

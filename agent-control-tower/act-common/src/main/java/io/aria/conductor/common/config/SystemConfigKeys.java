package io.aria.conductor.common.config;

/**
 * Stable identities and permitted values of the System Config entries whose
 * values are frozen into other records -- a run's deadline is written into its
 * execution binding at admission and cannot be re-resolved afterwards.
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

    private SystemConfigKeys() {
    }
}

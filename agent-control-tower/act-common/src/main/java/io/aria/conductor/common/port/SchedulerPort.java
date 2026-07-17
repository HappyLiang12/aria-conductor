package io.aria.conductor.common.port;

import io.aria.conductor.common.model.ScheduledJob;

/**
 * Port interface for scheduling tasks that fire notifications.
 * Lives in act-common to avoid circular module dependencies.
 */
public interface SchedulerPort {

    String schedule(ScheduledJob job);

    void cancel(String jobId);

    void pause(String jobId);

    void resume(String jobId);
}

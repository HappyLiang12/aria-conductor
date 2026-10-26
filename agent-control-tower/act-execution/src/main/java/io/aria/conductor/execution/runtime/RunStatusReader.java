package io.aria.conductor.execution.runtime;

import io.aria.conductor.common.model.RunStatus;

import java.util.UUID;

/**
 * The run store's committed status of a run, read straight from the database.
 *
 * <p>The coordinator consults this seam at the two points of a launch where a cancel can still
 * have landed without reaching this process's runtime record (after the handle was launched and
 * before the session opens, and again before the first prompt). The committed row is the only
 * place such a cancel is visible: a run cancelled while it was still PENDING/INITIALIZING -- before
 * the coordinator registered its runtime -- was recorded by the run store without a stop request,
 * and the launch must observe that instead of running a core nobody will stop.
 */
@FunctionalInterface
public interface RunStatusReader {

    /**
     * The run's committed status, or null when the run store serves no reader for it
     * (a wiring without the run store, or a run row that does not exist).
     */
    RunStatus committedStatus(UUID runId);
}

package io.aria.conductor.execution.runtime;

import java.util.Set;
import java.util.UUID;

/**
 * Quiescence view of the runtime: which writers of a run have verifiably
 * stopped and which runs of an agent are still active. Pause-independent
 * operations (cleanup, retirement) refuse active targets instead of killing
 * owned work.
 */
public interface RuntimeActivity {

    boolean writersStopped(UUID runId);

    Set<UUID> activeRuns(UUID agentId);
}

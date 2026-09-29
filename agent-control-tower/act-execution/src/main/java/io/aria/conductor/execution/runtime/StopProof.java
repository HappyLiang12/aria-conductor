package io.aria.conductor.execution.runtime;

import java.util.UUID;

/**
 * Evidence that all writers owned by the run have stopped. A prompt-completion
 * event is not a stop proof; export/capture and lock release require this
 * verified state.
 */
public record StopProof(UUID runId, boolean allWritersStopped) {
}

package io.aria.conductor.execution.runtime;

import io.aria.conductor.common.runtime.ExecutionMode;

import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.CompletionStage;

/**
 * Placement-specific lifecycle port (Host owned process or Sandbox
 * environment): prepare, launch, pause/resume/stop writers, export and destroy.
 * Export requires a verified {@link StopProof}; stop precedes capture, which
 * precedes destroy.
 */
public interface ExecutionBackend {

    ExecutionMode mode();

    PreparedEnvironment prepare(ExecutionSpec spec, WorkspaceLease workspace);

    RuntimeHandle launch(PreparedEnvironment environment, LaunchProfile profile);

    CompletionStage<ControlAck> pauseWriters(RuntimeHandle handle, Instant deadline);

    CompletionStage<ControlAck> resumeWriters(RuntimeHandle handle, Instant deadline);

    StopProof stopWriters(RuntimeHandle handle, Instant deadline);

    void exportWorkspace(RuntimeHandle handle, Path destination, StopProof proof);

    void destroy(RuntimeHandle handle);
}

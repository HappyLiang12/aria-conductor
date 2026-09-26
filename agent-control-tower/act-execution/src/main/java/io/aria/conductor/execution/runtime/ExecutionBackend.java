package io.aria.conductor.execution.runtime;

import io.aria.conductor.common.runtime.ExecutionMode;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
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

    /**
     * The run's minted control secret for the core session handshake, or an empty
     * bundle when this placement minted none (or the launched profile did not name
     * a control-secret file). The placement that mints a secret owns delivering it
     * to both sides: the runtime (its file or environment at launch) and the
     * session opener, which can never invent it.
     */
    default SecretBundle sessionSecret(RuntimeHandle handle) {
        return new SecretBundle(null, Map.of());
    }

    CompletionStage<ControlAck> pauseWriters(RuntimeHandle handle, Instant deadline);

    CompletionStage<ControlAck> resumeWriters(RuntimeHandle handle, Instant deadline);

    StopProof stopWriters(RuntimeHandle handle, Instant deadline);

    void exportWorkspace(RuntimeHandle handle, Path destination, StopProof proof);

    void destroy(RuntimeHandle handle);
}

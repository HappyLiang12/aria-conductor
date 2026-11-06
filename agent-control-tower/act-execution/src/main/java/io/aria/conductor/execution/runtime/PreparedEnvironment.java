package io.aria.conductor.execution.runtime;

import io.aria.conductor.common.runtime.ExecutionMode;

import java.net.URI;
import java.util.UUID;

/**
 * A prepared, run-owned execution environment before launch: its identity, the
 * controlled working/configuration directories and the protocol endpoint the
 * runtime must serve once started.
 */
public record PreparedEnvironment(UUID runId, ExecutionMode mode,
        String environmentId, String workingDirectory, String configurationDirectory,
        URI endpoint) {
}

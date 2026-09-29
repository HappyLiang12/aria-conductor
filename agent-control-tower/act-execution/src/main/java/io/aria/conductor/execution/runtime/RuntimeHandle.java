package io.aria.conductor.execution.runtime;

import io.aria.conductor.common.runtime.ExecutionMode;

import java.net.URI;
import java.util.UUID;

/**
 * Handle of one launched run-owned runtime. {@code ownershipIdentity} is the
 * supervised identity used to verify that only this run's process tree or
 * sandbox is adopted, paused or stopped -- never a process name.
 */
public record RuntimeHandle(UUID runId, ExecutionMode mode,
        String environmentId, String ownershipIdentity, URI endpoint) {
}

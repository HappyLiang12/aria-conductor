package io.aria.conductor.execution.runtime;

import io.aria.conductor.common.runtime.ExecutionMode;

/**
 * Protocol port of one core (Qoder, OpenCode): what it can do in a given mode,
 * how it is launched, and how a live session is opened. This is not a second
 * application provider registry -- existing provider facades delegate to the
 * shared runtime kernel.
 */
public interface CoreAdapter {

    String coreId();

    CoreCapabilities capabilities(ExecutionMode mode);

    LaunchProfile launchProfile(ExecutionSpec spec, PreparedEnvironment environment,
            SecretBundle credentials);

    CoreSession open(RuntimeHandle handle, ExecutionSpec spec, SecretBundle credentials);
}

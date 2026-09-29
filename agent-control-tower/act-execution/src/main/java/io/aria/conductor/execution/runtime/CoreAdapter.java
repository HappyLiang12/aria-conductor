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

    /**
     * Service-level health of this core's runtime prerequisites, with no agent
     * context (the per-core descriptor behind
     * {@code GET /api/v1/adk/providers/{id}/health} when no {@code AdkProvider}
     * bean serves the core). Unlike the provider probe
     * ({@code AdkProvider#isServiceHealthy()}), most cores have no long-running
     * service to reach: a core whose readiness is verified at launch reports
     * {@code true} here, which is exactly what "no service-level prerequisite
     * failed" means. A core with a real prerequisite overrides this.
     */
    default boolean serviceHealthy() {
        return true;
    }

    LaunchProfile launchProfile(ExecutionSpec spec, PreparedEnvironment environment,
            SecretBundle credentials);

    CoreSession open(RuntimeHandle handle, ExecutionSpec spec, SecretBundle credentials);
}

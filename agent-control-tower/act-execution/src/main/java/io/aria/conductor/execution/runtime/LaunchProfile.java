package io.aria.conductor.execution.runtime;

import java.util.List;
import java.util.Map;

/**
 * Memory-only launch description of one run-owned runtime: explicit argv,
 * environment and working directory. Both collections are defensively copied,
 * and {@link #toString()} is redacted so injected worker credentials cannot
 * leak through logging. Never serialized into an execution binding.
 */
public record LaunchProfile(List<String> argv, Map<String, String> env,
        String workingDirectory) {
    public LaunchProfile {
        argv = List.copyOf(argv);
        env = Map.copyOf(env);
    }

    @Override
    public String toString() {
        return "LaunchProfile[redacted]";
    }
}

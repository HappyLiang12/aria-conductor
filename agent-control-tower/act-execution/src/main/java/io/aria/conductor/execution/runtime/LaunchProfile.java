package io.aria.conductor.execution.runtime;

import java.util.List;
import java.util.Map;

/**
 * Memory-only launch description of one run-owned runtime: explicit argv,
 * environment and working directory, plus the run-owned file the placement
 * writes the run's minted control secret into before the runtime is started
 * ({@code null} for a runtime that authenticates another way, e.g. opencode).
 * Every collection is defensively copied, and {@link #toString()} is redacted so
 * injected worker credentials cannot leak through logging. Never serialized into
 * an execution binding.
 *
 * <p>The control secret is minted by the placement (spec 5.1) and never known to
 * the adapter, so the profile only names the file while the placement owns its
 * content: the host backend writes the minted secret there before it starts the
 * process, exactly like the committed bridge's contract demands. A profile that
 * names a file outside the run-owned configuration directory is refused at
 * launch, so a launch can never be told to spill a secret elsewhere.
 */
public record LaunchProfile(List<String> argv, Map<String, String> env,
        String workingDirectory, String controlSecretFile) {

    public LaunchProfile {
        argv = List.copyOf(argv);
        env = Map.copyOf(env);
        if (controlSecretFile != null && controlSecretFile.isBlank()) {
            controlSecretFile = null;
        }
    }

    /** A profile whose runtime does not read a run-owned control-secret file. */
    public LaunchProfile(List<String> argv, Map<String, String> env, String workingDirectory) {
        this(argv, env, workingDirectory, null);
    }

    @Override
    public String toString() {
        return "LaunchProfile[redacted]";
    }
}

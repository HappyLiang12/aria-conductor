package io.aria.conductor.execution.runtime;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Memory-only launch description of one run-owned runtime: explicit argv,
 * environment and working directory, plus the run-owned file the placement
 * writes the run's minted control secret into before the runtime is started
 * ({@code null} for a runtime that authenticates another way, e.g. opencode).
 * Every collection is defensively copied, and {@link #toString()} is redacted so
 * injected worker credentials cannot leak through logging. Never serialized into
 * an execution binding.
 *
 * <p>{@code secretValues} is the set of values this launch injects as secrets:
 * the credential bundle plus the run-scoped platform-MCP bearer
 * ({@code ARIA_MCP_TOKEN}), and the placement's minted control secret where one
 * is delivered. It travels on the profile on purpose: the run-owned core log
 * takes its redaction input from the same object the child is launched with, so
 * there is no second producer and nothing to drift. A profile constructed
 * without it declares no known secret, and the log then relies on the
 * credential-shaped-name rule alone. A declared secret must be a single line: a
 * value containing CR or LF is refused here, because the log masks by line and
 * could only mask such a value in part.
 *
 * <p>The control secret is minted by the placement (spec 5.1) and never known to
 * the adapter, so the profile only names the file while the placement owns its
 * content: the host backend writes the minted secret there before it starts the
 * process, exactly like the committed bridge's contract demands. A profile that
 * names a file outside the run-owned configuration directory is refused at
 * launch, so a launch can never be told to spill a secret elsewhere.
 */
public record LaunchProfile(List<String> argv, Map<String, String> env,
        String workingDirectory, String controlSecretFile, Set<String> secretValues) {

    public LaunchProfile {
        argv = List.copyOf(argv);
        env = Map.copyOf(env);
        if (controlSecretFile != null && controlSecretFile.isBlank()) {
            controlSecretFile = null;
        }
        secretValues = secretValues == null ? Set.of() : Set.copyOf(secretValues);
        for (String value : secretValues) {
            requireSingleLineSecret(value);
        }
    }

    /** Refuses a declared secret the line-scoped log cannot mask as a whole. */
    private static void requireSingleLineSecret(String value) {
        if (value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("A declared secret value must not contain CR or LF:"
                    + " the run-owned core log masks by line, so a multi-line value could only be"
                    + " masked in part and the rest would be written. Declare the secret as a single"
                    + " line, or do not declare it as a secret value.");
        }
    }

    /** A profile whose runtime does not read a run-owned control-secret file. */
    public LaunchProfile(List<String> argv, Map<String, String> env, String workingDirectory) {
        this(argv, env, workingDirectory, null, Set.of());
    }

    /** A profile that declares no known secret and names a control-secret file. */
    public LaunchProfile(List<String> argv, Map<String, String> env, String workingDirectory,
            String controlSecretFile) {
        this(argv, env, workingDirectory, controlSecretFile, Set.of());
    }

    @Override
    public String toString() {
        return "LaunchProfile[redacted]";
    }
}

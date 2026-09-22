package io.aria.conductor.execution.runtime;

import java.util.Map;

/**
 * Memory-only resolved credentials for one launch: a reference (also recorded
 * in the execution binding) plus the secret environment. The environment map is
 * defensively copied, and {@link #toString()} is redacted so a secret can never
 * leak through logging. Never serialized into an execution binding.
 */
public record SecretBundle(String reference, Map<String, String> environment) {
    public SecretBundle {
        environment = Map.copyOf(environment);
    }

    @Override
    public String toString() {
        return "SecretBundle[redacted]";
    }
}

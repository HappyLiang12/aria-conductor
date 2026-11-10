package io.aria.conductor.common.artifact;

import java.nio.file.Path;
import java.util.Optional;

/**
 * The outcome of a {@code spec.md} lookup. An unavailable artifact carries a
 * stable reason ({@link #MISSING}, {@link #EMPTY}, {@link #UNREADABLE},
 * {@link #INVALID_RUN_ID}) and the absolute path that was attempted, so the
 * caller can log a loud, diagnosable fallback. It never carries file content on
 * an unavailable result.
 */
public record SpecReadResult(SpecArtifact artifact, Path attemptedPath, String reason, String errorDetail) {

    public static final String MISSING = "missing";
    public static final String EMPTY = "empty";
    public static final String UNREADABLE = "unreadable";
    public static final String INVALID_RUN_ID = "invalid-run-id";

    public boolean available() {
        return artifact != null;
    }

    public Optional<SpecArtifact> artifactOptional() {
        return Optional.ofNullable(artifact);
    }

    static SpecReadResult missing(Path attemptedPath) {
        return new SpecReadResult(null, attemptedPath, MISSING, null);
    }

    static SpecReadResult empty(Path attemptedPath) {
        return new SpecReadResult(null, attemptedPath, EMPTY, null);
    }

    static SpecReadResult unreadable(Path attemptedPath, Throwable error) {
        String detail = error == null ? null : error.getClass().getName()
                + (error.getMessage() == null ? "" : ": " + error.getMessage());
        return new SpecReadResult(null, attemptedPath, UNREADABLE, detail);
    }

    static SpecReadResult invalidRunId(Path root) {
        return new SpecReadResult(null, root, INVALID_RUN_ID, null);
    }
}

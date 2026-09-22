package io.aria.conductor.execution.runtime;

import java.nio.file.Path;

/**
 * Captured workspace results after writers were verified stopped:
 * the retained directory, its manifest digest and whether capture completed.
 */
public record ArtifactBundle(Path directory,
        String manifestSha256, boolean complete) {
}

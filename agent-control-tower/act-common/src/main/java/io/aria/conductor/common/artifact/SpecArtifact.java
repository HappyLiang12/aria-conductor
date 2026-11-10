package io.aria.conductor.common.artifact;

import java.nio.file.Path;

/**
 * A captured {@code spec.md} artifact: its content and the resolved absolute
 * path it was read from. The path is part of the value so a caller can log the
 * exact location the read side used.
 */
public record SpecArtifact(String content, Path path) {
}

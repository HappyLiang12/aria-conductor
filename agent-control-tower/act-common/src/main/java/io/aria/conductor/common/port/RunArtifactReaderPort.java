package io.aria.conductor.common.port;

import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;

/**
 * Read-only view of a run's captured artifacts (#112). The execution module writes a
 * finished run's workspace into {@code <result-root>/<runId>/} and is therefore the only
 * code that knows how that location is spelled, provisioned and canonicalized; a consumer
 * that re-derives the path itself resolves the same contract twice, and the two
 * resolutions drift (an unexpanded {@code %NAME%} token, a blank value, a link alias and
 * the property default are each handled by one side only). This port is the same shape as
 * {@link RunBindingReaderPort}: declared here, served by the execution module, and
 * consumed as an optional dependency, so a deployment that never wires a reader still
 * boots.
 */
public interface RunArtifactReaderPort {

    /**
     * The canonical location the artifact would be read from. Diagnostic: callers log it
     * when a read comes back empty, so an operator can tell which path the reader looked
     * at instead of guessing the composition.
     */
    Path resolve(UUID runId, String fileName);

    /**
     * Bounded read of one artifact. Empty when the run has no such regular file, when the
     * entry is not a plain file inside the artifact root, or when reading it failed -- a
     * filesystem problem is never an exception for the caller, because a run whose
     * artifact is missing is a degraded case, not a broken one.
     *
     * @param maxChars upper bound on the returned content, counted in characters
     */
    Optional<RunArtifactText> read(UUID runId, String fileName, long maxChars);

    /**
     * One artifact read. {@code path} is the location {@link #read} actually loaded, the same
     * one {@link #resolve} returns; an artifact that is not there yields no record at all, so a
     * caller logging the checked path uses {@code resolve}.
     */
    record RunArtifactText(Path path, String content) {
    }
}

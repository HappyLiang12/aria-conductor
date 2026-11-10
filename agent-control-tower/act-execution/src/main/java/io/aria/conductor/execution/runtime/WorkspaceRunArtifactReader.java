package io.aria.conductor.execution.runtime;

import io.aria.conductor.common.port.RunArtifactReaderPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The execution module's answer to "where does a finished run's artifact live, and what
 * does it contain" (#112). Constructed with the result root the runtime wiring already
 * provisioned -- the same {@link Path} instance {@link RunWorkspaceService} captures
 * into, so the directory spelling, the {@code <runId>} segment and the canonical form are
 * inherited from the writer rather than re-derived next to it.
 *
 * <p>Every filesystem problem is reported as an absent artifact, never as an exception: a
 * caller acting on a completed run must still be able to do its job with the run's own
 * output when the export is missing, incomplete or unreadable.
 */
public class WorkspaceRunArtifactReader implements RunArtifactReaderPort {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceRunArtifactReader.class);

    private final Path resultRoot;

    /**
     * @param resultRoot the provisioned result root; it must exist, because the canonical
     *                   form adopted here is what makes a link alias of the root resolve to
     *                   the directory the writer wrote
     */
    public WorkspaceRunArtifactReader(Path resultRoot) {
        Objects.requireNonNull(resultRoot, "Result root is required");
        try {
            this.resultRoot = WorkspacePaths.canonicalDirectory(resultRoot);
        } catch (IOException e) {
            throw new IllegalArgumentException("Result root is not a canonical directory: " + resultRoot, e);
        }
    }

    @Override
    public Path resolve(UUID runId, String fileName) {
        String artifact = artifactName(fileName);
        if (runId == null) {
            // No run directory can be named; the artifact root is still the honest path to log.
            return resultRoot.resolve(artifact);
        }
        return resultRoot.resolve(runId.toString()).resolve(artifact);
    }

    @Override
    public Optional<RunArtifactText> read(UUID runId, String fileName, long maxChars) {
        Path path = resolve(runId, fileName);
        if (runId == null || maxChars <= 0 || !isPlainArtifact(path)) {
            return Optional.empty();
        }
        try {
            int bound = (int) Math.min(maxChars, Integer.MAX_VALUE);
            return Optional.of(new RunArtifactText(path, readBounded(path, bound)));
        } catch (Exception e) {
            log.warn("Cannot read the run artifact {}: {}", path, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * True only for a regular file that is reached from the canonical root through
     * canonical parents. A symbolic link or junction standing in for the file is reported
     * as absent and never followed, so a planted link cannot make this reader disclose a
     * file outside the run's artifact directory.
     */
    private boolean isPlainArtifact(Path path) {
        try {
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                return false;
            }
            return !WorkspacePaths.isLinkLikeEntry(path)
                    && WorkspacePaths.reachedThroughCanonicalParents(resultRoot, path);
        } catch (RuntimeException e) {
            log.warn("Cannot establish the artifact type of {}: {}", path, e.getMessage());
            return false;
        }
    }

    /** Reads at most {@code maxChars} characters, so an oversized artifact is never fully buffered. */
    private static String readBounded(Path path, int maxChars) throws IOException {
        char[] buffer = new char[maxChars];
        int read = 0;
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            int n;
            while (read < buffer.length && (n = reader.read(buffer, read, buffer.length - read)) > 0) {
                read += n;
            }
        }
        return new String(buffer, 0, read);
    }

    /** One plain file name below the run's directory: a caller cannot address another run or escape the root. */
    private static String artifactName(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            throw new IllegalArgumentException("Artifact file name is required");
        }
        if (fileName.indexOf('/') >= 0 || fileName.indexOf('\\') >= 0
                || ".".equals(fileName) || "..".equals(fileName)) {
            throw new IllegalArgumentException("Artifact must be addressed by plain file name below the "
                    + "run directory: " + fileName);
        }
        return fileName;
    }
}

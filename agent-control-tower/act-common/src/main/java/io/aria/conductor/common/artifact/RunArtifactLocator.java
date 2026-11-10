package io.aria.conductor.common.artifact;

import org.springframework.stereotype.Component;

import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * The single implementation of the run artifact filesystem contract:
 * {@code <result-root>/<runId>/spec.md}.
 *
 * <p>Both the capture (write) and the review (read) sides use this locator.
 * Neither module walks artifact directories or constructs a result path itself.
 * A run id is validated before it is used, and the resolved spec path is
 * verified to remain below the canonical root.
 */
@Component
public class RunArtifactLocator {

    /** The exported spec file name; the locator is the only place that knows it. */
    public static final String SPEC_FILE_NAME = "spec.md";

    /**
     * Upper bound on the characters read from {@code spec.md}, mirroring the
     * coordinator's stored-spec cap. A larger file is read up to this bound; the
     * caller truncates the stored content to the same limit.
     */
    static final int MAX_SPEC_CHARACTERS = 50 * 1024;

    private final RunArtifactProperties properties;

    public RunArtifactLocator(RunArtifactProperties properties) {
        this.properties = properties;
    }

    /** A locator whose root is already resolved (test seam for direct construction). */
    public static RunArtifactLocator forRoot(Path root) {
        return new RunArtifactLocator(RunArtifactProperties.forResolvedRoot(root));
    }

    /** The canonical normalized run result root. */
    public Path resultRoot() {
        return properties.resolvedResultRoot();
    }

    /** The canonical directory that holds one run's exported artifacts. */
    public Path resultDir(String runId) {
        Path root = resultRoot();
        Path dir = root.resolve(requireValidRunId(runId)).normalize();
        if (!dir.startsWith(root)) {
            throw new IllegalArgumentException(invalidRunIdMessage(runId));
        }
        return dir;
    }

    /** The canonical path of one run's exported {@code spec.md}. */
    public Path specPath(String runId) {
        return resultDir(runId).resolve(SPEC_FILE_NAME);
    }

    /**
     * Reads {@code spec.md} and returns the content together with the resolved
     * path it was read from, or an unavailable result carrying a reason and the
     * attempted absolute path.
     */
    public SpecReadResult readSpecResult(String runId) {
        Path root = resultRoot();
        if (!isValidRunId(runId)) {
            return SpecReadResult.invalidRunId(root);
        }
        Path spec = root.resolve(runId).resolve(SPEC_FILE_NAME).normalize();
        if (!spec.startsWith(root)) {
            return SpecReadResult.invalidRunId(root);
        }
        if (!Files.isRegularFile(spec)) {
            return SpecReadResult.missing(spec);
        }
        try (Reader reader = Files.newBufferedReader(spec, StandardCharsets.UTF_8)) {
            char[] buffer = new char[MAX_SPEC_CHARACTERS];
            int read = 0;
            int n;
            while (read < buffer.length && (n = reader.read(buffer, read, buffer.length - read)) > 0) {
                read += n;
            }
            if (read == 0) {
                return SpecReadResult.empty(spec);
            }
            String content = new String(buffer, 0, read);
            if (content.isBlank()) {
                return SpecReadResult.empty(spec);
            }
            return new SpecReadResult(new SpecArtifact(content, spec), spec, null, null);
        } catch (Exception e) {
            return SpecReadResult.unreadable(spec, e);
        }
    }

    /** The content and path of the run's spec, when it is available. */
    public Optional<SpecArtifact> readSpec(String runId) {
        return readSpecResult(runId).artifactOptional();
    }

    /**
     * A run id is safe only when it is non-blank, contains no path separator,
     * no {@code ..} segment, is not absolute and cannot escape the root.
     */
    public static boolean isValidRunId(String runId) {
        if (runId == null || runId.isBlank()) {
            return false;
        }
        if (runId.contains("/") || runId.contains("\\") || runId.contains("..")) {
            return false;
        }
        try {
            if (Path.of(runId).isAbsolute()) {
                return false;
            }
        } catch (RuntimeException e) {
            return false;
        }
        return true;
    }

    private static String requireValidRunId(String runId) {
        if (!isValidRunId(runId)) {
            throw new IllegalArgumentException(invalidRunIdMessage(runId));
        }
        return runId;
    }

    private static String invalidRunIdMessage(String runId) {
        return "Invalid run id for " + RunArtifactProperties.PROPERTY_KEY + ": "
                + (runId == null ? "null" : "'" + runId + "'")
                + " (blank, path separators, '..', and absolute paths are refused)";
    }
}

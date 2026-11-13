package io.aria.conductor.test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Temp-directory fixtures that adopt the same spelling production adopts.
 * <p>
 * Production roots are canonical ({@code toRealPath()}) — the workspace manager and the
 * run-workspace service both adopt at construction, and the link-safe deletion guard refuses
 * a base that is not its own real path. JUnit's {@code @TempDir} and
 * {@link Files#createTempDirectory} return the operating system's spelling instead, which on
 * macOS is {@code /var/...} while the real path is {@code /private/var/...}. Comparing an
 * adopted path with an unresolved fixture then fails for a reason that has nothing to do with
 * the behaviour under test, and a fixture that feeds a deletion base fails even harder.
 * <p>
 * Adopt fixture temp roots through this class instead of re-rolling {@code toRealPath()} in
 * each test.
 */
public final class TempPaths {

    private TempPaths() {
    }

    /** The real path of {@code path}: the fixture-side twin of what production adopts. */
    public static Path canonical(Path path) {
        try {
            return path.toRealPath();
        } catch (IOException e) {
            throw new UncheckedIOException("cannot canonicalize fixture path " + path, e);
        }
    }

    /** Creates a fresh temp directory and returns it canonically. */
    public static Path createCanonicalTempDirectory(String prefix) {
        try {
            return Files.createTempDirectory(prefix).toRealPath();
        } catch (IOException e) {
            throw new UncheckedIOException("cannot create temp directory " + prefix, e);
        }
    }

    /**
     * A directory link: a junction on Windows, where creating a symbolic link may need
     * elevation; a symbolic link everywhere else.
     */
    public static Path createDirectoryAlias(Path link, Path target) {
        try {
            if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
                ProcessBuilder builder = new ProcessBuilder("cmd", "/c", "mklink", "/J",
                        link.toString(), target.toString()).redirectErrorStream(true);
                Process process = builder.start();
                String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                if (!process.waitFor(20, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    throw new IllegalStateException("mklink /J timed out for " + link);
                }
                if (process.exitValue() != 0) {
                    throw new IllegalStateException("mklink /J failed for " + link + ": " + output);
                }
            } else {
                Files.createSymbolicLink(link, target);
            }
            return link;
        } catch (IOException e) {
            throw new UncheckedIOException("cannot create directory alias " + link + " -> " + target, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while creating directory alias " + link, e);
        }
    }
}

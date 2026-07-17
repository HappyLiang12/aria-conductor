package io.aria.conductor.knowledge.selfimprove;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * Process-level isolated executor used to validate skill scripts before
 * promotion to Stage 4. Every script is written to a tmp file, executed
 * with the matching interpreter via {@link ProcessBuilder} and its stdout
 * /stderr are captured up to {@link #MAX_OUTPUT_BYTES}. A wall-clock
 * {@link #TIMEOUT_SECONDS} is enforced via {@code waitFor(timeout)}.
 */
@Service
public class SandboxExecutor {

    private static final Logger log = LoggerFactory.getLogger(SandboxExecutor.class);

    public static final int TIMEOUT_SECONDS = 300;
    public static final long MAX_OUTPUT_BYTES = 1_000_000L; // 1 MB

    /**
     * Effective timeout in seconds. Defaults to {@link #TIMEOUT_SECONDS}
     * but can be overridden by tests via {@link #setTimeoutSeconds(int)}.
     */
    private int timeoutSeconds = TIMEOUT_SECONDS;

    /** Override the wall-clock timeout (visible for tests). */
    public void setTimeoutSeconds(int seconds) {
        this.timeoutSeconds = seconds;
    }

    /**
     * Execute {@code scriptContent} in {@code language} with environment
     * variables {@code inputs}. Returns a {@link SandboxResult} describing
     * the outcome. Never throws on script-level errors — they are surfaced
     * through {@code exitCode}/{@code stderr}.
     */
    public SandboxResult execute(String scriptContent, String language, Map<String, String> inputs) {
        Objects.requireNonNull(scriptContent, "scriptContent");
        Objects.requireNonNull(language, "language");
        Map<String, String> env = inputs != null ? inputs : Map.of();

        Path script = null;
        long start = System.currentTimeMillis();
        try {
            script = writeScript(scriptContent, language);
            List<String> command = buildCommand(language, script);
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.environment().putAll(env);
            pb.redirectErrorStream(false);

            Process process = pb.start();

            ByteArrayOutputStream stdout = new ByteArrayOutputStream();
            ByteArrayOutputStream stderr = new ByteArrayOutputStream();
            Thread tOut = drainAsync(process.getInputStream(), stdout);
            Thread tErr = drainAsync(process.getErrorStream(), stderr);

            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                joinQuietly(tOut);
                joinQuietly(tErr);
                long elapsed = System.currentTimeMillis() - start;
                return new SandboxResult(-1,
                        truncate(stdout.toByteArray()),
                        truncate(stderr.toByteArray()),
                        elapsed,
                        true);
            }
            joinQuietly(tOut);
            joinQuietly(tErr);
            int exitCode = process.exitValue();
            long elapsed = System.currentTimeMillis() - start;
            return new SandboxResult(exitCode,
                    truncate(stdout.toByteArray()),
                    truncate(stderr.toByteArray()),
                    elapsed,
                    false);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            long elapsed = System.currentTimeMillis() - start;
            log.warn("Sandbox execution failed: {}", e.toString());
            return new SandboxResult(-1, "", e.getMessage() == null ? "" : e.getMessage(),
                    elapsed, false);
        } catch (IllegalArgumentException e) {
            long elapsed = System.currentTimeMillis() - start;
            return new SandboxResult(-1, "", e.getMessage() == null ? "" : e.getMessage(),
                    elapsed, false);
        } finally {
            if (script != null) {
                try {
                    Files.deleteIfExists(script);
                } catch (IOException ignored) {
                    // best-effort cleanup
                }
            }
        }
    }

    // ---- helpers -------------------------------------------------------

    private Path writeScript(String content, String language) throws IOException {
        String suffix = switch (language.toLowerCase()) {
            case "python", "py" -> ".py";
            case "bash", "sh" -> ".sh";
            case "javascript", "js", "node" -> ".js";
            default -> ".txt";
        };
        Path tmp = Files.createTempFile("act-sandbox-", suffix);
        Files.writeString(tmp, content, StandardCharsets.UTF_8);
        // POSIX systems: make shell scripts executable (best-effort).
        try { tmp.toFile().setExecutable(true); } catch (SecurityException ignored) { }
        return tmp;
    }

    private List<String> buildCommand(String language, Path script) {
        List<String> cmd = new ArrayList<>();
        switch (language.toLowerCase()) {
            case "python", "py" -> {
                cmd.add(resolveInterpreter("python", "python3"));
                cmd.add(script.toString());
            }
            case "bash", "sh" -> {
                cmd.add(isWindows() ? "bash" : "/bin/bash");
                cmd.add(script.toString());
            }
            case "javascript", "js", "node" -> {
                cmd.add("node");
                cmd.add(script.toString());
            }
            default -> throw new IllegalArgumentException("Unsupported language: " + language);
        }
        return cmd;
    }

    private String resolveInterpreter(String primary, String fallback) {
        // Picking the binary is left to OS PATH resolution.
        return isWindows() ? primary : fallback;
    }

    private boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    private Thread drainAsync(InputStream in, ByteArrayOutputStream sink) {
        Thread t = new Thread(() -> {
            byte[] buf = new byte[4096];
            long total = 0;
            try {
                int n;
                while ((n = in.read(buf)) != -1) {
                    if (total >= MAX_OUTPUT_BYTES) {
                        // Drain the rest into the void to keep the child unblocked.
                        continue;
                    }
                    long room = MAX_OUTPUT_BYTES - total;
                    int write = (int) Math.min(n, room);
                    sink.write(buf, 0, write);
                    total += write;
                }
            } catch (IOException ignored) {
                // process termination → stream closed
            }
        }, "sandbox-drain");
        t.setDaemon(true);
        t.start();
        return t;
    }

    private void joinQuietly(Thread t) {
        try {
            t.join(2000);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    private String truncate(byte[] bytes) {
        if (bytes.length <= MAX_OUTPUT_BYTES) {
            return new String(bytes, StandardCharsets.UTF_8);
        }
        byte[] trimmed = new byte[(int) MAX_OUTPUT_BYTES];
        System.arraycopy(bytes, 0, trimmed, 0, trimmed.length);
        return new String(trimmed, StandardCharsets.UTF_8) + "\n[truncated]";
    }

    /** Result of a sandbox run; immutable. */
    public record SandboxResult(
            int exitCode,
            String stdout,
            String stderr,
            long durationMs,
            boolean timedOut) {
        public boolean isSuccess() { return !timedOut && exitCode == 0; }
    }

    // For tests that need an empty input map without importing Map.
    public static Map<String, String> noInputs() {
        return new HashMap<>();
    }
}

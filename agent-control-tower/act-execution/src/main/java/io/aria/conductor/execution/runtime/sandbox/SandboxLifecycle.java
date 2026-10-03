package io.aria.conductor.execution.runtime.sandbox;

import com.alibaba.opensandbox.sandbox.Sandbox;
import com.alibaba.opensandbox.sandbox.config.ConnectionConfig;
import com.alibaba.opensandbox.sandbox.domain.models.execd.executions.Execution;
import com.alibaba.opensandbox.sandbox.domain.models.execd.executions.ExecutionLogs;
import com.alibaba.opensandbox.sandbox.domain.models.execd.executions.ExecutionResult;
import com.alibaba.opensandbox.sandbox.domain.models.execd.executions.OutputMessage;
import com.alibaba.opensandbox.sandbox.domain.models.execd.executions.RunCommandRequest;
import com.alibaba.opensandbox.sandbox.domain.models.execd.filesystem.EntryInfo;
import com.alibaba.opensandbox.sandbox.domain.models.execd.filesystem.WriteEntry;
import com.alibaba.opensandbox.sandbox.domain.models.sandboxes.SandboxEndpoint;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aria.conductor.execution.adk.TaskExecutionException;
import io.aria.conductor.execution.runtime.ArtifactBundle;
import io.aria.conductor.execution.runtime.ControlAck;
import io.aria.conductor.execution.runtime.ControlState;
import io.aria.conductor.execution.runtime.StopProof;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.function.LongConsumer;
import java.util.function.LongSupplier;

/**
 * Run-owned Sandbox lifecycle (spec 4.1, 5.1): every sandbox operation is keyed
 * by the <em>run</em> UUID, never by an agent UUID, and stop/destroy are separate
 * operations. Writers are stopped first; the sandbox filesystem and its export
 * facility survive the stop so {@link #export} can capture a stable tree, and
 * only then does {@link #destroy} kill the sandbox. An export that cannot be
 * completed is reported as an incomplete artifact bundle, never as a stable diff.
 * The export gate requires both the caller's stop proof and a verified stop this
 * lifecycle recorded after the last launch or resume, so neither a stale proof
 * nor a minted one can unlock a tree that may be moving.
 *
 * <p>The SDK is reached only through the {@link SandboxSdk} seam so the ordering
 * and the refusal rules can be tested with a recording double. {@link OpenSandboxSdk}
 * is the production implementation; it also serves the legacy
 * {@code OpenCodeSandboxManager} facade, so the SDK endpoint/upload/renew/kill
 * semantics exist exactly once.
 *
 * <p>Nothing here builds a shell string from caller-supplied argv: the launch
 * argv travels in a trusted, validated {@link LaunchManifest} that the fixed
 * image launcher ({@code /opt/aria/launch.mjs}) reads and spawns with
 * {@code shell: false}. The lifecycle only ever issues constant command shapes
 * (launcher path, control-script path, run directory).
 */
@Slf4j
public class SandboxLifecycle implements AutoCloseable {

    /** Default sandbox-internal control root (writable by the unprivileged image user). */
    public static final String DEFAULT_CONTROL_ROOT = "/home/aria/run";
    /** Default sandbox-internal workspace root. */
    public static final String DEFAULT_WORKSPACE_ROOT = "/workspace";
    /** Fixed launcher shipped inside every core image (context: agent-control-tower/runtime-sandbox). */
    static final String LAUNCHER = "/opt/aria/launch.mjs";
    /** Fixed writer-control script shipped inside every core image. */
    static final String STOP_WRITERS = "/opt/aria/stop-writers.mjs";
    /** Manifest file name inside the run-owned control directory. */
    static final String MANIFEST_FILE = "launch-manifest.json";
    /** Upper bound on one bounded control command (stop/suspend/resume). */
    private static final Duration DEFAULT_COMMAND_TIMEOUT = Duration.ofMinutes(2);

    /**
     * The sandbox SDK operations the lifecycle owns, addressed by sandbox id.
     * The recording test double appends these literal operation names.
     */
    public interface SandboxSdk {

        /** One file discovered by {@link #export}, relative to the exported root. */
        record RemoteFile(String path, byte[] data) {
        }

        String create(String image, Map<String, String> env);

        void upload(String sandboxId, List<WriteEntry> entries);

        /** Read-only endpoint lookup (not a state-changing operation). */
        String endpoint(String sandboxId, int port);

        void launch(String sandboxId, String runDirectory, Duration timeout);

        String pauseWriters(String sandboxId, String runDirectory, Duration timeout);

        String resumeWriters(String sandboxId, String runDirectory, Duration timeout);

        String stopWriters(String sandboxId, String runDirectory, Duration timeout);

        void renew(String sandboxId, Duration extension);

        List<RemoteFile> export(String sandboxId, String remoteRoot);

        void kill(String sandboxId);
    }

    /** One run's live sandbox ownership record. */
    private static final class Run {
        final UUID runId;
        final String sandboxId;
        final String runDirectory;
        volatile URI endpoint;
        volatile ScheduledFuture<?> renewal;
        /** Set by a verified stop; cleared by launch and resume so a stale proof can never unlock an export. */
        volatile boolean verifiedStop;

        Run(UUID runId, String sandboxId, String runDirectory) {
            this.runId = runId;
            this.sandboxId = sandboxId;
            this.runDirectory = runDirectory;
        }
    }

    private final SandboxSdk sdk;
    private final String controlRoot;
    private final String workspaceRoot;
    private final Duration commandTimeout;
    private final Map<UUID, Run> runs = new ConcurrentHashMap<>();
    private final ScheduledExecutorService renewals = Executors.newSingleThreadScheduledExecutor(daemonFactory());
    private volatile Duration renewalInterval = Duration.ofMinutes(10);
    private volatile Duration renewalExtension = Duration.ofMinutes(30);

    public SandboxLifecycle(SandboxSdk sdk) {
        this(sdk, DEFAULT_CONTROL_ROOT, DEFAULT_WORKSPACE_ROOT, DEFAULT_COMMAND_TIMEOUT);
    }

    public SandboxLifecycle(SandboxSdk sdk, String controlRoot, String workspaceRoot, Duration commandTimeout) {
        this.sdk = Objects.requireNonNull(sdk, "sdk");
        this.controlRoot = requireAbsoluteDirectory(controlRoot, "controlRoot");
        this.workspaceRoot = requireAbsoluteDirectory(workspaceRoot, "workspaceRoot");
        this.commandTimeout = Objects.requireNonNull(commandTimeout, "commandTimeout");
    }

    /** The sandbox-internal workspace root uploaded into and exported from. */
    public String workspaceRoot() {
        return workspaceRoot;
    }

    /** Renewal plan for runs started after this call (production defaults: 10 min / 30 min). */
    public void setRenewalPlan(Duration interval, Duration extension) {
        this.renewalInterval = Objects.requireNonNull(interval, "interval");
        this.renewalExtension = Objects.requireNonNull(extension, "extension");
    }

    /** Whether this lifecycle currently owns an environment for the run. */
    public boolean owns(UUID runId) {
        return runId != null && runs.containsKey(runId);
    }

    /** The sandbox id of an owned run (memory-only; empty after destroy or on another lifecycle). */
    public Optional<String> sandboxId(UUID runId) {
        Run run = runId == null ? null : runs.get(runId);
        return run == null ? Optional.empty() : Optional.of(run.sandboxId);
    }

    // ------------------------------------------------------------------ create / endpoint

    /**
     * Creates the run's sandbox. A run key is never reused: a second create for
     * the same run is refused instead of silently leaking the first sandbox.
     */
    public String create(UUID runId, String image, Map<String, String> env) {
        Objects.requireNonNull(runId, "runId");
        if (image == null || image.isBlank()) {
            throw new IllegalArgumentException("A sandbox image is required for run " + runId);
        }
        if (runs.containsKey(runId)) {
            throw new IllegalStateException("This lifecycle already owns run " + runId
                    + "; a sandbox run key is never reused");
        }
        String sandboxId = sdk.create(image, env == null ? Map.of() : Map.copyOf(env));
        Run run = new Run(runId, sandboxId, controlRoot + "/" + runId);
        runs.put(runId, run);
        log.info("Sandbox {} created for run {} from image {}", sandboxId, runId, image);
        return sandboxId;
    }

    /** Resolves the externally reachable, scheme-completed endpoint of the run's core port. */
    public URI resolveEndpoint(UUID runId, int port) {
        Run run = require(runId);
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("Invalid core port " + port + " for run " + runId);
        }
        String raw = sdk.endpoint(run.sandboxId, port);
        if (raw == null || raw.isBlank()) {
            throw new IllegalStateException("The sandbox endpoint lookup returned nothing for run " + runId);
        }
        URI endpoint = URI.create(raw.contains("://") ? raw : "http://" + raw);
        run.endpoint = endpoint;
        return endpoint;
    }

    // ------------------------------------------------------------------ upload / launch

    /**
     * Uploads the run's workspace snapshot and its trusted launch manifest in one
     * SDK write. The manifest is validated before anything is uploaded.
     */
    public void uploadSnapshot(UUID runId, Path snapshotRoot, LaunchManifest manifest) {
        Run run = require(runId);
        Objects.requireNonNull(manifest, "manifest");
        if (!runId.equals(manifest.runId())) {
            throw new IllegalArgumentException("Manifest belongs to run " + manifest.runId()
                    + ", not " + runId);
        }
        List<WriteEntry> entries = new ArrayList<>();
        collectWorkspaceEntries(snapshotRoot, workspaceRoot, entries);
        entries.add(WriteEntry.builder()
                .path(run.runDirectory + "/" + MANIFEST_FILE)
                .data(manifest.toJson())
                .mode(600)
                .build());
        sdk.upload(run.sandboxId, entries);
        log.info("Uploaded {} entry(ies) into sandbox {} for run {}", entries.size(), run.sandboxId, runId);
    }

    /**
     * Uploads the run-owned host-side configuration subtree into the sandbox's
     * run control directory ({@code <controlRoot>/<runId>/<directoryName>}), so
     * a sandbox launch finds its governed configuration at the XDG paths the
     * launch manifest references. A host path in the manifest environment would
     * resolve relative to the container cwd and silently disable the governed
     * configuration.
     */
    public void uploadRunConfiguration(UUID runId, Path hostConfigurationDirectory, String directoryName) {
        Run run = require(runId);
        requireDirectoryName(Objects.requireNonNull(directoryName, "directoryName"), "directoryName");
        Path host = hostConfigurationDirectory.resolve(directoryName);
        if (!Files.isDirectory(host)) {
            log.info("No host-side '{}' subtree to upload for run {} ({}), skipping", directoryName, runId, host);
            return;
        }
        List<WriteEntry> entries = new ArrayList<>();
        collectWorkspaceEntries(host, run.runDirectory + "/" + directoryName, entries);
        if (entries.isEmpty()) {
            return;
        }
        sdk.upload(run.sandboxId, entries);
        log.info("Uploaded {} run configuration entry(ies) into '{}' of run {}", entries.size(), directoryName, runId);
    }

    /** Starts the fixed image launcher for the run's uploaded manifest. */
    public void launch(UUID runId) {
        Run run = require(runId);
        // A launch starts the writer tree: any earlier verified stop is no longer current.
        run.verifiedStop = false;
        sdk.launch(run.sandboxId, run.runDirectory, commandTimeout);
        startRenewal(runId, renewalInterval, renewalExtension);
    }

    // ------------------------------------------------------------------ control

    /**
     * Renewals run during execution and human/manual waits and stop for teardown;
     * a renewal failure is logged and never kills the run.
     */
    public void startRenewal(UUID runId, Duration interval, Duration extension) {
        Run run = require(runId);
        Objects.requireNonNull(interval, "interval");
        Objects.requireNonNull(extension, "extension");
        stopRenewal(runId);
        run.renewal = renewals.scheduleAtFixedRate(() -> {
            try {
                sdk.renew(run.sandboxId, extension);
            } catch (RuntimeException e) {
                log.warn("Renewal failed for sandbox {} of run {}: {}", run.sandboxId, runId, e.getMessage());
            }
        }, interval.toMillis(), interval.toMillis(), TimeUnit.MILLISECONDS);
    }

    /** Stops the run's renewal; called for teardown and on destroy. */
    public void stopRenewal(UUID runId) {
        Run run = runs.get(runId);
        if (run == null) {
            return;
        }
        ScheduledFuture<?> renewal = run.renewal;
        if (renewal != null) {
            renewal.cancel(false);
            run.renewal = null;
        }
    }

    public ControlAck pauseWriters(UUID runId, Instant deadline) {
        Run run = require(runId);
        Duration timeout = boundedTimeout(deadline);
        String output = sdk.pauseWriters(run.sandboxId, run.runDirectory, timeout);
        boolean suspended = booleanField(output, "suspended");
        return suspended ? new ControlAck(ControlState.PAUSED, true) : new ControlAck(ControlState.RUNNING, false);
    }

    public ControlAck resumeWriters(UUID runId, Instant deadline) {
        Run run = require(runId);
        Duration timeout = boundedTimeout(deadline);
        // A resume attempt moves the writer tree again: the earlier verified stop is no longer current.
        run.verifiedStop = false;
        String output = sdk.resumeWriters(run.sandboxId, run.runDirectory, timeout);
        boolean resumed = booleanField(output, "resumed");
        return resumed ? new ControlAck(ControlState.RUNNING, true) : new ControlAck(ControlState.PAUSED, false);
    }

    /**
     * Stops every writer the run owns and returns the proof export/capture
     * require. An unparseable or negative report is never reported as stopped
     * and never arms the export gate; only a report that verifies the stop does.
     */
    public StopProof stopWriters(UUID runId, Instant deadline) {
        Run run = require(runId);
        Duration timeout = boundedTimeout(deadline);
        String output = sdk.stopWriters(run.sandboxId, run.runDirectory, timeout);
        boolean stopped = booleanField(output, "writersStopped");
        run.verifiedStop = stopped;
        if (!stopped) {
            log.warn("Writers for run {} are not verified stopped: {}", runId, oneLine(output));
        }
        return new StopProof(runId, stopped);
    }

    // ------------------------------------------------------------------ export / destroy

    /**
     * Exports the run's sandbox workspace into the managed result directory. The
     * sandbox filesystem must still exist (writers stopped by this lifecycle,
     * environment not destroyed), and a failed capture is reported as an
     * incomplete bundle.
     */
    public ArtifactBundle export(UUID runId, Path destination, StopProof proof) {
        Run run = require(runId);
        requireVerifiedStop(run, proof);
        Path target = Objects.requireNonNull(destination, "destination").toAbsolutePath().normalize();
        try {
            Files.createDirectories(target);
        } catch (IOException e) {
            log.warn("Unable to create the export destination {} for run {}: {}", target, runId, e.getMessage());
            return new ArtifactBundle(target, null, false);
        }
        List<SandboxSdk.RemoteFile> files;
        try {
            files = sdk.export(run.sandboxId, workspaceRoot);
        } catch (RuntimeException e) {
            log.warn("Export of run {} failed: {}", runId, e.getMessage());
            return new ArtifactBundle(target, null, false);
        }
        try {
            List<String> manifestLines = new ArrayList<>();
            for (SandboxSdk.RemoteFile file : files) {
                Path relative = relativeExportPath(file.path(), runId);
                Path resolved = target.resolve(relative).normalize();
                if (!resolved.startsWith(target)) {
                    throw new IllegalStateException("Exported path escapes the artifact root: " + file.path());
                }
                if (resolved.getParent() != null) {
                    Files.createDirectories(resolved.getParent());
                }
                Files.write(resolved, file.data());
                manifestLines.add(sha256(file.data()) + "  " + relative.toString().replace('\\', '/'));
            }
            return new ArtifactBundle(target, sha256(String.join("\n", manifestLines).getBytes(StandardCharsets.UTF_8)), true);
        } catch (IOException | RuntimeException e) {
            log.warn("Export of run {} is incomplete: {}", runId, e.getMessage());
            return new ArtifactBundle(target, null, false);
        }
    }

    /**
     * Export admission: the caller's proof must be verified and name this run,
     * and this lifecycle must have recorded a verified stop that no launch or
     * resume has since invalidated. A proof object alone never unlocks an export
     * of a tree that may be moving again.
     */
    private void requireVerifiedStop(Run run, StopProof proof) {
        UUID runId = run.runId;
        if (proof == null || !proof.allWritersStopped()) {
            throw new IllegalStateException("Writers are not stopped");
        }
        if (!runId.equals(proof.runId())) {
            throw new IllegalStateException("Stop proof of run " + proof.runId()
                    + " does not belong to run " + runId);
        }
        if (!run.verifiedStop) {
            throw new IllegalStateException("No current verified stop for run " + runId);
        }
    }

    /**
     * Destroys the run's sandbox, after export. Renewal stops first so a teardown
     * is never followed by a renewal of a dead sandbox; a missing run is a no-op.
     */
    public void destroy(UUID runId) {
        stopRenewal(runId);
        Run run = runs.remove(runId);
        if (run == null) {
            return;
        }
        sdk.kill(run.sandboxId);
        log.info("Sandbox {} destroyed for run {}", run.sandboxId, runId);
    }

    /** Stops the renewal scheduler. Owned sandboxes are left to the caller's recovery path. */
    @Override
    public void close() {
        renewals.shutdownNow();
    }

    // ------------------------------------------------------------------ internals

    private Run require(UUID runId) {
        Run run = runId == null ? null : runs.get(runId);
        if (run == null) {
            throw new IllegalStateException("Run " + runId + " is not owned by this sandbox lifecycle");
        }
        return run;
    }

    /** The run's original hard deadline bounds every control request; an elapsed one is refused. */
    private Duration boundedTimeout(Instant deadline) {
        Objects.requireNonNull(deadline, "deadline");
        Duration remaining = Duration.between(Instant.now(), deadline);
        if (remaining.isNegative() || remaining.isZero()) {
            throw new IllegalStateException("The control deadline has elapsed before the sandbox operation could run: "
                    + deadline);
        }
        return remaining.compareTo(commandTimeout) < 0 ? remaining : commandTimeout;
    }

    /** Reads a boolean field from the last JSON line a control script printed. */
    static boolean booleanField(String output, String field) {
        if (output == null || output.isBlank()) {
            return false;
        }
        String[] lines = output.strip().split("\\R");
        for (int index = lines.length - 1; index >= 0; index--) {
            String line = lines[index].trim();
            if (!line.startsWith("{")) {
                continue;
            }
            try {
                Map<String, Object> parsed = MAPPER.readValue(line, new TypeReference<>() {
                });
                return Boolean.TRUE.equals(parsed.get(field));
            } catch (IOException e) {
                return false;
            }
        }
        return false;
    }

    /** Paths are relative to the exported root; absolute or traversing paths are refused. */
    private static Path relativeExportPath(String path, UUID runId) {
        if (path == null || path.isBlank()) {
            throw new IllegalStateException("Export of run " + runId + " reported an empty path");
        }
        String normalized = path.replace('\\', '/');
        if (normalized.startsWith("/")) {
            throw new IllegalStateException("Export of run " + runId + " reported an absolute path: " + path);
        }
        Path relative = Path.of(normalized).normalize();
        if (relative.isAbsolute() || relative.startsWith("..")) {
            throw new IllegalStateException("Export of run " + runId + " reported a traversing path: " + path);
        }
        return relative;
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private static String oneLine(String text) {
        return text == null ? "" : text.strip().replace('\n', ' ');
    }

    private static String requireAbsoluteDirectory(String value, String name) {
        if (!isSandboxAbsolutePath(value)) {
            throw new IllegalArgumentException(name + " must be an absolute sandbox path, got: " + value);
        }
        return value.endsWith("/") && value.length() > 1 ? value.substring(0, value.length() - 1) : value;
    }

    /**
     * Sandbox paths are POSIX paths inside the container image; they are checked
     * as such so validation behaves identically on a Windows development host.
     */
    static boolean isSandboxAbsolutePath(String value) {
        return value != null && value.length() > 1 && value.startsWith("/") && !value.startsWith("//");
    }

    /**
     * The name of an uploaded subtree inside the run control directory: one plain
     * directory name, never a path. It is interpolated into a sandbox path, so a
     * separator, a traversal segment or an absolute path is refused instead of
     * being resolved.
     */
    private static String requireDirectoryName(String value, String name) {
        if (!value.matches("[A-Za-z0-9_-]+")) {
            throw new IllegalArgumentException(name + " must be a plain directory name, got: " + value);
        }
        return value;
    }

    private static ThreadFactory daemonFactory() {
        return runnable -> {
            Thread thread = new Thread(runnable, "sandbox-renewal");
            thread.setDaemon(true);
            return thread;
        };
    }

    // ------------------------------------------------------------------ snapshot upload walk

    /** Bounded walk of the host-side snapshot, mirroring the verified upload semantics. */
    static void collectWorkspaceEntries(Path root, String remoteRoot, List<WriteEntry> out) {
        if (root == null || !Files.isDirectory(root)) {
            log.info("Sandbox snapshot root {} is empty — nothing to upload", root);
            return;
        }
        collectEntries(root, root, remoteRoot, 0, out);
    }

    private static void collectEntries(Path root, Path dir, String remoteRoot, int depth, List<WriteEntry> out) {
        if (depth > OpenSandboxSdk.MAX_UPLOAD_DEPTH) {
            return;
        }
        try (var stream = Files.list(dir)) {
            for (Path entry : stream.sorted().toList()) {
                if (Files.isSymbolicLink(entry)) {
                    log.warn("Skipping link-shaped workspace entry in the sandbox snapshot: {}", entry);
                    continue;
                }
                if (Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS)) {
                    collectEntries(root, entry, remoteRoot, depth + 1, out);
                } else if (Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)) {
                    writeEntry(root, entry, remoteRoot, out);
                }
            }
        } catch (IOException e) {
            log.warn("Could not list snapshot directory {}: {}", dir, e.getMessage());
        }
    }

    private static void writeEntry(Path root, Path entry, String remoteRoot, List<WriteEntry> out) {
        try {
            if (Files.size(entry) > OpenSandboxSdk.MAX_UPLOAD_BYTES) {
                log.warn("Skipping oversized workspace file: {}", entry);
                return;
            }
            byte[] bytes = Files.readAllBytes(entry);
            if (containsBinary(bytes)) {
                log.warn("Skipping binary workspace file (the SDK entry upload is text-oriented): {}", entry);
                return;
            }
            String relative = root.relativize(entry).toString().replace('\\', '/');
            out.add(WriteEntry.builder()
                    .path(remoteRoot + "/" + relative)
                    .data(new String(bytes, StandardCharsets.UTF_8))
                    .mode(644)
                    .build());
        } catch (IOException e) {
            log.warn("Could not read workspace file {}, skipping: {}", entry, e.getMessage());
        }
    }

    private static boolean containsBinary(byte[] bytes) {
        int check = Math.min(bytes.length, 512);
        for (int i = 0; i < check; i++) {
            if (bytes[i] == 0) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ manifest

    /**
     * Trusted launch description of one run-owned core: explicit argv, explicit
     * environment and the sandbox working directory. Every value is validated
     * here, before upload, and again by the fixed image launcher before spawn.
     * {@link #toString()} is redacted so injected worker credentials cannot leak
     * through logging.
     */
    public record LaunchManifest(UUID runId, int port, String workingDirectory,
            List<String> argv, Map<String, String> env) {

        /** Injection- and process-hijack-shaped names refused in a manifest environment. */
        public static final Set<String> FORBIDDEN_ENVIRONMENT_KEYS = Set.of(
                "PATH", "HOME", "SHELL", "IFS", "ENV", "BASH_ENV", "BASH_FUNC",
                "NODE_OPTIONS", "LD_PRELOAD", "LD_LIBRARY_PATH",
                "DYLD_INSERT_LIBRARIES", "DYLD_LIBRARY_PATH",
                "PYTHONPATH", "PYTHONHOME", "GIT_CONFIG_GLOBAL", "GIT_CONFIG_SYSTEM");
        /** Shells are never a core entrypoint: argv[0] must name the core itself. */
        private static final Set<String> SHELLS = Set.of(
                "sh", "bash", "dash", "zsh", "ksh", "csh", "tcsh", "fish",
                "cmd", "cmd.exe", "powershell", "powershell.exe", "pwsh", "pwsh.exe");
        // Bounded, not tight: the Qoder bridge entry legitimately carries the node
        // launcher, the bridge script, the run/workspace/model selection, the pinned
        // CLI and its arguments, the credential and control-secret file paths and the
        // listen host/port, which is more than a bare core invocation.
        private static final int MAX_ARGV_ENTRIES = 64;
        private static final int MAX_ARGUMENT_LENGTH = 4096;
        private static final int MAX_ENVIRONMENT_ENTRIES = 64;
        private static final int MAX_ENVIRONMENT_VALUE_LENGTH = 8192;

        public LaunchManifest {
            Objects.requireNonNull(runId, "runId");
            if (port < 1 || port > 65535) {
                throw new IllegalArgumentException("Manifest port must be 1-65535, got " + port);
            }
            if (!isSandboxAbsolutePath(workingDirectory)) {
                throw new IllegalArgumentException(
                        "Manifest working directory must be an absolute sandbox path, got: " + workingDirectory);
            }
            rejectControlCharacters(workingDirectory, "workingDirectory");
            if (argv == null || argv.isEmpty() || argv.size() > MAX_ARGV_ENTRIES) {
                throw new IllegalArgumentException("Manifest argv must carry 1-" + MAX_ARGV_ENTRIES + " entries");
            }
            List<String> validatedArgv = new ArrayList<>(argv.size());
            for (int index = 0; index < argv.size(); index++) {
                String argument = argv.get(index);
                if (argument == null || argument.isEmpty() || argument.length() > MAX_ARGUMENT_LENGTH) {
                    throw new IllegalArgumentException("Manifest argv[" + index + "] is empty or oversized");
                }
                rejectControlCharacters(argument, "argv[" + index + "]");
                validatedArgv.add(argument);
            }
            String program = validatedArgv.get(0);
            String baseName = Path.of(program).getFileName() == null
                    ? program : Path.of(program).getFileName().toString();
            if (SHELLS.contains(baseName.toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException("Manifest argv[0] must name the core entrypoint, not a shell: "
                        + baseName);
            }
            Map<String, String> validatedEnv = new LinkedHashMap<>();
            if (env != null) {
                if (env.size() > MAX_ENVIRONMENT_ENTRIES) {
                    throw new IllegalArgumentException("Manifest environment carries more than "
                            + MAX_ENVIRONMENT_ENTRIES + " entries");
                }
                env.forEach((name, value) -> {
                    if (name == null || !name.matches("[A-Za-z_][A-Za-z0-9_]*")) {
                        throw new IllegalArgumentException(
                                "Manifest environment name is not a plain variable name: " + name);
                    }
                    if (FORBIDDEN_ENVIRONMENT_KEYS.contains(name.toUpperCase(Locale.ROOT))) {
                        throw new IllegalArgumentException(
                                "Manifest environment must not carry the process-injection variable " + name);
                    }
                    if (value == null || value.length() > MAX_ENVIRONMENT_VALUE_LENGTH) {
                        throw new IllegalArgumentException(
                                "Manifest environment value for " + name + " is empty or oversized");
                    }
                    rejectControlCharacters(value, "environment " + name);
                    validatedEnv.put(name, value);
                });
            }
            argv = List.copyOf(validatedArgv);
            env = Map.copyOf(validatedEnv);
        }

        private static void rejectControlCharacters(String value, String what) {
            for (int index = 0; index < value.length(); index++) {
                char character = value.charAt(index);
                if (character == '\0' || character == '\n' || character == '\r') {
                    throw new IllegalArgumentException(
                            "Manifest " + what + " must not contain NUL, CR or LF characters");
                }
            }
        }

        /** The exact JSON the fixed image launcher validates before spawning. */
        public String toJson() {
            Map<String, Object> document = new LinkedHashMap<>();
            document.put("schemaVersion", 1);
            document.put("runId", runId.toString());
            document.put("port", port);
            document.put("workingDirectory", workingDirectory);
            document.put("argv", argv);
            document.put("env", env);
            try {
                return MAPPER.writeValueAsString(document);
            } catch (IOException e) {
                throw new IllegalStateException("Unable to serialize the launch manifest", e);
            }
        }

        @Override
        public String toString() {
            return "LaunchManifest[redacted]";
        }
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ------------------------------------------------------------------ production SDK adapter

    /**
     * Production {@link SandboxSdk} over the pinned OpenSandbox Java SDK
     * ({@code com.alibaba.opensandbox:sandbox 1.0.18}). Semantics preserved from
     * the verified OpenCode provider path: no SDK health check (the server
     * reports scheme-less direct endpoints), transient start failures retried
     * with backoff, endpoints scheme-completed, long-running cores started on a
     * background thread, text-oriented bounded workspace upload.
     *
     * <p>It is also the single SDK access path for the legacy
     * {@code OpenCodeSandboxManager} facade, so no second copy of these rules can
     * drift.
     */
    @Slf4j
    public static final class OpenSandboxSdk implements SandboxSdk {

        /** Sandbox default TTL. */
        private static final Duration SANDBOX_TIMEOUT = Duration.ofMinutes(30);
        /** Timeout for the server-level health probe. */
        private static final Duration HEALTH_TIMEOUT = Duration.ofSeconds(3);
        /** Max recursion depth when uploading a workspace. */
        static final int MAX_UPLOAD_DEPTH = 3;
        /** Cap on a single uploaded file to keep requests sane. */
        static final long MAX_UPLOAD_BYTES = 4 * 1024 * 1024;
        /** Max attempts to create a sandbox before giving up on transient start errors. */
        private static final int MAX_SANDBOX_CREATE_ATTEMPTS = 3;
        /** Base backoff (ms) between sandbox creation retries; doubles each attempt (2s, then 4s). */
        private static final long SANDBOX_CREATE_BACKOFF_BASE_MS = 2000L;
        /**
         * Default total window (ms) one workspace upload may spend absorbing transient
         * execd connectivity failures. The Windows/WSL published-port relay can leave a
         * fresh sandbox's published execd port unreachable for tens of seconds (observed
         * 20s+ after a stack restart on 2026-10-01 and repeatedly on 2026-10-03), which
         * the former five-attempt (~18s) budget did not survive; runs interleaved before
         * and after failed this way. The failure after the window is unchanged: a loud
         * SANDBOX_UNAVAILABLE naming the last observed cause.
         */
        static final long DEFAULT_UPLOAD_WINDOW_MS = 90_000L;
        /** Base backoff (ms) between upload attempts; doubles until capped. */
        static final long UPLOAD_RETRY_BACKOFF_BASE_MS = 500L;
        /** Cap (ms) on one upload backoff so a long relay warm-up gets steady retries, not exponentially sparse ones. */
        static final long UPLOAD_RETRY_BACKOFF_CAP_MS = 5_000L;
        /** Export bounds: a runaway tree must fail the export, never exhaust the backend. */
        private static final int MAX_EXPORT_DEPTH = 12;
        private static final int MAX_EXPORT_FILES = 4096;
        private static final long MAX_EXPORT_TOTAL_BYTES = 64L * 1024 * 1024;
        private static final long MAX_EXPORT_FILE_BYTES = 16L * 1024 * 1024;

        private final ConnectionConfig connectionConfig;
        /** Raw server base URL (used for the service-level health probe). */
        private final String serverUrl;
        /** sandboxId → live sandbox instance. */
        private final Map<String, Sandbox> sandboxes = new ConcurrentHashMap<>();
        /** Total window (ms) one upload may spend absorbing transient execd failures. */
        private final long uploadWindowMs;
        /** Monotonic clock of the upload window; the test seam replaces it. */
        private final LongSupplier nanoClock;
        /** Sleeper of one upload backoff; the test seam replaces it. */
        private final LongConsumer uploadSleeper;

        public OpenSandboxSdk(String serverUrl, String apiKey) {
            this(serverUrl, apiKey, DEFAULT_UPLOAD_WINDOW_MS);
        }

        /** Production constructor with the operator-configured window ({@code opencode.sandbox-upload-window-ms}). */
        public OpenSandboxSdk(String serverUrl, String apiKey, long uploadWindowMs) {
            this(serverUrl, apiKey, uploadWindowMs, System::nanoTime, OpenSandboxSdk::sleepQuietly);
        }

        /** Test seam: a deterministic clock and sleeper make the upload window independent of wall time. */
        OpenSandboxSdk(String serverUrl, String apiKey, long uploadWindowMs,
                LongSupplier nanoClock, LongConsumer uploadSleeper) {
            this.serverUrl = serverUrl != null && !serverUrl.isBlank() ? serverUrl : "http://localhost:8080";
            this.connectionConfig = buildConnectionConfig(serverUrl, apiKey);
            this.uploadWindowMs = uploadWindowMs;
            this.nanoClock = Objects.requireNonNull(nanoClock, "nanoClock");
            this.uploadSleeper = Objects.requireNonNull(uploadSleeper, "uploadSleeper");
        }

        @Override
        public String create(String image, Map<String, String> env) {
            Sandbox sandbox = createSandboxWithRetry(image, env);
            String sandboxId = sandbox.getId();
            sandboxes.put(sandboxId, sandbox);
            log.info("OpenSandbox sandbox created from image {}: {}", image, sandboxId);
            return sandboxId;
        }

        @Override
        public void upload(String sandboxId, List<WriteEntry> entries) {
            if (entries == null || entries.isEmpty()) {
                return;
            }
            Sandbox sandbox = requireSandbox(sandboxId);
            long deadline = nanoClock.getAsLong() + Duration.ofMillis(uploadWindowMs).toNanos();
            long backoffMs = UPLOAD_RETRY_BACKOFF_BASE_MS;
            for (int attempt = 1; ; attempt++) {
                try {
                    sandbox.files().write(entries);
                    return;
                } catch (Exception e) {
                    boolean transientError = isTransientUploadError(e);
                    // The window absorbs a warming-up published-port relay; a permanent
                    // failure or one past the window fails immediately, as loudly as before.
                    if (!transientError || nanoClock.getAsLong() >= deadline) {
                        throw new TaskExecutionException(TaskExecutionException.Cause.SANDBOX_UNAVAILABLE,
                                "Workspace upload failed for sandbox " + sandboxId + ": " + e.getMessage(), e);
                    }
                    log.warn("Workspace upload attempt {} for sandbox {} failed with transient execd error '{}'; "
                                    + "retrying in {}ms within the {}ms upload window",
                            attempt, sandboxId, e.getMessage(), backoffMs, uploadWindowMs);
                    uploadSleeper.accept(backoffMs);
                    backoffMs = Math.min(UPLOAD_RETRY_BACKOFF_CAP_MS, backoffMs * 2);
                }
            }
        }

        @Override
        public String endpoint(String sandboxId, int port) {
            Sandbox sandbox = requireSandbox(sandboxId);
            try {
                SandboxEndpoint endpoint = sandbox.getEndpoint(port);
                return ipv4Loopback(endpoint.getEndpoint());
            } catch (Exception e) {
                throw new TaskExecutionException(TaskExecutionException.Cause.SANDBOX_UNAVAILABLE,
                        "Could not resolve endpoint for sandbox " + sandboxId + " port " + port + ": "
                                + e.getMessage(), e);
            }
        }

        @Override
        public void launch(String sandboxId, String runDirectory, Duration timeout) {
            Sandbox sandbox = requireSandbox(sandboxId);
            String command = "node " + LAUNCHER
                    + " --manifest " + runDirectory + "/" + MANIFEST_FILE;
            log.info("Launching the run-owned core in sandbox {} via the fixed image launcher", sandboxId);
            // The launcher supervises a long-running child, so it runs on a background
            // virtual thread (verified SDK behaviour: commands().run blocks until exit).
            Thread.ofVirtual().start(() -> {
                try {
                    sandbox.commands().run(RunCommandRequest.builder()
                            .command(command)
                            .timeout(timeout)
                            .build());
                } catch (Exception e) {
                    log.warn("The image launcher exited in sandbox {}: {}", sandboxId, e.getMessage());
                }
            });
        }

        @Override
        public String pauseWriters(String sandboxId, String runDirectory, Duration timeout) {
            return control(sandboxId, runDirectory, "suspend", timeout);
        }

        @Override
        public String resumeWriters(String sandboxId, String runDirectory, Duration timeout) {
            return control(sandboxId, runDirectory, "resume", timeout);
        }

        @Override
        public String stopWriters(String sandboxId, String runDirectory, Duration timeout) {
            return control(sandboxId, runDirectory, "stop", timeout);
        }

        @Override
        public void renew(String sandboxId, Duration extension) {
            Sandbox sandbox = requireSandbox(sandboxId);
            try {
                var resp = sandbox.renew(extension);
                log.info("Sandbox {} renewed until {}", sandboxId, resp.getExpiresAt());
            } catch (Exception e) {
                log.warn("Failed to renew sandbox {}: {}", sandboxId, e.getMessage());
                throw new TaskExecutionException(TaskExecutionException.Cause.SANDBOX_UNAVAILABLE,
                        "Sandbox renewal failed for " + sandboxId + ": " + e.getMessage(), e);
            }
        }

        @Override
        public List<RemoteFile> export(String sandboxId, String remoteRoot) {
            Sandbox sandbox = requireSandbox(sandboxId);
            List<RemoteFile> files = new ArrayList<>();
            long[] total = {0L};
            collectExport(sandbox, remoteRoot, remoteRoot, 0, files, total);
            return files;
        }

        @Override
        public void kill(String sandboxId) {
            if (sandboxId == null) {
                return;
            }
            Sandbox sandbox = sandboxes.remove(sandboxId);
            if (sandbox == null) {
                return;
            }
            try {
                sandbox.kill();
                log.info("Sandbox {} killed", sandboxId);
            } catch (Exception e) {
                log.warn("Failed to kill sandbox {}: {}", sandboxId, e.getMessage());
            }
        }

        /** Whether this SDK client currently tracks the sandbox id. */
        public boolean tracks(String sandboxId) {
            return sandboxId != null && sandboxes.containsKey(sandboxId);
        }

        // -------------------------------------------------------------- legacy facade helpers

        /**
         * Upload the agent workspace into the sandbox workspace root, entry by
         * entry (verified SDK semantics: no bulk directory upload API). Shared
         * with the migration path of the legacy per-agent facade.
         */
        public void uploadWorkspace(String sandboxId, Path workspaceDir) {
            if (workspaceDir == null || !Files.isDirectory(workspaceDir)) {
                log.info("Workspace {} is empty — nothing to upload for sandbox {}", workspaceDir, sandboxId);
                return;
            }
            List<WriteEntry> entries = new ArrayList<>();
            SandboxLifecycle.collectWorkspaceEntries(workspaceDir, SandboxLifecycle.DEFAULT_WORKSPACE_ROOT, entries);
            if (entries.isEmpty()) {
                log.info("No files to upload for workspace {} into sandbox {}", workspaceDir, sandboxId);
                return;
            }
            upload(sandboxId, entries);
            log.info("Uploaded {} file(s) from workspace {} into sandbox {}", entries.size(), workspaceDir, sandboxId);
        }

        /** Run a long-lived core command on a background virtual thread (legacy facade path). */
        public void runBackground(String sandboxId, String command, Map<String, String> env) {
            Sandbox sandbox = requireSandbox(sandboxId);
            log.info("Starting background command in sandbox {}: {}", sandboxId, command);
            Thread.ofVirtual().start(() -> {
                try {
                    if (env == null || env.isEmpty()) {
                        sandbox.commands().run(command);
                    } else {
                        sandbox.commands().run(RunCommandRequest.builder()
                                .command(command)
                                .envs(env)
                                .build());
                    }
                } catch (Exception e) {
                    log.warn("Background command exited in sandbox {}: {}", sandboxId, e.getMessage());
                }
            });
        }

        /** Run a shell command inside the sandbox and return its combined stdout/result text. */
        public String runCommand(String sandboxId, String command) {
            Sandbox sandbox = requireSandbox(sandboxId);
            try {
                return renderExecution(sandbox.commands().run(command));
            } catch (Exception e) {
                throw new TaskExecutionException(TaskExecutionException.Cause.SANDBOX_UNAVAILABLE,
                        "Command execution failed in sandbox " + sandboxId + ": " + e.getMessage(), e);
            }
        }

        /**
         * Aggregate a diagnostic snapshot of a sandbox: metrics, the process table
         * and the recent core log tail. Never throws — each section is collected
         * independently and failures are recorded as ERROR markers.
         */
        public String diagnose(String sandboxId) {
            Sandbox sandbox = requireSandbox(sandboxId);
            StringBuilder sb = new StringBuilder();
            try {
                var metrics = sandbox.getMetrics();
                String metricsText;
                try {
                    metricsText = MAPPER.writeValueAsString(metrics);
                } catch (Exception jsonEx) {
                    metricsText = String.valueOf(metrics);
                }
                sb.append("== metrics ==\n").append(metricsText).append('\n');
            } catch (Exception e) {
                sb.append("== metrics == ERROR ").append(e.getMessage()).append('\n');
            }
            try {
                var exec = sandbox.commands().run("ps aux 2>/dev/null | head -30 || ps -ef | head -30");
                String rendered = renderExecution(exec);
                if (rendered == null || rendered.isBlank()) {
                    throw new IllegalStateException("ps returned no output");
                }
                sb.append("== processes ==\n").append(rendered).append('\n');
            } catch (Exception e) {
                try {
                    var exec = sandbox.commands().run("ls /proc | grep -E '^[0-9]+$' | head -30");
                    sb.append("== processes (proc fallback) ==\n").append(renderExecution(exec)).append('\n');
                } catch (Exception fallbackEx) {
                    sb.append("== processes == ERROR ").append(e.getMessage()).append('\n');
                }
            }
            try {
                var exec = sandbox.commands().run(
                        "tail -50 $(ls -t ~/.opencode/log/*.log 2>/dev/null | head -1) 2>/dev/null"
                        + " || tail -50 $(ls -t ~/.local/share/opencode/log/*.log 2>/dev/null | head -1) 2>/dev/null"
                        + " || echo 'no opencode log found'");
                sb.append("== opencode log tail ==\n").append(renderExecution(exec)).append('\n');
            } catch (Exception e) {
                sb.append("== opencode log tail == ERROR ").append(e.getMessage()).append('\n');
            }
            return sb.toString();
        }

        /** Service-level health probe for the OpenSandbox server itself; never throws. */
        public boolean isServerHealthy() {
            try (HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(HEALTH_TIMEOUT)
                    .version(HttpClient.Version.HTTP_1_1)
                    .build()) {
                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(serverUrl + "/health"))
                        .timeout(HEALTH_TIMEOUT)
                        .GET()
                        .build();
                HttpResponse<Void> resp = client.send(req, HttpResponse.BodyHandlers.discarding());
                return resp.statusCode() / 100 == 2;
            } catch (Exception e) {
                log.debug("OpenSandbox server health probe failed: {}", e.getMessage());
                return false;
            }
        }

        // -------------------------------------------------------------- internals

        private String control(String sandboxId, String runDirectory, String action, Duration timeout) {
            Sandbox sandbox = requireSandbox(sandboxId);
            String command = "node " + STOP_WRITERS
                    + " --run-directory " + runDirectory
                    + " --action " + action;
            try {
                Execution exec = sandbox.commands().run(RunCommandRequest.builder()
                        .command(command)
                        .timeout(timeout)
                        .build());
                String rendered = renderExecution(exec);
                log.info("Writer control '{}' in sandbox {} exited with {}: {}",
                        action, sandboxId, exec.getExitCode(), oneLine(rendered));
                return rendered;
            } catch (Exception e) {
                log.warn("Writer control '{}' failed in sandbox {}: {}", action, sandboxId, e.getMessage());
                return "";
            }
        }

        private void collectExport(Sandbox sandbox, String root, String directory, int depth,
                List<RemoteFile> out, long[] total) {
            if (depth > MAX_EXPORT_DEPTH) {
                throw new IllegalStateException("The exported tree is deeper than " + MAX_EXPORT_DEPTH + " levels");
            }
            List<EntryInfo> entries;
            try {
                entries = sandbox.files().listDirectory(directory);
            } catch (Exception e) {
                throw new TaskExecutionException(TaskExecutionException.Cause.SANDBOX_UNAVAILABLE,
                        "Could not list " + directory + ": " + e.getMessage(), e);
            }
            if (entries == null) {
                return;
            }
            for (EntryInfo entry : entries) {
                String type = entry.getType() == null ? "" : entry.getType().toLowerCase(Locale.ROOT);
                if (type.contains("dir")) {
                    collectExport(sandbox, root, entry.getPath(), depth + 1, out, total);
                    continue;
                }
                if (!type.contains("file")) {
                    // A link or a special entry is never followed into retained results.
                    log.warn("Skipping non-file sandbox entry during export: {}", entry.getPath());
                    continue;
                }
                if (entry.getSize() > MAX_EXPORT_FILE_BYTES) {
                    throw new IllegalStateException("Exported file is oversized: " + entry.getPath());
                }
                if (out.size() >= MAX_EXPORT_FILES) {
                    throw new IllegalStateException("The exported tree carries more than " + MAX_EXPORT_FILES + " files");
                }
                byte[] data;
                try {
                    data = sandbox.files().readByteArray(entry.getPath());
                } catch (Exception e) {
                    throw new TaskExecutionException(TaskExecutionException.Cause.SANDBOX_UNAVAILABLE,
                            "Could not read " + entry.getPath() + ": " + e.getMessage(), e);
                }
                total[0] += data.length;
                if (total[0] > MAX_EXPORT_TOTAL_BYTES) {
                    throw new IllegalStateException("The exported tree exceeds " + MAX_EXPORT_TOTAL_BYTES + " bytes");
                }
                String relative = entry.getPath().startsWith(root + "/")
                        ? entry.getPath().substring(root.length() + 1)
                        : entry.getPath();
                out.add(new RemoteFile(relative, data));
            }
        }

        /** Best-effort text rendering of a command {@link Execution} (stdout + result text). */
        private String renderExecution(Execution exec) {
            if (exec == null) {
                return "";
            }
            StringBuilder sb = new StringBuilder();
            ExecutionLogs logs = exec.getLogs();
            if (logs != null && logs.getStdout() != null) {
                for (OutputMessage msg : logs.getStdout()) {
                    sb.append(msg.getText());
                }
            }
            if (exec.getResult() != null) {
                for (ExecutionResult result : exec.getResult()) {
                    sb.append(result.getText());
                }
            }
            return sb.toString();
        }

        private Sandbox requireSandbox(String sandboxId) {
            Sandbox sandbox = sandboxId == null ? null : sandboxes.get(sandboxId);
            if (sandbox == null) {
                throw new TaskExecutionException(TaskExecutionException.Cause.SANDBOX_UNAVAILABLE,
                        "Sandbox " + sandboxId + " is not tracked by this manager");
            }
            return sandbox;
        }

        private Sandbox createSandboxWithRetry(String image, Map<String, String> env) {
            Exception lastFailure = null;
            for (int attempt = 1; attempt <= MAX_SANDBOX_CREATE_ATTEMPTS; attempt++) {
                try {
                    return buildSandbox(image, env);
                } catch (Exception e) {
                    lastFailure = e;
                    boolean transientError = isTransientStartError(e);
                    boolean lastAttempt = attempt == MAX_SANDBOX_CREATE_ATTEMPTS;
                    if (!transientError || lastAttempt) {
                        log.error("Failed to create OpenSandbox sandbox for image {}: {}", image, e.getMessage());
                        throw new TaskExecutionException(TaskExecutionException.Cause.SANDBOX_UNAVAILABLE,
                                "OpenSandbox sandbox creation failed for image " + image + ": " + e.getMessage(), e);
                    }
                    long backoffMs = SANDBOX_CREATE_BACKOFF_BASE_MS << (attempt - 1);
                    log.warn("Sandbox creation attempt {}/{} failed for image {} with transient error '{}'; retrying in {}ms",
                            attempt, MAX_SANDBOX_CREATE_ATTEMPTS, image, e.getMessage(), backoffMs);
                    sleepQuietly(backoffMs);
                }
            }
            throw new TaskExecutionException(TaskExecutionException.Cause.SANDBOX_UNAVAILABLE,
                    "OpenSandbox sandbox creation failed for image " + image + ": " + lastFailure.getMessage(),
                    lastFailure);
        }

        private Sandbox buildSandbox(String image, Map<String, String> env) {
            Sandbox.Builder builder = Sandbox.builder()
                    .connectionConfig(connectionConfig)
                    .image(image)
                    .timeout(SANDBOX_TIMEOUT)
                    // The server reports execd endpoints without a scheme and with the
                    // configured host (bridge mode: 127.0.0.1:{mapped}/proxy/{port}). The
                    // SDK's built-in health check would probe the scheme-less endpoint and
                    // fail, so readiness is verified by the caller against the completed URL.
                    .skipHealthCheck(true);
            if (env != null && !env.isEmpty()) {
                builder.env(env);
            }
            return builder.build();
        }

        /**
         * True when the failure looks like a transient sandbox start / port-bind
         * error ({@code DOCKER::SANDBOX_START_FAILED} or an excluded port range),
         * or an OpenSandbox server-side fault that clears on a fresh placement:
         * a 5xx carrying a {@code DOCKER::*} code (observed live:
         * {@code DOCKER::SANDBOX_EXECD_DISTRIBUTION_FAILED} — the server's own
         * execd distribution lost its docker subprocess on a broken pipe while
         * creating {@code /opt/opensandbox} in the new sandbox) and that same
         * broken-pipe / lost-subprocess wording when it arrives without the code
         * prefix. A 4xx — the server refusing the request itself, surfaced as
         * {@code Client error : <status> ...} — is permanent (a bad image, an
         * invalid request) and keeps failing fast. Package-private as the
         * deterministic seam for the classification tests.
         */
        static boolean isTransientStartError(Exception e) {
            if (e == null || e.getMessage() == null) {
                return false;
            }
            String message = e.getMessage().toLowerCase(Locale.ROOT);
            // The 4xx wrapper wins over every keyword below: a request the server
            // refused never turns retryable, whatever wording its body carries.
            if (message.contains("client error :")) {
                return false;
            }
            return message.contains("sandbox_start_failed")
                    || message.contains("excluded port")
                    || message.contains("port")
                    // OpenSandbox answers a server-side placement fault with a 5xx
                    // ("Server error : 500 ...") whose body carries the DOCKER::*
                    // code; the next attempt gets a fresh server-side placement.
                    || (message.contains("docker::") && message.contains("server error"))
                    || message.contains("sandbox_execd_distribution_failed")
                    // The same server-side fault's wording, without the code prefix.
                    || message.contains("broken pipe")
                    || message.contains("passing bulk input to subprocess");
        }

        /**
         * True when the upload failure is connection-level and worth retrying:
         * the sandbox's execd is still warming up (create returns with
         * {@code skipHealthCheck=true}, so the execd listener may not exist yet
         * and the direct endpoint answers EOF / connection reset). Permanent
         * failures (size caps, invalid paths, ...) never match and fail
         * immediately.
         */
        private static boolean isTransientUploadError(Exception e) {
            if (e == null || e.getMessage() == null) {
                return false;
            }
            String message = e.getMessage().toLowerCase(Locale.ROOT);
            // EOF markers are matched in their observed forms, never as a bare
            // substring, so a permanent diagnostic that happens to contain "eof"
            // is not retried.
            return message.contains("unexpected end of stream")
                    || message.contains("unexpected eof")
                    || message.contains("eofexception")
                    || message.contains("connection refused")
                    || message.contains("connection reset")
                    || message.contains("failed to connect");
        }

        private static void sleepQuietly(long millis) {
            try {
                Thread.sleep(millis);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        }

        private static ConnectionConfig buildConnectionConfig(String serverUrl, String apiKey) {
            ConnectionConfig.Builder builder = ConnectionConfig.builder()
                    .protocol(protocolOf(serverUrl))
                    .domain(loopbackDomainOf(serverUrl));
            // The SDK rejects blank keys ("API key cannot be blank"); a null key falls back
            // to the OPEN_SANDBOX_API_KEY env var, which matches the "optional key" contract.
            if (apiKey != null && !apiKey.isBlank()) {
                builder.apiKey(apiKey);
            }
            // Direct execd endpoints ({@code <host_ip>:{mapped}/proxy/<port>}) are the only
            // reliable path when sandboxes run on the default bridge; keep useServerProxy off.
            return builder.build();
        }

        /**
         * The configured scheme is preserved (https on TLS-fronted server deployments).
         */
        static String protocolOf(String serverUrl) {
            String scheme = sandboxServerUri(serverUrl).getScheme();
            return scheme != null ? scheme : "http";
        }

        /**
         * The connection domain of the sandbox server URL, with any loopback host
         * pinned to the IPv4 literal. "localhost" resolves to the IPv6 loopback
         * ([::1]) on this host and the JDK client connects to the first resolved
         * address, so a localhost-based direct execd URL intermittently fails with
         * {@code Failed to connect to localhost/[0:0:0:0:0:0:0:1]:<port>} (observed
         * on real sandbox uploads regardless of the configured yml value). The
         * execd endpoints listen on IPv4, so the loopback is always dialed as
         * 127.0.0.1.
         */
        static String loopbackDomainOf(String serverUrl) {
            URI uri = sandboxServerUri(serverUrl);
            String authority = uri.getAuthority() != null ? uri.getAuthority() : uri.getHost();
            return ipv4Loopback(authority);
        }

        private static URI sandboxServerUri(String serverUrl) {
            return URI.create(serverUrl != null && !serverUrl.isBlank() ? serverUrl : "http://localhost:8080");
        }

        /**
         * Rewrites a leading loopback name of a host[:port][/path] address to the
         * IPv4 literal; every other address is returned unchanged. The loopback
         * name must be the whole host, so name-sharing hosts such as
         * "localhost.localdomain" are left alone.
         */
        static String ipv4Loopback(String address) {
            if (address == null) {
                return null;
            }
            String scheme = "";
            String rest = address;
            int schemeIndex = address.indexOf("://");
            if (schemeIndex > 0) {
                scheme = address.substring(0, schemeIndex + 3);
                rest = address.substring(schemeIndex + 3);
            }
            if (matchesWholeHost(rest, "localhost")) {
                return scheme + "127.0.0.1" + rest.substring("localhost".length());
            }
            if (matchesWholeHost(rest, "[::1]")) {
                return scheme + "127.0.0.1" + rest.substring("[::1]".length());
            }
            if (matchesWholeHost(rest, "[0:0:0:0:0:0:0:1]")) {
                return scheme + "127.0.0.1" + rest.substring("[0:0:0:0:0:0:0:1]".length());
            }
            if (matchesWholeHost(rest, "::1")) {
                return scheme + "127.0.0.1" + rest.substring("::1".length());
            }
            if (matchesWholeHost(rest, "0:0:0:0:0:0:0:1")) {
                return scheme + "127.0.0.1" + rest.substring("0:0:0:0:0:0:0:1".length());
            }
            return address;
        }

        /**
         * True when the address starts with the host literal followed by ':',
         * '/' or the end of the address.
         */
        private static boolean matchesWholeHost(String address, String hostLiteral) {
            int length = hostLiteral.length();
            if (!address.regionMatches(true, 0, hostLiteral, 0, length)) {
                return false;
            }
            return address.length() == length
                    || address.charAt(length) == ':'
                    || address.charAt(length) == '/';
        }
    }
}

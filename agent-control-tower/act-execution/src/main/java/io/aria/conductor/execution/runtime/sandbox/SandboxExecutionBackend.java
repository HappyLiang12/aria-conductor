package io.aria.conductor.execution.runtime.sandbox;

import io.aria.conductor.common.runtime.ExecutionMode;
import io.aria.conductor.common.runtime.WorkspaceKind;
import io.aria.conductor.execution.adk.TaskExecutionException;
import io.aria.conductor.execution.runtime.ArtifactBundle;
import io.aria.conductor.execution.runtime.ControlAck;
import io.aria.conductor.execution.runtime.ExecutionBackend;
import io.aria.conductor.execution.runtime.ExecutionSpec;
import io.aria.conductor.execution.runtime.LaunchProfile;
import io.aria.conductor.execution.runtime.PreparedEnvironment;
import io.aria.conductor.execution.runtime.RuntimeHandle;
import io.aria.conductor.execution.runtime.StopProof;
import io.aria.conductor.execution.runtime.WorkspaceLease;
import io.aria.conductor.execution.runtime.WorkspacePaths;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Sandbox placement backend (spec 4.1, 5.1): one run-owned image environment per
 * run, keyed by the run UUID. Preparation allocates the sandbox and resolves the
 * authenticated core endpoint; launch uploads the run's workspace snapshot and
 * its trusted {@link SandboxLifecycle.LaunchManifest} in one write and starts the
 * fixed image launcher; writer termination, export and destruction are three
 * separate steps in that order.
 *
 * <p>{@code stopWriters} produces the {@link StopProof} and records the verified
 * stop that export requires (a later launch or resume invalidates it); it
 * deliberately leaves the sandbox filesystem and export facility alive.
 * {@link #destroy} is the only operation that kills the sandbox, and an export
 * that cannot complete is reported as an incomplete artifact result (a refusal),
 * never as a stable final diff.
 */
@Slf4j
public class SandboxExecutionBackend implements ExecutionBackend {

    /** Host-side staging directory of the run-owned sandbox configuration, inside the workspace runtime root. */
    private static final String STAGING_DIRECTORY = "sandbox";

    /** One recreate attempt per launch: a second relay-dead sandbox fails the run. */
    static final int RELAY_RECREATE_BUDGET = 1;

    /** Raised image/port pair of one core, resolved from operator configuration, never from a worker. */
    public record SandboxProfile(String image, int port) {
        public SandboxProfile {
            if (image == null || image.isBlank()) {
                throw new IllegalArgumentException("A sandbox image is required");
            }
            if (port < 1 || port > 65535) {
                throw new IllegalArgumentException("The core port must be 1-65535, got " + port);
            }
        }
    }

    /** Operator configuration: which image and core port a core runs in, or {@code null} when unconfigured. */
    @FunctionalInterface
    public interface SandboxImages {
        SandboxProfile forCore(String coreId);
    }

    private final SandboxLifecycle lifecycle;
    private final SandboxImages images;
    private final Map<UUID, Prepared> environments = new ConcurrentHashMap<>();

    /** One prepared run: the environment record plus the host-side snapshot and staging roots. */
    private record Prepared(PreparedEnvironment environment, SandboxProfile profile,
            Path stagingDirectory, Path snapshotRoot) {
    }

    public SandboxExecutionBackend(SandboxLifecycle lifecycle, SandboxImages images) {
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
        this.images = Objects.requireNonNull(images, "images");
    }

    /**
     * Production wiring: the pinned OpenSandbox SDK with the given server URL, API key
     * and workspace-upload window ({@code opencode.sandbox-upload-window-ms}).
     */
    public static SandboxExecutionBackend usingOpenSandbox(String serverUrl, String apiKey, long uploadWindowMs,
            SandboxImages images) {
        return new SandboxExecutionBackend(
                new SandboxLifecycle(new SandboxLifecycle.OpenSandboxSdk(serverUrl, apiKey, uploadWindowMs)), images);
    }

    @Override
    public ExecutionMode mode() {
        return ExecutionMode.SANDBOX;
    }

    @Override
    public PreparedEnvironment prepare(ExecutionSpec spec, WorkspaceLease workspace) {
        Objects.requireNonNull(spec, "spec");
        Objects.requireNonNull(workspace, "workspace");
        if (!spec.runId().equals(workspace.runId())) {
            throw new IllegalArgumentException("Execution spec run " + spec.runId()
                    + " does not match workspace lease run " + workspace.runId());
        }
        // The local root of a sandbox lease is uploaded wholesale, so only the
        // sandbox snapshot kind is admitted; any other lease is refused outright.
        if (workspace.kind() != WorkspaceKind.SANDBOX_SNAPSHOT) {
            throw new IllegalArgumentException("The sandbox backend admits only " + WorkspaceKind.SANDBOX_SNAPSHOT
                    + " workspace leases, got " + workspace.kind());
        }
        if (spec.mode() != ExecutionMode.SANDBOX) {
            throw new IllegalStateException("Host execution is not this backend's placement (spec mode "
                    + spec.mode() + ")");
        }
        SandboxProfile profile = images.forCore(spec.coreId());
        if (profile == null) {
            throw new IllegalStateException("No sandbox image is configured for core " + spec.coreId());
        }
        if (lifecycle.owns(spec.runId())) {
            throw new IllegalStateException("This backend already owns run " + spec.runId()
                    + "; a sandbox run key is never reused");
        }
        Path staging = Path.of(workspace.runtimeRoot()).toAbsolutePath().normalize().resolve(STAGING_DIRECTORY);
        try {
            Files.createDirectories(staging);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to prepare the run-owned sandbox staging directory " + staging
                    + ": " + e.getMessage(), e);
        }
        String sandboxId = lifecycle.create(spec.runId(), profile.image(), Map.of());
        URI endpoint;
        try {
            endpoint = lifecycle.resolveEndpoint(spec.runId(), profile.port());
        } catch (RuntimeException e) {
            // A prepared environment without a verified endpoint is not usable: the
            // just-created sandbox is destroyed instead of being leaked half-prepared.
            lifecycle.destroy(spec.runId());
            throw e;
        }
        PreparedEnvironment environment = new PreparedEnvironment(spec.runId(), ExecutionMode.SANDBOX,
                sandboxId, lifecycle.workspaceRoot(), staging.toString(), endpoint);
        environments.put(spec.runId(), new Prepared(environment, profile, staging, workspace.localRoot()));
        return environment;
    }

    @Override
    public RuntimeHandle launch(PreparedEnvironment environment, LaunchProfile profile) {
        Objects.requireNonNull(environment, "environment");
        Objects.requireNonNull(profile, "profile");
        Prepared prepared = environments.get(environment.runId());
        if (prepared == null || prepared.environment() != environment) {
            throw new IllegalArgumentException("Environment was not prepared by this backend for run "
                    + environment.runId());
        }
        PreparedEnvironment launched = environment;
        try {
            SandboxLifecycle.LaunchManifest manifest = new SandboxLifecycle.LaunchManifest(
                    environment.runId(), prepared.profile().port(), workingDirectory(profile),
                    profile.argv(), profile.env());
            int recreateBudget = RELAY_RECREATE_BUDGET;
            while (true) {
                try {
                    uploadAll(prepared, launched, manifest);
                    lifecycle.launch(launched.runId());
                    break;
                } catch (TaskExecutionException e) {
                    // The relay-never-established upload failure class is not healed by
                    // waiting: the published relay port never accepted a connection in
                    // the whole upload window, so only a fresh sandbox can carry this
                    // run. One recreate is admitted; a second relay-dead sandbox fails
                    // the run (through the outer failure path below).
                    if (recreateBudget <= 0 || !SandboxLifecycle.isRelayNeverEstablished(e)) {
                        throw e;
                    }
                    recreateBudget--;
                    log.warn("Relay never established for sandbox {} of run {}; recreating a fresh sandbox "
                            + "({} attempt(s) left)", launched.environmentId(), launched.runId(), recreateBudget + 1);
                    lifecycle.destroy(launched.runId());
                    launched = recreateSandbox(prepared, launched);
                    log.info("Recreated sandbox {} for run {}", launched.environmentId(), launched.runId());
                }
            }
        } catch (RuntimeException e) {
            // The sandbox was created during prepare: a failure before the core is
            // running must not leak it (a refused manifest or a failed upload would
            // otherwise leave an orphaned container behind).
            environments.remove(environment.runId());
            lifecycle.destroy(environment.runId());
            throw e;
        }
        return new RuntimeHandle(launched.runId(), ExecutionMode.SANDBOX, launched.environmentId(),
                ownershipIdentity(launched, prepared), launched.endpoint());
    }

    /**
     * The upload sequence a sandbox needs before the launch: the workspace
     * snapshot plus its trusted manifest in one write, then the run-owned
     * governed configuration. This is the single place both uploads live, so
     * the recreate path re-runs both against the fresh sandbox by construction.
     */
    private void uploadAll(Prepared prepared, PreparedEnvironment environment,
            SandboxLifecycle.LaunchManifest manifest) {
        // The manifest can carry the run's core environment, so no host-side copy is
        // written: the only copy is the run-owned one uploaded into the sandbox
        // control directory (mode 600), which dies with the sandbox.
        lifecycle.uploadSnapshot(environment.runId(), prepared.snapshotRoot(), manifest);
        // The run-owned governed configuration (written into the host staging
        // directory by the core adapter) is uploaded into the sandbox run control
        // directory, where the launch manifest's XDG roots point.
        lifecycle.uploadRunConfiguration(environment.runId(), prepared.stagingDirectory(), "config");
    }

    /**
     * The bounded recreate of a relay-dead sandbox: the dead sandbox is already
     * destroyed by the caller (releasing the run key), and one fresh sandbox is
     * created from the same configured image with its endpoint re-resolved
     * exactly as {@link #prepare} does, so the run can be re-uploaded and
     * launched against it. The run-keyed map entry is refreshed so the entry
     * always names the live environment.
     */
    private PreparedEnvironment recreateSandbox(Prepared prepared, PreparedEnvironment stale) {
        UUID runId = stale.runId();
        String sandboxId = lifecycle.create(runId, prepared.profile().image(), Map.of());
        URI endpoint;
        try {
            endpoint = lifecycle.resolveEndpoint(runId, prepared.profile().port());
        } catch (RuntimeException e) {
            // Same invariant as prepare: an environment without a verified endpoint is
            // not usable, so the just-created sandbox is destroyed instead of leaked.
            lifecycle.destroy(runId);
            throw e;
        }
        PreparedEnvironment recreated = new PreparedEnvironment(runId, ExecutionMode.SANDBOX, sandboxId,
                lifecycle.workspaceRoot(), stale.configurationDirectory(), endpoint);
        environments.put(runId, new Prepared(recreated, prepared.profile(),
                prepared.stagingDirectory(), prepared.snapshotRoot()));
        return recreated;
    }

    @Override
    public CompletionStage<ControlAck> pauseWriters(RuntimeHandle handle, Instant deadline) {
        requireOwned(handle);
        return CompletableFuture.completedFuture(lifecycle.pauseWriters(handle.runId(), deadline));
    }

    @Override
    public CompletionStage<ControlAck> resumeWriters(RuntimeHandle handle, Instant deadline) {
        requireOwned(handle);
        return CompletableFuture.completedFuture(lifecycle.resumeWriters(handle.runId(), deadline));
    }

    @Override
    public StopProof stopWriters(RuntimeHandle handle, Instant deadline) {
        requireOwned(handle);
        return lifecycle.stopWriters(handle.runId(), deadline);
    }

    /**
     * Exports the stopped run's sandbox workspace into managed result storage.
     * The sandbox is intentionally still alive here: destroying it first would
     * destroy the export source. An export that cannot complete throws, so the
     * caller reports an incomplete artifact result instead of a stable diff.
     */
    @Override
    public void exportWorkspace(RuntimeHandle handle, Path destination, StopProof proof) {
        requireOwned(handle);
        ArtifactBundle bundle = lifecycle.export(handle.runId(), destination, proof);
        if (!bundle.complete()) {
            throw new IllegalStateException("Export of run " + handle.runId()
                    + " is incomplete; no stable diff is reported (artifact directory " + bundle.directory() + ")");
        }
    }

    /**
     * Destroys the run's sandbox and its run-owned generated configuration. This
     * is the only operation that kills the environment, and it is never part of
     * writer termination: export runs between them.
     */
    @Override
    public void destroy(RuntimeHandle handle) {
        Objects.requireNonNull(handle, "handle");
        lifecycle.destroy(handle.runId());
        Prepared prepared = environments.remove(handle.runId());
        if (prepared == null) {
            return;
        }
        Path staging = prepared.stagingDirectory();
        Path stagingParent = staging.getParent();
        try {
            if (stagingParent != null) {
                // Link-safe deletion from the run-owned root: a link above the deletion
                // root refuses the deletion instead of redirecting it.
                WorkspacePaths.deleteTree(stagingParent, staging);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Unable to remove the run-owned sandbox staging directory " + staging
                    + ": " + e.getMessage(), e);
        }
    }

    /** The live sandbox id of a launched run (memory-only; empty on another backend). */
    public java.util.Optional<String> sandboxId(RuntimeHandle handle) {
        Objects.requireNonNull(handle, "handle");
        return lifecycle.sandboxId(handle.runId());
    }

    private void requireOwned(RuntimeHandle handle) {
        Objects.requireNonNull(handle, "handle");
        if (handle.mode() != ExecutionMode.SANDBOX) {
            throw new IllegalArgumentException("Not a Sandbox handle: " + handle.mode());
        }
        if (!lifecycle.owns(handle.runId())) {
            throw new IllegalStateException("Run " + handle.runId() + " is not owned by this sandbox backend");
        }
    }

    /** The sandbox working directory: the profile's when it names one, the sandbox workspace root otherwise. */
    private String workingDirectory(LaunchProfile profile) {
        String requested = profile.workingDirectory();
        return requested == null || requested.isBlank() ? lifecycle.workspaceRoot() : requested;
    }

    private static String ownershipIdentity(PreparedEnvironment environment, Prepared prepared) {
        return "sandbox:" + environment.runId() + ":" + environment.environmentId();
    }
}

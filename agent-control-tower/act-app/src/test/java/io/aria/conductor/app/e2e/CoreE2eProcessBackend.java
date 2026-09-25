package io.aria.conductor.app.e2e;

import io.aria.conductor.common.runtime.ExecutionMode;
import io.aria.conductor.execution.runtime.ControlAck;
import io.aria.conductor.execution.runtime.ExecutionBackend;
import io.aria.conductor.execution.runtime.ExecutionSpec;
import io.aria.conductor.execution.runtime.LaunchProfile;
import io.aria.conductor.execution.runtime.PreparedEnvironment;
import io.aria.conductor.execution.runtime.RuntimeHandle;
import io.aria.conductor.execution.runtime.StopProof;
import io.aria.conductor.execution.runtime.WorkspaceLease;
import io.aria.conductor.execution.runtime.WorkspacePaths;
import io.aria.conductor.execution.runtime.host.OwnedProcess;
import io.aria.conductor.execution.runtime.host.OwnedProcessController;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Harness process transport of the deterministic core E2E distribution (Task 19
 * peer-launch wiring): the placement backend that launches the mock protocol
 * peers as run-owned local processes for both {@code HOST} and {@code SANDBOX}
 * placements, selected by the harness setting
 * {@code e2e.sandbox-transport=process} (design §8.1: "explicit test-only
 * sandbox transport fixture where needed").
 *
 * <p>What it keeps real: the launch is a real argv profile started through the
 * committed {@link OwnedProcessController} with the same ownership binding the
 * production Host backend uses ({@link OwnedProcessController#start} +
 * {@link OwnedProcessController#owns}), the run-owned working/configuration
 * directories and the loopback endpoint come from the run's admitted
 * {@link WorkspaceLease}, pause/resume/stop are the controller's verified
 * process-tree operations, and the stop proof is the controller's own record.
 *
 * <p>What it deliberately substitutes, disclosed: the production Host backend
 * additionally proves the launched runtime holds the run's minted
 * bridge-control secret by calling the authenticated session route
 * ({@code GET /session} with {@code x-bridge-control-secret}); the mock peers
 * are not the bridge and serve no such route, so this backend does not perform
 * that probe. It still mints the per-run secret, delivers it to the launched
 * process (both as {@value #CONTROL_SECRET_ENVIRONMENT} and, when the profile
 * names a control-secret file, into that file -- the "trusted launcher" role
 * the production wiring leaves open), and hands the same value to the harness
 * adapter that opens the session.
 *
 * <p>The worker credential ({@value CoreE2eWorkerTokens#WORKER_TOKEN_ENV}) is
 * delivered like the production run lifecycle would deliver it, so the token
 * the harness route returns is exactly the value the run's peer process holds.
 *
 * <p>One {@link State} is shared by the HOST and the SANDBOX backend views, so
 * a run owned by either placement is equally reachable for the harness session's
 * peer control and for the adapters' secret lookup.
 */
public final class CoreE2eProcessBackend implements ExecutionBackend {

    /** The same variable the production Host backend delivers the minted secret in. */
    static final String CONTROL_SECRET_ENVIRONMENT = "QODER_BRIDGE_CONTROL_SECRET";

    /** The bridge argv flag whose file the trusted launcher writes the secret into. */
    static final String CONTROL_SECRET_FILE_FLAG = "--control-secret-file";

    private static final String CONFIGURATION_DIRECTORY = "e2e";

    private final ExecutionMode mode;
    private final State state;

    CoreE2eProcessBackend(ExecutionMode mode, State state) {
        this.mode = Objects.requireNonNull(mode, "mode");
        this.state = Objects.requireNonNull(state, "state");
    }

    /** Shared run-owned state of the harness process transport (both modes). */
    static final class State implements AutoCloseable {

        private static final SecureRandom SECRET_SOURCE = new SecureRandom();

        private final OwnedProcessController controller;
        private final CoreE2eWorkerTokens workerTokens;
        private final Map<UUID, PreparedEnvironment> environments = new ConcurrentHashMap<>();
        private final Map<UUID, OwnedProcess> processes = new ConcurrentHashMap<>();
        private final Map<UUID, String> controlSecrets = new ConcurrentHashMap<>();

        State(OwnedProcessController controller, CoreE2eWorkerTokens workerTokens) {
            this.controller = Objects.requireNonNull(controller, "controller");
            this.workerTokens = Objects.requireNonNull(workerTokens, "workerTokens");
        }

        /** The run's minted control secret, for the harness adapter that opens the session. */
        String controlSecret(UUID runId) {
            return controlSecrets.get(Objects.requireNonNull(runId, "runId"));
        }

        OwnedProcess ownedProcess(UUID runId) {
            return processes.get(runId);
        }

        /**
         * Verified suspension of a run's owned peer tree, for a harness session
         * whose core has no native pause route: the recorded control technique of
         * this program is process suspension, and the controller acknowledges only
         * a fully suspended tree.
         */
        CompletionStage<ControlAck> pausePeer(UUID runId, Instant deadline) {
            OwnedProcess owned = processes.get(runId);
            if (owned == null) {
                return CompletableFuture.failedFuture(new IllegalStateException(
                        "Run " + runId + " has no live peer process in this harness backend to pause"));
            }
            return controller.pause(owned, Objects.requireNonNull(deadline, "deadline"));
        }

        /** Resumes the same owned tree, matching every recorded suspension. */
        CompletionStage<ControlAck> resumePeer(UUID runId, Instant deadline) {
            OwnedProcess owned = processes.get(runId);
            if (owned == null) {
                return CompletableFuture.failedFuture(new IllegalStateException(
                        "Run " + runId + " has no live peer process in this harness backend to resume"));
            }
            return controller.resume(owned, Objects.requireNonNull(deadline, "deadline"));
        }

        @Override
        public void close() {
            controller.close();
        }
    }

    @Override
    public ExecutionMode mode() {
        return mode;
    }

    @Override
    public PreparedEnvironment prepare(ExecutionSpec spec, WorkspaceLease workspace) {
        Objects.requireNonNull(spec, "spec");
        Objects.requireNonNull(workspace, "workspace");
        if (!spec.runId().equals(workspace.runId())) {
            throw new IllegalArgumentException("Execution spec run " + spec.runId()
                    + " does not match workspace lease run " + workspace.runId());
        }
        Path configurationRoot = Path.of(workspace.runtimeRoot()).toAbsolutePath().normalize()
                .resolve(CONFIGURATION_DIRECTORY);
        try {
            Files.createDirectories(configurationRoot);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to prepare the run-owned configuration directory "
                    + configurationRoot + ": " + e.getMessage(), e);
        }
        URI endpoint;
        try {
            endpoint = allocateLoopbackEndpoint();
        } catch (IOException e) {
            throw new IllegalStateException("Unable to allocate a loopback endpoint for run " + spec.runId()
                    + ": " + e.getMessage(), e);
        }
        state.controlSecrets.put(spec.runId(), mintControlSecret());
        PreparedEnvironment environment = new PreparedEnvironment(spec.runId(), mode,
                "e2e-" + mode.name().toLowerCase(Locale.ROOT) + "-" + spec.runId(),
                workspace.localRoot().toString(), configurationRoot.toString(), endpoint);
        state.environments.put(spec.runId(), environment);
        return environment;
    }

    @Override
    public RuntimeHandle launch(PreparedEnvironment environment, LaunchProfile profile) {
        Objects.requireNonNull(environment, "environment");
        Objects.requireNonNull(profile, "profile");
        if (state.environments.get(environment.runId()) != environment) {
            throw new IllegalArgumentException("Environment was not prepared by this backend for run "
                    + environment.runId());
        }
        String controlSecret = state.controlSecrets.get(environment.runId());
        if (controlSecret == null) {
            throw new IllegalStateException("Run " + environment.runId()
                    + " has no minted control secret; it was not prepared by this backend");
        }
        writeNamedControlSecretFiles(profile, controlSecret);
        OwnedProcess owned = state.controller.start(environment.runId(),
                deliverSecrets(profile, environment, controlSecret));
        if (!state.controller.owns(owned)) {
            discard(owned);
            throw new IllegalStateException("The launched peer runtime failed its ownership verification for run "
                    + environment.runId() + "; it was terminated instead of being supervised on trust");
        }
        state.processes.put(environment.runId(), owned);
        return new RuntimeHandle(environment.runId(), environment.mode(), environment.environmentId(),
                owned.ownershipIdentity(), environment.endpoint());
    }

    @Override
    public CompletionStage<ControlAck> pauseWriters(RuntimeHandle handle, Instant deadline) {
        return state.controller.pause(resolve(handle), Objects.requireNonNull(deadline, "deadline"));
    }

    @Override
    public CompletionStage<ControlAck> resumeWriters(RuntimeHandle handle, Instant deadline) {
        return state.controller.resume(resolve(handle), Objects.requireNonNull(deadline, "deadline"));
    }

    @Override
    public StopProof stopWriters(RuntimeHandle handle, Instant deadline) {
        return state.controller.stop(resolve(handle), Objects.requireNonNull(deadline, "deadline"));
    }

    @Override
    public void exportWorkspace(RuntimeHandle handle, Path destination, StopProof proof) {
        requireVerifiedStop(handle, proof);
        PreparedEnvironment environment = state.environments.get(handle.runId());
        if (environment == null) {
            throw new IllegalStateException("Run " + handle.runId() + " was not prepared by this backend");
        }
        Path source = Path.of(environment.workingDirectory()).toAbsolutePath().normalize();
        Path target = Objects.requireNonNull(destination, "destination").toAbsolutePath().normalize();
        if (target.equals(source)) {
            return;
        }
        copyTree(source, target);
    }

    @Override
    public void destroy(RuntimeHandle handle) {
        Objects.requireNonNull(handle, "handle");
        OwnedProcess owned = state.processes.remove(handle.runId());
        state.controller.release(owned != null ? owned : OwnedProcess.parse(handle.ownershipIdentity()));
        state.controlSecrets.remove(handle.runId());
        PreparedEnvironment environment = state.environments.remove(handle.runId());
        if (environment != null) {
            Path generated = Path.of(environment.configurationDirectory()).toAbsolutePath().normalize();
            Path runConfigurationRoot = generated.getParent();
            try {
                if (runConfigurationRoot != null) {
                    WorkspacePaths.deleteTree(runConfigurationRoot, generated);
                }
            } catch (IOException e) {
                throw new IllegalStateException("Unable to remove the run-owned generated configuration "
                        + generated + ": " + e.getMessage(), e);
            }
        }
    }

    /**
     * Delivers the run's minted control secret and worker credential into the
     * runtime environment exactly as the harness's "trusted launcher" role
     * requires: the delivered values always win over profile values.
     */
    private LaunchProfile deliverSecrets(LaunchProfile profile, PreparedEnvironment environment,
            String controlSecret) {
        Map<String, String> env = new LinkedHashMap<>(profile.env());
        env.put(CONTROL_SECRET_ENVIRONMENT, controlSecret);
        env.put(CoreE2eWorkerTokens.WORKER_TOKEN_ENV, state.workerTokens.tokenFor(environment.runId()));
        return new LaunchProfile(profile.argv(), env, profile.workingDirectory());
    }

    /**
     * The bridge-shaped half of the launch: when the profile names a
     * control-secret file ({@code --control-secret-file <path>}), the trusted
     * launcher writes the run's minted secret there before the runtime starts.
     * The file lives in the run-owned configuration directory and is removed by
     * {@link #destroy}.
     */
    private static void writeNamedControlSecretFiles(LaunchProfile profile, String controlSecret) {
        List<String> argv = profile.argv();
        for (int index = 0; index + 1 < argv.size(); index++) {
            if (!CONTROL_SECRET_FILE_FLAG.equals(argv.get(index))) {
                continue;
            }
            Path target = Path.of(argv.get(index + 1)).toAbsolutePath().normalize();
            try {
                Files.createDirectories(target.getParent());
                Files.writeString(target, controlSecret, StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new IllegalStateException("Unable to write the run's control secret file " + target
                        + ": " + e.getMessage(), e);
            }
        }
    }

    private OwnedProcess resolve(RuntimeHandle handle) {
        Objects.requireNonNull(handle, "handle");
        if (handle.mode() != mode) {
            throw new IllegalArgumentException("Not a " + mode + " handle: " + handle.mode());
        }
        OwnedProcess live = state.processes.get(handle.runId());
        if (live != null) {
            return live;
        }
        OwnedProcess recovered = OwnedProcess.parse(handle.ownershipIdentity());
        if (!recovered.runId().equals(handle.runId())) {
            throw new IllegalArgumentException("Handle ownership identity belongs to run " + recovered.runId()
                    + ", not " + handle.runId());
        }
        return recovered;
    }

    private void discard(OwnedProcess owned) {
        Process live = owned.liveProcess();
        if (live != null) {
            for (ProcessHandle descendant : live.toHandle().descendants().toList()) {
                descendant.destroyForcibly();
            }
            live.destroyForcibly();
        }
        state.controller.release(owned);
    }

    private static void requireVerifiedStop(RuntimeHandle handle, StopProof proof) {
        if (proof == null || !Objects.equals(handle.runId(), proof.runId()) || !proof.allWritersStopped()) {
            throw new IllegalStateException("Writers are not stopped for run " + handle.runId()
                    + ": export requires a verified StopProof of that run");
        }
    }

    private static void copyTree(Path source, Path target) {
        if (!Files.isDirectory(source)) {
            throw new IllegalStateException("The run workspace is not a directory: " + source);
        }
        List<Path> entries;
        try {
            Files.createDirectories(target);
            try (var walk = Files.walk(source)) {
                entries = walk.toList();
            }
        } catch (IOException e) {
            throw new IllegalStateException("Unable to export workspace " + source + ": " + e.getMessage(), e);
        }
        for (Path entry : entries) {
            if (entry.equals(source)) {
                continue;
            }
            Path destination = target.resolve(source.relativize(entry));
            try {
                if (WorkspacePaths.isLinkLikeEntry(entry)) {
                    continue; // a link is never followed into retained results
                }
                if (Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS)) {
                    Files.createDirectories(destination);
                } else {
                    Files.copy(entry, destination, StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException e) {
                throw new IllegalStateException("Unable to export " + entry + ": " + e.getMessage(), e);
            }
        }
    }

    private static URI allocateLoopbackEndpoint() throws IOException {
        try (ServerSocket socket = new ServerSocket()) {
            socket.setReuseAddress(false);
            socket.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0));
            return URI.create("http://127.0.0.1:" + socket.getLocalPort() + "/");
        }
    }

    private static String mintControlSecret() {
        byte[] material = new byte[32];
        State.SECRET_SOURCE.nextBytes(material);
        return HexFormat.of().formatHex(material);
    }
}

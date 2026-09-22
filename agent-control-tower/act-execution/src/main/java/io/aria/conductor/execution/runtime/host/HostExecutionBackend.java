package io.aria.conductor.execution.runtime.host;

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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Host placement backend (spec 4.1, 5.1): one run-owned local process tree,
 * prepared on the backend machine itself and controlled through verified
 * ownership rather than a sandbox API.
 *
 * <p>Preparation touches no sandbox client and needs no container runtime: the
 * run's generated configuration is placed in the run-owned configuration
 * directory of its workspace lease, and the protocol endpoint is a fresh
 * loopback port allocated by binding it. A bind failure is explicit -- the
 * backend never probes or adopts an unrelated service, and it never connects to
 * an endpoint it did not allocate.
 *
 * <p>Preparation also mints the run's bridge control secret. The committed
 * run-bound bridge (spec 5.1) authenticates every HTTP route but {@code /health}
 * with that secret, so the backend delivers it to the runtime's environment at
 * launch and then <em>proves</em> the endpoint it allocated is that runtime's:
 * only a 200 from the authenticated session route whose binding names this run
 * declares the launch ready. The file-name stem comparison of the launch
 * profile's executable against the OS-reported command stays in place as a
 * cheap first filter, but the authenticated call is the real gate -- a listener
 * that does not hold the secret (an unrelated service on the port, a runtime
 * that lost the secret) is refused, and the runtime this backend launched is
 * terminated instead of being supervised on trust. {@code /health} is never the
 * gate: it answers anyone and proves nothing. A runtime that serves no
 * authenticated endpoint is therefore refused by this backend, deliberately.
 *
 * <p>Writer termination and configuration cleanup are separate operations:
 * {@link #stopWriters} produces the {@link StopProof} that capture and lock
 * release require, while {@link #destroy} removes only the run-owned generated
 * configuration and never deletes the workspace or signals a process itself.
 *
 * <p>The run's hard deadline bounds <em>execution</em>, not cleanup: stop,
 * pause/resume and export take a caller-supplied window and are never clamped
 * back to the deadline recorded at {@link #prepare}. Once the deadline has
 * elapsed the run still has to be stoppable and its workspace exportable, so the
 * caller's window is honoured as given (an already elapsed window is still
 * refused by the controller, because no proof may be claimed for it). The order
 * is stop, then export, then destroy: a verified stop never removes the
 * environment entry that {@link #exportWorkspace} needs, and {@link #destroy} is
 * the only operation that ends it.
 */
public class HostExecutionBackend implements ExecutionBackend {

    /** Generated run-owned configuration, kept beside (never inside) the workspace. */
    private static final String CONFIGURATION_DIRECTORY = "host";

    /**
     * Header the committed bridge requires on every route but {@code /health}
     * (packages/qoder-acp-bridge, {@code CONTROL_SECRET_HEADER}).
     */
    static final String CONTROL_SECRET_HEADER = "x-bridge-control-secret";

    /**
     * Environment variable the run's runtime receives the minted per-run control
     * secret in. The bridge itself reads the secret from its
     * {@code --control-secret-file} (the launch profile's contract) and must
     * never forward it to the Qoder CLI child -- this variable is the trusted
     * launcher's delivery to the runtime process.
     */
    static final String CONTROL_SECRET_ENVIRONMENT = "QODER_BRIDGE_CONTROL_SECRET";

    /** Authenticated bridge route used to authenticate the endpoint; {@code /health} would prove nothing. */
    private static final String SESSION_ROUTE = "session";
    /** How long a launch waits for its runtime to answer the authenticated call. */
    private static final Duration AUTHENTICATION_WINDOW = Duration.ofSeconds(30);
    private static final Duration AUTHENTICATION_PAUSE = Duration.ofMillis(100);
    private static final Duration AUTHENTICATION_CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration AUTHENTICATION_REQUEST_TIMEOUT = Duration.ofSeconds(5);
    /** Bounded read of the runtime's authentication answer. */
    private static final int AUTHENTICATION_BODY_LIMIT = 64 * 1024;

    private static final SecureRandom SECRET_SOURCE = new SecureRandom();
    private static final ObjectMapper JSON = new ObjectMapper();

    private final OwnedProcessController controller;
    private final EndpointAllocator endpoints;
    private final Map<UUID, PreparedEnvironment> environments = new ConcurrentHashMap<>();
    private final Map<UUID, OwnedProcess> processes = new ConcurrentHashMap<>();
    /** The per-run bridge control secret minted at {@link #prepare}, memory-only. */
    private final Map<UUID, String> controlSecrets = new ConcurrentHashMap<>();

    public HostExecutionBackend(OwnedProcessController controller) {
        this(controller, HostExecutionBackend::allocateLoopbackEndpoint);
    }

    /** Explicit allocator seam: a failed bind must fail the preparation. */
    HostExecutionBackend(OwnedProcessController controller, EndpointAllocator endpoints) {
        this.controller = Objects.requireNonNull(controller, "controller");
        this.endpoints = Objects.requireNonNull(endpoints, "endpoints");
    }

    public static HostExecutionBackend forCurrentPlatform() {
        return new HostExecutionBackend(OwnedProcessController.forCurrentPlatform());
    }

    @Override
    public ExecutionMode mode() {
        return ExecutionMode.HOST;
    }

    @Override
    public PreparedEnvironment prepare(ExecutionSpec spec, WorkspaceLease workspace) {
        Objects.requireNonNull(spec, "spec");
        Objects.requireNonNull(workspace, "workspace");
        if (!spec.runId().equals(workspace.runId())) {
            throw new IllegalArgumentException("Execution spec run " + spec.runId()
                    + " does not match workspace lease run " + workspace.runId());
        }
        Path runConfigurationRoot = Path.of(workspace.runtimeRoot()).toAbsolutePath().normalize();
        Path generated = runConfigurationRoot.resolve(CONFIGURATION_DIRECTORY);
        try {
            Files.createDirectories(generated);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to prepare the run-owned configuration directory " + generated
                    + ": " + e.getMessage(), e);
        }
        URI endpoint;
        try {
            endpoint = endpoints.allocate();
        } catch (IOException e) {
            throw new IllegalStateException("Unable to allocate a loopback endpoint for run " + spec.runId()
                    + ": " + e.getMessage(), e);
        }
        if (!isLoopback(endpoint)) {
            throw new IllegalStateException("Host endpoints must bind to loopback, got " + endpoint);
        }
        // Minted here, delivered at launch and never persisted: the secret lives
        // for exactly this backend's custody of the run.
        controlSecrets.put(spec.runId(), mintControlSecret());
        PreparedEnvironment environment = new PreparedEnvironment(spec.runId(), ExecutionMode.HOST,
                "host-" + spec.runId(), workspace.localRoot().toString(), generated.toString(), endpoint);
        environments.put(spec.runId(), environment);
        return environment;
    }

    @Override
    public RuntimeHandle launch(PreparedEnvironment environment, LaunchProfile profile) {
        Objects.requireNonNull(environment, "environment");
        Objects.requireNonNull(profile, "profile");
        if (environments.get(environment.runId()) != environment) {
            throw new IllegalArgumentException("Environment was not prepared by this backend for run "
                    + environment.runId());
        }
        String controlSecret = controlSecrets.get(environment.runId());
        if (controlSecret == null) {
            throw new IllegalStateException("Run " + environment.runId()
                    + " has no minted control secret; it was not prepared by this backend");
        }
        OwnedProcess owned = controller.start(environment.runId(), deliverControlSecret(profile, controlSecret));
        if (!controller.owns(owned)) {
            discard(owned);
            throw new IllegalStateException("The launched runtime failed its ownership verification for run "
                    + environment.runId() + "; it was terminated instead of being supervised on trust");
        }
        try {
            // The real gate: the runtime at the allocated endpoint must prove it
            // holds this run's control secret. The executable-stem check inside
            // controller.start is only a cheap first filter -- any process of
            // that name would satisfy it.
            authenticateRuntimeEndpoint(environment, controlSecret);
        } catch (RuntimeException e) {
            discard(owned);
            throw e;
        }
        processes.put(environment.runId(), owned);
        return new RuntimeHandle(environment.runId(), ExecutionMode.HOST, environment.environmentId(),
                owned.ownershipIdentity(), environment.endpoint());
    }

    @Override
    public CompletionStage<ControlAck> pauseWriters(RuntimeHandle handle, Instant deadline) {
        // Cleanup window, caller-supplied: the run deadline is not a clamp here
        // (see the class javadoc) -- the controller refuses only an already
        // elapsed window, because no state may be claimed for one.
        return controller.pause(resolve(handle), Objects.requireNonNull(deadline, "deadline"));
    }

    @Override
    public CompletionStage<ControlAck> resumeWriters(RuntimeHandle handle, Instant deadline) {
        return controller.resume(resolve(handle), Objects.requireNonNull(deadline, "deadline"));
    }

    @Override
    public StopProof stopWriters(RuntimeHandle handle, Instant deadline) {
        return controller.stop(resolve(handle), Objects.requireNonNull(deadline, "deadline"));
    }

    /**
     * Copies the stopped run's workspace into the capture destination. Export
     * consumes the verified stop proof and stays available with the same handle
     * until {@link #destroy} ends the run: a stop never removes the environment
     * entry this operation reads, so the order stop, export, destroy always works
     * -- including after the run deadline elapsed.
     */
    @Override
    public void exportWorkspace(RuntimeHandle handle, Path destination, StopProof proof) {
        requireVerifiedStop(handle, proof);
        PreparedEnvironment environment = environments.get(handle.runId());
        if (environment == null) {
            throw new IllegalStateException("Run " + handle.runId() + " was not prepared by this backend");
        }
        Path source = Path.of(environment.workingDirectory()).toAbsolutePath().normalize();
        Path target = Objects.requireNonNull(destination, "destination").toAbsolutePath().normalize();
        if (target.equals(source)) {
            return; // the run-owned workspace already is the retained location
        }
        copyTree(source, target);
    }

    /**
     * Destroys the run's placement record: generation configuration, the export
     * handle and any supervision left over. It is the last step of the lifecycle
     * (stop, export, destroy) and never a substitute for the stop: it deletes only
     * the run-owned generated configuration and never deletes the workspace or
     * signals a process itself. Releasing the last supervision channel closes the
     * Windows job, whose kill-on-close limit is the OS-level backstop for a run
     * that was never successfully stopped.
     */
    @Override
    public void destroy(RuntimeHandle handle) {
        Objects.requireNonNull(handle, "handle");
        OwnedProcess owned = processes.remove(handle.runId());
        controller.release(owned != null ? owned : OwnedProcess.parse(handle.ownershipIdentity()));
        controlSecrets.remove(handle.runId());
        PreparedEnvironment environment = environments.remove(handle.runId());
        if (environment == null) {
            return;
        }
        Path generated = Path.of(environment.configurationDirectory()).toAbsolutePath().normalize();
        Path runConfigurationRoot = generated.getParent();
        try {
            if (runConfigurationRoot != null) {
                // Link-safe deletion from the run-owned root: a junction above the
                // deletion root refuses the deletion instead of redirecting it.
                WorkspacePaths.deleteTree(runConfigurationRoot, generated);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Unable to remove the run-owned generated configuration " + generated
                    + ": " + e.getMessage(), e);
        }
    }

    /** The live owned record of a launched run (memory-only; empty on another backend). */
    public Optional<OwnedProcess> ownedProcess(RuntimeHandle handle) {
        Objects.requireNonNull(handle, "handle");
        return Optional.ofNullable(processes.get(handle.runId()));
    }

    /**
     * Resolves the handle's owned record. Without a live record for the run the
     * handle's persisted ownership identity is reconstructed so the call is
     * answered by the control contract rather than a parse error -- but a
     * reconstructed record carries no in-memory binding, so the controller
     * refuses it and the caller receives an unverified outcome (no stop proof,
     * no verified state change) with no OS action taken. A run that survived a
     * backend restart is the recovery coordinator's to adopt or reap, with
     * run-store evidence -- never this handle's to signal on trust.
     */
    private OwnedProcess resolve(RuntimeHandle handle) {
        Objects.requireNonNull(handle, "handle");
        if (handle.mode() != ExecutionMode.HOST) {
            throw new IllegalArgumentException("Not a Host handle: " + handle.mode());
        }
        OwnedProcess live = processes.get(handle.runId());
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

    /**
     * The per-run bridge control secret, delivered into the runtime's
     * environment. The secret the run was prepared with always wins over a
     * profile-supplied value: the delivery is the backend's, never a caller's.
     */
    private static LaunchProfile deliverControlSecret(LaunchProfile profile, String controlSecret) {
        Map<String, String> env = new LinkedHashMap<>(profile.env());
        env.put(CONTROL_SECRET_ENVIRONMENT, controlSecret);
        return new LaunchProfile(profile.argv(), env, profile.workingDirectory());
    }

    /** A fresh 256-bit secret; the committed bridge insists on at least 16 characters. */
    private static String mintControlSecret() {
        byte[] material = new byte[32];
        SECRET_SOURCE.nextBytes(material);
        return HexFormat.of().formatHex(material);
    }

    /**
     * Terminates a runtime whose launch could not be completed and ends its
     * supervision. The runtime may already have spawned its own child -- a
     * bridge starts its core before it can serve the endpoint refused here -- so
     * the descendants visible through the live handle are force-killed first,
     * then the root. This is a best-effort cleanup of a refused launch, not a
     * stop proof, and it deliberately does not go through the controller's sweep:
     * that sweep's Windows job backstop acts on the controller's shared job and
     * would take another supervised run down with it. The secret of the run stays
     * minted: only {@link #destroy} ends the run, and a retried launch must
     * deliver the same secret.
     */
    private void discard(OwnedProcess owned) {
        Process live = owned.liveProcess();
        if (live != null) {
            for (ProcessHandle descendant : live.toHandle().descendants().toList()) {
                descendant.destroyForcibly();
            }
            live.destroyForcibly();
        }
        controller.release(owned);
    }

    /**
     * Proves the run's endpoint is served by this run's runtime. The committed
     * bridge (spec 5.1) authenticates every route but {@code /health} with the
     * per-run control secret, so a 200 from the session route whose binding
     * names this run is the runtime's proof that it received the secret this
     * backend minted and delivered; {@code /health} is never consulted, because
     * it answers anyone and identifies nothing. A runtime that has not started
     * yet leaves the port unbound (retried until the window elapses); an answer
     * that is not that proof -- a refusal, a foreign service, a wrong binding --
     * is definitive and refuses the launch immediately. The failure carries no
     * secret value.
     */
    private static void authenticateRuntimeEndpoint(PreparedEnvironment environment, String controlSecret) {
        URI target = environment.endpoint().resolve(SESSION_ROUTE);
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(AUTHENTICATION_CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NEVER)
                .version(HttpClient.Version.HTTP_1_1)
                .build();
        HttpRequest request = HttpRequest.newBuilder(target)
                .header(CONTROL_SECRET_HEADER, controlSecret)
                .timeout(AUTHENTICATION_REQUEST_TIMEOUT)
                .GET()
                .build();
        Instant window = Instant.now().plus(AUTHENTICATION_WINDOW);
        String lastFailure = "no attempt was made";
        while (true) {
            HttpResponse<InputStream> response = null;
            try {
                response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            } catch (IOException e) {
                // Nothing is bound yet (or the listener closed): only the window bounds the wait.
                lastFailure = e.getClass().getSimpleName() + ": " + e.getMessage();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while authenticating the runtime endpoint of run "
                        + environment.runId(), e);
            }
            if (response != null) {
                String body = readBounded(response);
                if (response.statusCode() != 200) {
                    throw authenticationFailure(environment, "the endpoint refused the run's control secret (status "
                            + response.statusCode() + ")");
                }
                if (!bindsRun(body, environment.runId())) {
                    throw authenticationFailure(environment,
                            "the endpoint answered with an unauthenticated or foreign binding");
                }
                return;
            }
            if (!Instant.now().isBefore(window)) {
                throw authenticationFailure(environment,
                        "no authenticated answer within " + AUTHENTICATION_WINDOW + " (last: " + lastFailure + ")");
            }
            try {
                Thread.sleep(AUTHENTICATION_PAUSE.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while authenticating the runtime endpoint of run "
                        + environment.runId(), e);
            }
        }
    }

    private static IllegalStateException authenticationFailure(PreparedEnvironment environment, String detail) {
        return new IllegalStateException("The runtime endpoint of run " + environment.runId()
                + " did not authenticate the run's control secret (" + detail
                + "); the runtime was terminated instead of being trusted on its executable name");
    }

    /** Bounded read of the runtime's answer: a listener cannot make this unbounded. */
    private static String readBounded(HttpResponse<InputStream> response) {
        try (InputStream body = response.body()) {
            return new String(body.readNBytes(AUTHENTICATION_BODY_LIMIT), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    /** True when the answer's binding names exactly this run. */
    private static boolean bindsRun(String body, UUID runId) {
        try {
            JsonNode binding = JSON.readTree(body);
            return binding != null && runId.toString().equals(binding.path("runId").asText(""));
        } catch (IOException e) {
            return false;
        }
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

    /** Allocates a fresh loopback port by binding it; the caller publishes it to the runtime. */
    private static URI allocateLoopbackEndpoint() throws IOException {
        try (ServerSocket socket = new ServerSocket()) {
            socket.setReuseAddress(false);
            socket.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0));
            return URI.create("http://127.0.0.1:" + socket.getLocalPort() + "/");
        }
    }

    private static boolean isLoopback(URI endpoint) {
        String host = endpoint.getHost();
        return "127.0.0.1".equals(host) || "::1".equals(host) || "localhost".equalsIgnoreCase(host);
    }

    /** Binds the run's loopback endpoint; a bind failure must fail the preparation. */
    @FunctionalInterface
    interface EndpointAllocator {
        URI allocate() throws IOException;
    }
}

package io.aria.conductor.execution.runtime.sandbox;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aria.conductor.common.runtime.AgentExecutionSettings;
import io.aria.conductor.common.runtime.ExecutionMode;
import io.aria.conductor.common.runtime.WorkspaceKind;
import io.aria.conductor.common.runtime.WorkspaceMode;
import io.aria.conductor.execution.runtime.ArtifactBundle;
import io.aria.conductor.execution.runtime.ControlAck;
import io.aria.conductor.execution.runtime.ExecutionSpec;
import io.aria.conductor.execution.runtime.LaunchProfile;
import io.aria.conductor.execution.runtime.PreparedEnvironment;
import io.aria.conductor.execution.runtime.RuntimeHandle;
import io.aria.conductor.execution.runtime.StopProof;
import io.aria.conductor.execution.runtime.WorkspaceLease;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Real-container lane harness for {@code e2e/agent-core/sandbox-lifecycle.test.mjs}.
 *
 * <p>It is a JSON-lines driver over the <em>production</em>
 * {@link SandboxExecutionBackend} and {@link SandboxLifecycle} against a real
 * OpenSandbox server and a real core image: the Node E2E spawns this class, sends
 * one command per line on stdin and reads one JSON result per line on stdout. No
 * behaviour is faked here -- every command calls the same code the run
 * orchestration calls, and the Node side asserts the observable container state
 * (endpoint responses, writer log growth, exported bytes, sandbox liveness).
 *
 * <p>Commands ({@code {"command": ...}}):
 * <ul>
 *   <li>{@code prepare} -- runId, coreId, image, port, runtimeRoot, snapshot, [env]</li>
 *   <li>{@code launch} -- runId, argv, [env], [workingDirectory]; returns the endpoint</li>
 *   <li>{@code renewalPlan} -- intervalMs, extensionMs (renewal counters below)</li>
 *   <li>{@code renewals} -- returns the number of renew calls the SDK made</li>
 *   <li>{@code pause} / {@code resume} -- runId; returns the ack</li>
 *   <li>{@code stopWriters} -- runId; returns the stop proof</li>
 *   <li>{@code export} -- runId, destination, proof; returns the bundle (complete + digest)</li>
 *   <li>{@code exec} -- runId, shell; returns the shell command's output (diagnostics only)</li>
 *   <li>{@code destroy} -- runId</li>
 *   <li>{@code quit}</li>
 * </ul>
 *
 * <p>Environment: {@code ARIA_OPEN_SANDBOX_URL} (required, fails closed),
 * {@code ARIA_OPEN_SANDBOX_API_KEY} (optional).
 */
public final class SandboxLifecycleHarness {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Map<UUID, RuntimeHandle> HANDLES = new ConcurrentHashMap<>();
    private static final Map<UUID, PreparedEnvironment> ENVIRONMENTS = new ConcurrentHashMap<>();
    private static final AtomicInteger RENEWALS = new AtomicInteger();

    private static SandboxExecutionBackend backend;
    private static SandboxLifecycle lifecycle;
    private static SandboxLifecycle.OpenSandboxSdk sandboxSdk;

    public static void main(String[] args) throws Exception {
        String serverUrl = System.getenv("ARIA_OPEN_SANDBOX_URL");
        if (serverUrl == null || serverUrl.isBlank()) {
            fail("ARIA_OPEN_SANDBOX_URL is required: this lane runs against a real OpenSandbox server");
            return;
        }
        String apiKey = System.getenv("ARIA_OPEN_SANDBOX_API_KEY");
        sandboxSdk = new SandboxLifecycle.OpenSandboxSdk(serverUrl, apiKey);
        lifecycle = new SandboxLifecycle(new CountingSdk(sandboxSdk));
        backend = new SandboxExecutionBackend(lifecycle, coreId -> {
            throw new IllegalStateException("The harness resolves every profile from the prepare command");
        });

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(System.in, StandardCharsets.UTF_8));
                PrintWriter writer = new PrintWriter(System.out, true, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                writer.println(dispatch(MAPPER.readValue(line, new TypeReference<Map<String, Object>>() {
                })));
            }
        }
    }

    private static String dispatch(Map<String, Object> request) {
        String command = String.valueOf(request.get("command"));
        try {
            return switch (command) {
                case "prepare" -> prepare(request);
                case "launch" -> launch(request);
                case "renewalPlan" -> renewalPlan(request);
                case "renewals" -> ok(Map.of("renewals", RENEWALS.get()));
                case "pause" -> control(request, true);
                case "resume" -> control(request, false);
                case "stopWriters" -> stopWriters(request);
                case "export" -> export(request);
                case "exec" -> exec(request);
                case "destroy" -> destroy(request);
                case "quit" -> ok(Map.of("quit", true));
                default -> failure("Unknown command: " + command);
            };
        } catch (RuntimeException | java.io.IOException e) {
            return failure(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private static String prepare(Map<String, Object> request) throws java.io.IOException {
        UUID runId = UUID.fromString(required(request, "runId"));
        String coreId = required(request, "coreId");
        String image = required(request, "image");
        int port = Integer.parseInt(required(request, "port"));
        Path runtimeRoot = Files.createDirectories(Path.of(required(request, "runtimeRoot")).resolve(runId.toString()));
        Path snapshot = Path.of(required(request, "snapshot"));
        if (!Files.isDirectory(snapshot)) {
            throw new IllegalArgumentException("Snapshot directory does not exist: " + snapshot);
        }
        // A per-run profile resolver: the harness receives the trusted image/port
        // from its own command line, never from a worker.
        backend = new SandboxExecutionBackend(lifecycle,
                requested -> requested.equals(coreId)
                        ? new SandboxExecutionBackend.SandboxProfile(image, port)
                        : null);
        WorkspaceLease lease = new WorkspaceLease(UUID.randomUUID(), runId, WorkspaceKind.SANDBOX_SNAPSHOT,
                snapshot, null, runtimeRoot.toString(), null);
        ExecutionSpec spec = new ExecutionSpec(runId, UUID.randomUUID(), coreId, ExecutionMode.SANDBOX,
                new AgentExecutionSettings(coreId, ExecutionMode.SANDBOX, WorkspaceMode.DIRECT, null, null),
                "harness-credential-ref", "harness-configuration-revision",
                Instant.now().plus(Duration.ofMinutes(45)));
        PreparedEnvironment environment = backend.prepare(spec, lease);
        ENVIRONMENTS.put(runId, environment);
        return ok(Map.of("runId", runId.toString(), "environmentId", environment.environmentId(),
                "endpoint", String.valueOf(environment.endpoint())));
    }

    private static String launch(Map<String, Object> request) {
        UUID runId = UUID.fromString(required(request, "runId"));
        PreparedEnvironment environment = ENVIRONMENTS.get(runId);
        if (environment == null) {
            throw new IllegalStateException("Run " + runId + " was not prepared");
        }
        @SuppressWarnings("unchecked")
        List<String> argv = (List<String>) request.get("argv");
        @SuppressWarnings("unchecked")
        Map<String, String> env = request.get("env") == null ? Map.of()
                : (Map<String, String>) request.get("env");
        String workingDirectory = request.get("workingDirectory") == null
                ? environment.workingDirectory() : String.valueOf(request.get("workingDirectory"));
        RuntimeHandle handle = backend.launch(environment, new LaunchProfile(argv, env, workingDirectory));
        HANDLES.put(runId, handle);
        return ok(Map.of("runId", runId.toString(), "endpoint", String.valueOf(handle.endpoint()),
                "ownershipIdentity", handle.ownershipIdentity()));
    }

    private static String renewalPlan(Map<String, Object> request) {
        long intervalMs = Long.parseLong(required(request, "intervalMs"));
        long extensionMs = Long.parseLong(required(request, "extensionMs"));
        lifecycle.setRenewalPlan(Duration.ofMillis(intervalMs), Duration.ofMillis(extensionMs));
        return ok(Map.of("intervalMs", intervalMs, "extensionMs", extensionMs));
    }

    private static String control(Map<String, Object> request, boolean pause) {
        UUID runId = UUID.fromString(required(request, "runId"));
        RuntimeHandle handle = requireHandle(runId);
        Instant deadline = Instant.now().plus(Duration.ofSeconds(60));
        ControlAck ack = (pause ? backend.pauseWriters(handle, deadline) : backend.resumeWriters(handle, deadline))
                .toCompletableFuture().join();
        return ok(Map.of("state", ack.state().name(), "verified", ack.verified()));
    }

    private static String stopWriters(Map<String, Object> request) {
        UUID runId = UUID.fromString(required(request, "runId"));
        StopProof proof = backend.stopWriters(requireHandle(runId), Instant.now().plus(Duration.ofSeconds(60)));
        return ok(Map.of("runId", proof.runId().toString(), "allWritersStopped", proof.allWritersStopped()));
    }

    private static String export(Map<String, Object> request) throws java.io.IOException {
        UUID runId = UUID.fromString(required(request, "runId"));
        Path destination = Files.createDirectories(Path.of(required(request, "destination")));
        StopProof proof = new StopProof(runId, Boolean.parseBoolean(required(request, "proof")));
        ArtifactBundle bundle = lifecycle.export(runId, destination, proof);
        return ok(Map.of("complete", bundle.complete(),
                "manifestSha256", String.valueOf(bundle.manifestSha256()),
                "directory", bundle.directory().toString()));
    }

    private static String exec(Map<String, Object> request) {
        UUID runId = UUID.fromString(required(request, "runId"));
        String sandboxId = lifecycle.sandboxId(runId)
                .orElseThrow(() -> new IllegalStateException("Run " + runId + " has no sandbox"));
        // Diagnostics only: reading observable container state (writer logs, process
        // tables) so the E2E asserts real effects rather than the backend's own report.
        return ok(Map.of("output", String.valueOf(
                sandboxSdk.runCommand(sandboxId, required(request, "shell")))));
    }

    private static String destroy(Map<String, Object> request) {
        UUID runId = UUID.fromString(required(request, "runId"));
        backend.destroy(requireHandle(runId));
        ENVIRONMENTS.remove(runId);
        HANDLES.remove(runId);
        return ok(Map.of("runId", runId.toString()));
    }

    private static RuntimeHandle requireHandle(UUID runId) {
        RuntimeHandle handle = HANDLES.get(runId);
        if (handle == null) {
            throw new IllegalStateException("Run " + runId + " was not launched");
        }
        return handle;
    }

    private static String required(Map<String, Object> request, String key) {
        Object value = request.get(key);
        if (value == null || String.valueOf(value).isBlank()) {
            throw new IllegalArgumentException("Missing required field: " + key);
        }
        return String.valueOf(value);
    }

    private static String ok(Map<String, Object> fields) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", true);
        result.putAll(fields);
        return render(result);
    }

    private static String failure(String message) {
        return render(Map.of("ok", false, "error", message));
    }

    private static String render(Map<String, Object> result) {
        try {
            return MAPPER.writeValueAsString(result);
        } catch (java.io.IOException e) {
            return "{\"ok\":false,\"error\":\"unserializable result\"}";
        }
    }

    private static void fail(String message) {
        System.out.println(failure(message));
    }

    /** Counts renewal calls so the E2E can assert automatic renewal actually ran. */
    private record CountingSdk(SandboxLifecycle.OpenSandboxSdk delegate) implements SandboxLifecycle.SandboxSdk {

        @Override
        public String create(String image, Map<String, String> env) {
            return delegate.create(image, env);
        }

        @Override
        public void upload(String sandboxId, List<com.alibaba.opensandbox.sandbox.domain.models.execd.filesystem.WriteEntry> entries) {
            delegate.upload(sandboxId, entries);
        }

        @Override
        public String endpoint(String sandboxId, int port) {
            return delegate.endpoint(sandboxId, port);
        }

        @Override
        public void launch(String sandboxId, String runDirectory, Duration timeout) {
            delegate.launch(sandboxId, runDirectory, timeout);
        }

        @Override
        public String pauseWriters(String sandboxId, String runDirectory, Duration timeout) {
            return delegate.pauseWriters(sandboxId, runDirectory, timeout);
        }

        @Override
        public String resumeWriters(String sandboxId, String runDirectory, Duration timeout) {
            return delegate.resumeWriters(sandboxId, runDirectory, timeout);
        }

        @Override
        public String stopWriters(String sandboxId, String runDirectory, Duration timeout) {
            return delegate.stopWriters(sandboxId, runDirectory, timeout);
        }

        @Override
        public void renew(String sandboxId, Duration extension) {
            RENEWALS.incrementAndGet();
            delegate.renew(sandboxId, extension);
        }

        @Override
        public List<RemoteFile> export(String sandboxId, String remoteRoot) {
            return delegate.export(sandboxId, remoteRoot);
        }

        @Override
        public void kill(String sandboxId) {
            delegate.kill(sandboxId);
        }
    }
}

package io.aria.conductor.execution.runtime.host;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.aria.conductor.common.runtime.AgentExecutionSettings;
import io.aria.conductor.common.runtime.ExecutionMode;
import io.aria.conductor.common.runtime.WorkspaceKind;
import io.aria.conductor.execution.runtime.ControlAck;
import io.aria.conductor.execution.runtime.ExecutionSpec;
import io.aria.conductor.execution.runtime.LaunchProfile;
import io.aria.conductor.execution.runtime.PreparedEnvironment;
import io.aria.conductor.execution.runtime.RuntimeHandle;
import io.aria.conductor.execution.runtime.StopProof;
import io.aria.conductor.execution.runtime.WorkspaceLease;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Task 20 fix round 1: the host placement is the trusted launcher of the
 * run-owned files the launch profile names. A profile that declares a
 * control-secret file must find it written with exactly the run's minted secret
 * before the runtime is started (the committed bridge refuses a
 * {@code --control-secret-file} it cannot read, which at the readiness gate is
 * only a 30-second connect timeout), and the session opener receives that same
 * secret. A profile that names a file outside the run-owned generated
 * configuration directory is refused instead of being obeyed.
 */
class HostLaunchSecretFileTest {

    private static final String CONTROL_SECRET_ENVIRONMENT = "QODER_BRIDGE_CONTROL_SECRET";

    private final AtomicBoolean started = new AtomicBoolean();
    private Path root;
    private HttpServer endpoint;

    @AfterEach
    void tearDown() throws IOException {
        if (endpoint != null) {
            endpoint.stop(0);
        }
        if (root != null) {
            deleteTree(root);
        }
    }

    private record Fixture(Path root, Path workspace, Path configuration, UUID runId,
            ExecutionSpec spec, WorkspaceLease lease) {
    }

    private Fixture fixture() throws IOException {
        root = Files.createTempDirectory("host-secret-file");
        Path workspace = Files.createDirectories(root.resolve("workspace"));
        Path runtimeRoot = Files.createDirectories(root.resolve("runtime"));
        UUID runId = UUID.randomUUID();
        ExecutionSpec spec = new ExecutionSpec(runId, UUID.randomUUID(), "qoder", ExecutionMode.HOST,
                new AgentExecutionSettings("qoder", ExecutionMode.HOST, null, null, null),
                "qoder:operator", "rev-1", Instant.now().plusSeconds(600));
        WorkspaceLease lease = new WorkspaceLease(UUID.randomUUID(), runId, WorkspaceKind.DIRECT,
                workspace, workspace, runtimeRoot.toString(), null);
        return new Fixture(root, workspace, runtimeRoot.resolve("host"), runId, spec, lease);
    }

    /** The placement backend under test: the stub controller never starts a real process. */
    private HostExecutionBackend backend(URI allocated) {
        OwnedProcessController controller = new OwnedProcessController() {
            @Override
            public OwnedProcess start(UUID runId, LaunchProfile profile) {
                started.set(true);
                return new OwnedProcess(runId, "nonce", 4711L, "4711@1", "supervisor", "stub", null);
            }

            @Override
            public boolean owns(OwnedProcess process) {
                return process != null && process.liveProcess() == null;
            }

            @Override
            public StopProof stop(OwnedProcess process, Instant deadline) {
                throw new UnsupportedOperationException();
            }

            @Override
            public CompletionStage<ControlAck> pause(OwnedProcess process, Instant deadline) {
                throw new UnsupportedOperationException();
            }

            @Override
            public CompletionStage<ControlAck> resume(OwnedProcess process, Instant deadline) {
                throw new UnsupportedOperationException();
            }

            @Override
            public void release(OwnedProcess process) {
            }
        };
        return new HostExecutionBackend(controller, () -> allocated);
    }

    /** An endpoint that authenticates with exactly the secret read from the named run-owned file. */
    private HttpServer endpointReading(Path secretFile, UUID runId) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/session", exchange -> {
            String expected = Files.readString(secretFile, StandardCharsets.UTF_8).trim();
            String provided = exchange.getRequestHeaders().getFirst("x-bridge-control-secret");
            if (!expected.equals(provided)) {
                answer(exchange, 401, "{\"error\":{\"code\":\"E_UNAUTHORIZED\"}}");
                return;
            }
            answer(exchange, 200, "{\"runId\":\"" + runId + "\"}");
        });
        server.start();
        return server;
    }

    private static void answer(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static URI uriOf(HttpServer server) {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/");
    }

    @Test
    void theLaunchWritesTheDeclaredSecretFileAndExposesTheSameSecretToTheSession() throws Exception {
        Fixture fixture = fixture();
        Path declared = fixture.configuration().resolve("bridge-control.secret");
        endpoint = endpointReading(declared, fixture.runId());
        HostExecutionBackend backend = backend(uriOf(endpoint));
        PreparedEnvironment environment = backend.prepare(fixture.spec(), fixture.lease());
        LaunchProfile profile = new LaunchProfile(List.of("stub-runtime"), Map.of(),
                fixture.workspace().toString(), declared.toString());

        RuntimeHandle handle = backend.launch(environment, profile);

        String written = Files.readString(declared, StandardCharsets.UTF_8).trim();
        assertThat(written)
                .as("the runtime must find the control secret it is told to read")
                .hasSizeGreaterThanOrEqualTo(32);
        assertThat(backend.sessionSecret(handle).environment())
                .as("the session opener receives exactly the minted secret")
                .containsEntry(CONTROL_SECRET_ENVIRONMENT, written);
    }

    @Test
    void aProfileNamingAFileOutsideTheRunConfigurationDirectoryIsRefused() throws Exception {
        Fixture fixture = fixture();
        Path outside = fixture.root().resolve("outside.secret");
        HostExecutionBackend backend = backend(URI.create("http://127.0.0.1:1/"));
        PreparedEnvironment environment = backend.prepare(fixture.spec(), fixture.lease());
        LaunchProfile profile = new LaunchProfile(List.of("stub-runtime"), Map.of(),
                fixture.workspace().toString(), outside.toString());

        assertThatThrownBy(() -> backend.launch(environment, profile))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("outside the run-owned configuration directory");
        assertThat(started)
                .as("a refused profile must never have started a runtime")
                .isFalse();
        assertThat(outside).doesNotExist();
    }

    @Test
    void aProfileWithoutADeclaredSecretFileExposesNoSessionSecret() throws Exception {
        Fixture fixture = fixture();
        Path config = Files.createDirectories(fixture.configuration());
        endpoint = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        endpoint.createContext("/session", exchange -> answer(exchange, 200,
                "{\"runId\":\"" + fixture.runId() + "\"}"));
        endpoint.start();
        HostExecutionBackend backend = backend(uriOf(endpoint));
        PreparedEnvironment environment = backend.prepare(fixture.spec(), fixture.lease());
        LaunchProfile profile = new LaunchProfile(List.of("stub-runtime"), Map.of(),
                fixture.workspace().toString());

        RuntimeHandle handle = backend.launch(environment, profile);

        assertThat(profile.controlSecretFile()).isNull();
        assertThat(backend.sessionSecret(handle).environment()).isEmpty();
        assertThat(Files.list(config).toList()).isEmpty();
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (var walk = Files.walk(root)) {
            for (Path path : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}

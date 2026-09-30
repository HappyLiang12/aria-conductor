package io.aria.conductor.execution.runtime.core;

import com.sun.net.httpserver.HttpServer;
import io.aria.conductor.common.runtime.AgentExecutionSettings;
import io.aria.conductor.common.runtime.ExecutionMode;
import io.aria.conductor.execution.adk.TaskExecutionException;
import io.aria.conductor.execution.mcp.RunMcpWiring;
import io.aria.conductor.execution.runtime.ExecutionSpec;
import io.aria.conductor.execution.runtime.LaunchProfile;
import io.aria.conductor.execution.runtime.PreparedEnvironment;
import io.aria.conductor.execution.runtime.RuntimeHandle;
import io.aria.conductor.execution.runtime.SecretBundle;
import io.aria.conductor.execution.runtime.sandbox.SandboxLifecycle;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link OpenCodeCoreAdapter#open}: the served core is launched as a
 * separate hop, and the execd proxy answers 502 until {@code serve} listens, so
 * the session open is gated on {@code GET /global/health} becoming healthy
 * (observed: {@code POST /session returned status 502} 64ms after launch).
 */
class OpenCodeCoreAdapterTest {

    private static final UUID RUN_ID = UUID.randomUUID();
    private static final UUID AGENT_ID = UUID.randomUUID();

    @Test
    void open_waitsForServeHealth_thenOpensSession() throws IOException {
        AtomicInteger healthHits = new AtomicInteger();
        HttpServer server = serveHealthyAfter(2, healthHits);
        try {
            OpenCodeCoreAdapter adapter = adapter(server, Duration.ofSeconds(2), Duration.ofMillis(25));

            var session = adapter.open(handle(server), spec(), new SecretBundle(null, Map.of()));

            assertThat(session.sessionId()).isEqualTo("s1");
            // Two failing probes preceded the healthy one: the open was gated on readiness.
            assertThat(healthHits.get()).isGreaterThanOrEqualTo(3);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void open_throwsWhenServeNeverBecomesHealthy() throws IOException {
        AtomicInteger healthHits = new AtomicInteger();
        HttpServer server = serveHealthyAfter(Integer.MAX_VALUE, healthHits);
        try {
            OpenCodeCoreAdapter adapter = adapter(server, Duration.ofMillis(300), Duration.ofMillis(25));

            assertThatThrownBy(() -> adapter.open(handle(server), spec(), new SecretBundle(null, Map.of())))
                    .isInstanceOf(TaskExecutionException.class)
                    .hasMessageContaining("did not become ready")
                    .satisfies(e -> assertThat(((TaskExecutionException) e).cause())
                            .isEqualTo(TaskExecutionException.Cause.SANDBOX_UNAVAILABLE));
        } finally {
            server.stop(0);
        }
    }

    // ---- launch profile: sandbox XDG roots -------------------------------------------

    @Test
    void launchProfile_sandboxXdgRootsPointIntoTheSandboxControlTree() throws IOException {
        Path hostConfig = Files.createTempDirectory("oc-config");
        UUID runId = UUID.randomUUID();
        RunMcpWiring runMcp = mock(RunMcpWiring.class);
        when(runMcp.forRun(any(ExecutionSpec.class), any(PreparedEnvironment.class)))
                .thenReturn(Optional.empty());
        OpenCodeCoreAdapter adapter = new OpenCodeCoreAdapter(
                new OpenCodeCoreAdapter.OpenCodeProfile("opencode", List.of(), Map.of(), "1.14.31", "efficient"),
                runMcp, Duration.ofSeconds(2), Duration.ofMillis(25));
        PreparedEnvironment environment = new PreparedEnvironment(runId, ExecutionMode.SANDBOX, "env-1",
                "/workspace", hostConfig.toString(), URI.create("http://127.0.0.1:40369/proxy/4096"));
        ExecutionSpec spec = new ExecutionSpec(runId, UUID.randomUUID(), "opencode", ExecutionMode.SANDBOX,
                new AgentExecutionSettings("opencode", ExecutionMode.SANDBOX, null, null, null),
                null, null, Instant.now());

        LaunchProfile profile = adapter.launchProfile(spec, environment, new SecretBundle(null, Map.of()));

        // The XDG roots must be SANDBOX paths inside the run control tree (where
        // the governed configuration is uploaded), never host paths: a host path
        // (D:\... on Windows) is not absolute on Linux and would silently disable
        // the governed permission policy inside the sandbox.
        String controlRoot = SandboxLifecycle.DEFAULT_CONTROL_ROOT + "/" + runId;
        assertThat(profile.env())
                .containsEntry("XDG_CONFIG_HOME", controlRoot + "/config")
                .containsEntry("XDG_DATA_HOME", controlRoot + "/data")
                .containsEntry("XDG_CACHE_HOME", controlRoot + "/cache");
        // The governed configuration is still materialized on the host staging root.
        assertThat(Files.readString(OpenCodeCoreAdapter.governedConfigurationFile(environment)))
                .contains("\"*\": \"deny\"");
    }

    // ---- helpers ------------------------------------------------------------------

    private static OpenCodeCoreAdapter adapter(HttpServer server, Duration budget, Duration poll) {
        RunMcpWiring runMcp = mock(RunMcpWiring.class);
        when(runMcp.forRun(any(ExecutionSpec.class), any(PreparedEnvironment.class)))
                .thenReturn(Optional.empty());
        OpenCodeCoreAdapter.OpenCodeProfile profile = new OpenCodeCoreAdapter.OpenCodeProfile(
                "opencode", List.of(), Map.of(), "1.14.31", "efficient");
        return new OpenCodeCoreAdapter(profile, runMcp, budget, poll);
    }

    private static ExecutionSpec spec() {
        return new ExecutionSpec(RUN_ID, AGENT_ID, "opencode", ExecutionMode.SANDBOX,
                new AgentExecutionSettings("opencode", ExecutionMode.SANDBOX, null, null, null),
                null, null, Instant.now());
    }

    private static RuntimeHandle handle(HttpServer server) {
        return new RuntimeHandle(RUN_ID, ExecutionMode.SANDBOX, "env-1", "id-1",
                URI.create("http://127.0.0.1:" + server.getAddress().getPort()));
    }

    /** Loopback server whose health answers 502 until {@code failures} probes passed. */
    private static HttpServer serveHealthyAfter(int failures, AtomicInteger healthHits) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/global/health", exchange -> {
            healthHits.incrementAndGet();
            if (failures > 0 && healthHits.get() <= failures) {
                exchange.sendResponseHeaders(502, -1);
                exchange.close();
                return;
            }
            byte[] body = "{\"healthy\":true}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.createContext("/session", exchange -> {
            byte[] body = "{\"id\":\"s1\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        return server;
    }
}
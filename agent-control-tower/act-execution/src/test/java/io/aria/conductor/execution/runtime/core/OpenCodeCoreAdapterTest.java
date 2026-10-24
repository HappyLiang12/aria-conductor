package io.aria.conductor.execution.runtime.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.junit.jupiter.api.io.TempDir;

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
import java.util.ArrayList;
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
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path hostConfig;

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

    /**
     * The serve-ready wait never outlives the run: a launch whose run deadline is
     * about to elapse waits the remaining run budget, not the full serve budget.
     */
    @Test
    void open_boundsTheServeWaitByTheRunDeadline() throws IOException {
        AtomicInteger healthHits = new AtomicInteger();
        HttpServer server = serveHealthyAfter(Integer.MAX_VALUE, healthHits);
        try {
            OpenCodeCoreAdapter adapter = adapter(server, Duration.ofSeconds(30), Duration.ofMillis(25));
            ExecutionSpec expiring = spec(Instant.now().plusMillis(200));
            Instant started = Instant.now();

            assertThatThrownBy(() -> adapter.open(handle(server), expiring, new SecretBundle(null, Map.of())))
                    .isInstanceOf(TaskExecutionException.class)
                    .hasMessageContaining("did not become ready");

            assertThat(Duration.between(started, Instant.now()))
                    .as("the wait must end with the run deadline, not the 30s serve budget")
                    .isLessThan(Duration.ofSeconds(10));
        } finally {
            server.stop(0);
        }
    }

    // ---- launch profile: sandbox XDG roots -------------------------------------------

    @Test
    void launchProfile_governedConfigurationPointsAtTheResolvedProvider() throws IOException {
        UUID runId = UUID.randomUUID();
        OpenCodeCoreAdapter.ModelProvider provider = new OpenCodeCoreAdapter.ModelProvider(
                "acme-gateway", "acme-flash", "https://gw.example.com/v1");
        OpenCodeCoreAdapter adapter = new OpenCodeCoreAdapter(
                new OpenCodeCoreAdapter.OpenCodeProfile("opencode", List.of(), Map.of(), "1.18.15", "efficient"),
                runMcp(), () -> provider);
        PreparedEnvironment environment = new PreparedEnvironment(runId, ExecutionMode.SANDBOX, "env-1",
                "/workspace", hostConfig.toString(), URI.create("http://127.0.0.1:40369/proxy/4096"));
        ExecutionSpec spec = new ExecutionSpec(runId, UUID.randomUUID(), "opencode", ExecutionMode.SANDBOX,
                new AgentExecutionSettings("opencode", ExecutionMode.SANDBOX, null, null, null),
                null, null, Instant.now().plusSeconds(30));

        adapter.launchProfile(spec, environment, new SecretBundle(null, Map.of()));

        // The served model comes from the operator's provider block, never from
        // opencode's own default (whose free tier refuses this consumer); the
        // credential stays an env reference, so no secret is written into the file.
        // The block is keyed under the governed prefix, never under the operator's
        // bare id, because opencode bundles provider ids and serves those itself.
        String document = Files.readString(OpenCodeCoreAdapter.governedConfigurationFile(environment));
        assertThat(document).isEqualTo(OpenCodeCoreAdapter.governedConfigurationJson(provider));
        assertThat(document)
                .contains("\"model\": \"aria-acme-gateway/acme-flash\"")
                .contains("\"aria-acme-gateway\": {")
                .contains("\"npm\": \"@ai-sdk/openai-compatible\"")
                .contains("\"apiKey\": \"{env:LLM_API_KEY}\"")
                .contains("\"baseURL\": \"https://gw.example.com/v1\"")
                .contains("\"acme-flash\": {}");
        // The permission policy is unchanged: pointing at a provider never widens it.
        assertThat(document)
                .contains("\"*\": \"deny\"")
                .contains("\"bash\": \"deny\"")
                .doesNotContain("\"*\": \"allow\"");
    }

    @Test
    void modelProviderRefusesValuesThatCouldBreakTheGovernedDocument() {
        assertThatThrownBy(() -> new OpenCodeCoreAdapter.ModelProvider("Acme", "m", "https://x/v1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("lowercase");
        assertThatThrownBy(() -> new OpenCodeCoreAdapter.ModelProvider("acme", "a/b", "https://x/v1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not carry a '/'");
        assertThatThrownBy(() -> new OpenCodeCoreAdapter.ModelProvider("acme", "m", "ftp://x/v1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("http(s)");
        assertThatThrownBy(() -> new OpenCodeCoreAdapter.ModelProvider("acme", "m\"x", "https://x/v1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("quotes");
        assertThat(OpenCodeCoreAdapter.ModelProvider.deepseekFallback().providerId()).isEqualTo("deepseek");
    }

    // ---- governed provider document key: never a bundled opencode id -------------------

    /**
     * The governed document's provider key is never a provider id opencode bundles.
     * Observed against the pinned opencode 1.18.15 HOST launch: a document keyed
     * {@code deepseek} made the first prompt die with
     * {@code ProviderModelNotFoundError: Model not found: deepseek/deepseek-v4-flash.
     * Did you mean: deepseek-flash, deepseek-v4-pro?} (the HTTP caller only sees
     * {@code 500 {"name":"UnknownError"}}), because the server answers a bundled id
     * from its own catalog and ignores the model this document declares. Every name
     * a stock install auto-creates and activates ({@code DeepSeek}, {@code OpenAI},
     * {@code Anthropic}) sanitizes to such an id, so the rule is checked for all of
     * them: the key is the {@code aria-} prefixed id, the {@code model} member names
     * that same key, and the operator's own model id stays exactly as declared.
     */
    @Test
    void governedDocumentNeverUsesAProviderKeyOpencodeBundles() throws IOException {
        for (String providerId : List.of("deepseek", "openai", "anthropic")) {
            OpenCodeCoreAdapter.ModelProvider colliding = new OpenCodeCoreAdapter.ModelProvider(
                    providerId, "deepseek-v4-flash", "https://api.deepseek.com/v1");

            JsonNode document = JSON.readTree(OpenCodeCoreAdapter.governedConfigurationJson(colliding));

            assertThat(memberNames(document.path("provider"))).containsExactly("aria-" + providerId);
            assertThat(document.path("model").asText()).isEqualTo("aria-" + providerId + "/deepseek-v4-flash");
            JsonNode provider = document.path("provider").path("aria-" + providerId);
            assertThat(provider.path("npm").asText()).isEqualTo("@ai-sdk/openai-compatible");
            assertThat(provider.path("options").path("apiKey").asText()).isEqualTo("{env:LLM_API_KEY}");
            assertThat(provider.path("options").path("baseURL").asText()).isEqualTo("https://api.deepseek.com/v1");
            assertThat(memberNames(provider.path("models"))).containsExactly("deepseek-v4-flash");
            // The permission policy is not part of the key rule and must not drift.
            assertThat(memberNames(document.path("permission"))).isEqualTo(List.of("*", "read", "list",
                    "glob", "grep", "webfetch", "websearch", "edit", "write", "patch", "bash", "task",
                    "question", "external_directory", "aria-conductor*"));
            assertThat(document.path("permission").path("*").asText()).isEqualTo("deny");
        }
    }

    /**
     * The documented fallback provider stays reachable and keeps serving the same
     * model: only its document key gains the prefix.
     */
    @Test
    void governedDocumentOfTheFallbackProviderKeepsItsModelAndPrefixesOnlyItsKey() throws IOException {
        JsonNode document = JSON.readTree(OpenCodeCoreAdapter.governedConfigurationJson());

        assertThat(memberNames(document.path("provider"))).containsExactly("aria-deepseek");
        assertThat(document.path("model").asText()).isEqualTo("aria-deepseek/deepseek-chat");
        JsonNode provider = document.path("provider").path("aria-deepseek");
        assertThat(provider.path("options").path("baseURL").asText()).isEqualTo("https://api.deepseek.com/v1");
        assertThat(memberNames(provider.path("models"))).containsExactly("deepseek-chat");
    }

    /** An operator id that already carries the prefix is not prefixed twice. */
    @Test
    void governedProviderKeyIsNeverDoublePrefixed() throws IOException {
        OpenCodeCoreAdapter.ModelProvider prefixed = new OpenCodeCoreAdapter.ModelProvider(
                "aria-deepseek", "deepseek-v4-flash", "https://api.deepseek.com/v1");

        JsonNode document = JSON.readTree(OpenCodeCoreAdapter.governedConfigurationJson(prefixed));

        assertThat(memberNames(document.path("provider"))).containsExactly("aria-deepseek");
        assertThat(document.path("model").asText()).isEqualTo("aria-deepseek/deepseek-v4-flash");
    }

    /** The member names of an object node, in document order. */
    private static List<String> memberNames(JsonNode object) {
        List<String> names = new ArrayList<>();
        object.fieldNames().forEachRemaining(names::add);
        return names;
    }

    @Test
    void launchProfile_sandboxXdgRootsPointIntoTheSandboxControlTree() throws IOException {
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
                null, null, Instant.now().plusSeconds(30));

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

    /** The wiring of a run without platform MCP: no endpoint is materialised. */
    private static RunMcpWiring runMcp() {
        RunMcpWiring runMcp = mock(RunMcpWiring.class);
        when(runMcp.forRun(any(ExecutionSpec.class), any(PreparedEnvironment.class)))
                .thenReturn(Optional.empty());
        return runMcp;
    }

    private static ExecutionSpec spec() {
        return spec(Instant.now().plusSeconds(30));
    }

    private static ExecutionSpec spec(Instant deadline) {
        return new ExecutionSpec(RUN_ID, AGENT_ID, "opencode", ExecutionMode.SANDBOX,
                new AgentExecutionSettings("opencode", ExecutionMode.SANDBOX, null, null, null),
                null, null, deadline);
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

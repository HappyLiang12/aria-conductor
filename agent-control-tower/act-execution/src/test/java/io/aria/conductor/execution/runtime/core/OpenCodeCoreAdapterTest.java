package io.aria.conductor.execution.runtime.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.sun.net.httpserver.HttpServer;
import io.aria.conductor.common.runtime.AgentExecutionSettings;
import io.aria.conductor.common.runtime.ExecutionMode;
import io.aria.conductor.execution.adk.TaskExecutionException;
import io.aria.conductor.execution.mcp.RunMcpWiring;
import io.aria.conductor.execution.runtime.ExecutionSpec;
import io.aria.conductor.execution.runtime.LaunchProfile;
import io.aria.conductor.execution.runtime.LaunchRefusal;
import io.aria.conductor.execution.runtime.PreparedEnvironment;
import io.aria.conductor.execution.runtime.RuntimeHandle;
import io.aria.conductor.execution.runtime.SecretBundle;
import io.aria.conductor.execution.runtime.sandbox.SandboxLifecycle;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

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
import static org.assertj.core.api.Assertions.catchThrowableOfType;
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

    // ---- the launch refusal, consulted by the readiness wait -----------------------

    /** The refusal the Lead reproduced by hand inside the container its run leaked. */
    private static final String LAUNCHER_REFUSAL =
            "supervisor.fault (LaunchFault): Entrypoint name is not allowlisted by this image: "
                    + "C:\\Users\\operator\\opencode.exe [exit 78]";

    /**
     * The production shape the Lead measured on a real container: the launcher refuses
     * before it spawns anything, and it says so only <em>after</em> the launch's own verdict
     * window had already closed over a silent stream. What runs next is this wait, and the
     * defect is that it never looks at the verdict again: the run burns its whole budget and
     * fails with a timeout that describes a symptom, while the image's sentence — the only
     * part an operator can act on — was already known.
     */
    @Test
    void open_stopsTheServeWaitOnAPreSpawnRefusalThatLandsAfterTheVerdictWindow() throws IOException {
        AtomicInteger healthHits = new AtomicInteger();
        HttpServer server = serveHealthyAfter(Integer.MAX_VALUE, healthHits);
        try {
            OpenCodeCoreAdapter adapter = adapter(server, Duration.ofSeconds(30), Duration.ofMillis(25));
            ScriptedRefusal refusal = new ScriptedRefusal(2, LAUNCHER_REFUSAL);

            TaskExecutionException failure = catchThrowableOfType(
                    () -> adapter.open(handle(server), spec(Instant.now().plusSeconds(60)),
                            new SecretBundle(null, Map.of()), refusal),
                    TaskExecutionException.class);

            assertThat(failure).as("the wait must end on the verdict the launcher did report").isNotNull();
            assertThat(failure.cause()).isEqualTo(TaskExecutionException.Cause.SANDBOX_UNAVAILABLE);
            assertThat(failure.getMessage())
                    .contains("Entrypoint name is not allowlisted by this image: "
                            + "C:\\Users\\operator\\opencode.exe")
                    .contains("supervisor.fault")
                    .doesNotContain("did not become ready");
            assertThat(refusal.lookCount())
                    .as("the verdict is consulted on the polls the wait already performs")
                    .isEqualTo(3);
            assertThat(healthHits.get())
                    .as("the wait ended on the third probe, not on the 30 s budget")
                    .isLessThanOrEqualTo(5);
        } finally {
            server.stop(0);
        }
    }

    /**
     * The one line the Lead needs to be able to measure: when a wait ends on the verdict
     * rather than on its budget, the log says so once, with what it ended on.
     */
    @Test
    void open_logsOnceThatTheReadinessWaitEndedOnTheRefusal() throws IOException {
        ListAppender<ILoggingEvent> logs = captureAdapterLogs();
        AtomicInteger healthHits = new AtomicInteger();
        HttpServer server = serveHealthyAfter(Integer.MAX_VALUE, healthHits);
        try {
            OpenCodeCoreAdapter adapter = adapter(server, Duration.ofSeconds(30), Duration.ofMillis(25));

            assertThatThrownBy(() -> adapter.open(handle(server), spec(Instant.now().plusSeconds(60)),
                    new SecretBundle(null, Map.of()), new ScriptedRefusal(0, LAUNCHER_REFUSAL)))
                    .isInstanceOf(TaskExecutionException.class);

            assertThat(logs.list)
                    .filteredOn(event -> event.getLevel() == Level.INFO)
                    .filteredOn(event -> event.getFormattedMessage()
                            .contains("ended after") && event.getFormattedMessage()
                            .contains("pre-spawn refusal"))
                    .as("the readiness wait reports that it stopped on the verdict")
                    .hasSize(1);
        } finally {
            server.stop(0);
            ((Logger) LoggerFactory.getLogger(OpenCodeCoreAdapter.class)).detachAppender(logs);
        }
    }

    /**
     * Lane F's latency guarantee, restated at the readiness loop: a core that answers
     * healthy is opened on the probe that found it healthy and the refusal source is never
     * consulted on that path, and a core that needs a few more probes pays exactly the
     * probes its script says — no window, no tax, nothing beyond what it paid before.
     */
    @Test
    void open_healthyCorePaysNothingForTheRefusalSource() throws IOException {
        AtomicInteger immediateHits = new AtomicInteger();
        HttpServer healthy = serveHealthyAfter(0, immediateHits);
        ScriptedRefusal refusal = new ScriptedRefusal(Integer.MAX_VALUE, LAUNCHER_REFUSAL);
        long started;
        try {
            OpenCodeCoreAdapter adapter = adapter(healthy, Duration.ofSeconds(2), Duration.ofMillis(25));

            started = System.nanoTime();
            var session = adapter.open(handle(healthy), spec(), new SecretBundle(null, Map.of()), refusal);

            assertThat(session.sessionId()).isEqualTo("s1");
            assertThat(refusal.lookCount())
                    .as("a core that is healthy on the first probe is never asked whether it refused")
                    .isZero();
            assertThat(immediateHits.get()).isEqualTo(1);
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(1));
        } finally {
            healthy.stop(0);
        }
        AtomicInteger slowHits = new AtomicInteger();
        HttpServer slowToListen = serveHealthyAfter(2, slowHits);
        try {
            OpenCodeCoreAdapter adapter = adapter(slowToListen, Duration.ofSeconds(2), Duration.ofMillis(25));

            var session = adapter.open(handle(slowToListen), spec(), new SecretBundle(null, Map.of()), refusal);

            assertThat(session.sessionId()).isEqualTo("s1");
            assertThat(slowHits.get())
                    .as("two 502s and the healthy probe that followed, which is the script itself")
                    .isEqualTo(3);
        } finally {
            slowToListen.stop(0);
        }
    }

    /**
     * Fail-closed, restated at the readiness loop: while nothing is <em>proven</em> — a
     * silent launcher, an unreadable stream, a launcher that reported its spawn and only
     * then faulted — the wait runs its budget and today's error stands. A slow but live core
     * must still be allowed to come up, which is exactly what an unproven verdict keeps
     * possible here.
     */
    @Test
    void open_unprovenRefusalRunsTheWholeBudgetAndKeepsTodaysError() throws IOException {
        AtomicInteger healthHits = new AtomicInteger();
        HttpServer server = serveHealthyAfter(Integer.MAX_VALUE, healthHits);
        ScriptedRefusal refusal = new ScriptedRefusal(Integer.MAX_VALUE, LAUNCHER_REFUSAL);
        try {
            OpenCodeCoreAdapter adapter = adapter(server, Duration.ofMillis(300), Duration.ofMillis(20));

            assertThatThrownBy(() -> adapter.open(handle(server), spec(Instant.now().plusSeconds(30)),
                    new SecretBundle(null, Map.of()), refusal))
                    .isInstanceOf(TaskExecutionException.class)
                    .hasMessageContaining("did not become ready within");

            assertThat(refusal.lookCount())
                    .as("the wait did ask the verdict, and an unproven answer changed nothing: the run "
                            + "of a slow-but-live core keeps its whole budget")
                    .isGreaterThanOrEqualTo(1);
            assertThat(healthHits.get()).isGreaterThanOrEqualTo(1);
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

    /**
     * A launch refusal source with the shape of the real one: it answers {@code null} —
     * nothing proven — for its first {@code looksBeforeLanding} consultations and the
     * launcher's sentence from then on, counting every consultation so a test can see
     * exactly how often the wait looked.
     */
    private static final class ScriptedRefusal implements LaunchRefusal {

        private final int looksBeforeLanding;
        private final String reason;
        private final AtomicInteger looks = new AtomicInteger();

        ScriptedRefusal(int looksBeforeLanding, String reason) {
            this.looksBeforeLanding = looksBeforeLanding;
            this.reason = reason;
        }

        @Override
        public String reason() {
            return looks.incrementAndGet() > looksBeforeLanding ? reason : null;
        }

        int lookCount() {
            return looks.get();
        }
    }

    /** Captures what {@link OpenCodeCoreAdapter} logs for the duration of one test. */
    private static ListAppender<ILoggingEvent> captureAdapterLogs() {
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        ((Logger) LoggerFactory.getLogger(OpenCodeCoreAdapter.class)).addAppender(appender);
        return appender;
    }

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

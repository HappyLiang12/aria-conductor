package io.aria.conductor.execution.adk.qoder;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Black-box tests for {@link QoderBridgeClient} against the frozen C0.2 wire contract, backed by
 * the JDK built-in {@link com.sun.net.httpserver.HttpServer} (no WireMock). The in-sandbox bridge
 * server is the wire authority: {@code agent-control-tower/qoder-sandbox/bridge/src/server.ts}.
 *
 * <p>All tokens are synthetic (never real credentials).
 */
class QoderBridgeClientTest {

    private static final String TOKEN = "test-bridge-token";
    private static final String SESSION_ID = "bs-1111";
    private static final ObjectMapper JSON = new ObjectMapper();

    private HttpServer server;
    private ExecutorService serverExecutor;
    private String baseUrl;
    private QoderBridgeClient client;

    /** Route table keyed by {@code "METHOD /path"}. */
    private final Map<String, Route> routes = new ConcurrentHashMap<>();
    private final List<RecordedRequest> requests = Collections.synchronizedList(new ArrayList<>());

    @FunctionalInterface
    private interface Route {
        void handle(HttpExchange exchange) throws IOException;
    }

    private record RecordedRequest(String method, String path, String query,
                                   Map<String, String> headers, String body) { }

    @BeforeEach
    void startStub() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        serverExecutor = Executors.newCachedThreadPool();
        server.setExecutor(serverExecutor);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            Map<String, String> headers = new LinkedHashMap<>();
            exchange.getRequestHeaders().forEach((name, values) ->
                    headers.put(name.toLowerCase(Locale.ROOT), values.isEmpty() ? "" : values.get(0)));
            byte[] body = exchange.getRequestBody().readAllBytes();
            requests.add(new RecordedRequest(exchange.getRequestMethod(), path,
                    exchange.getRequestURI().getRawQuery(), headers, new String(body, StandardCharsets.UTF_8)));
            Route route = routes.get(exchange.getRequestMethod() + " " + path);
            if (route == null) {
                sendJson(exchange, 404, "{\"error\":\"NOT_FOUND\"}");
                return;
            }
            route.handle(exchange);
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        client = new QoderBridgeClient(baseUrl, TOKEN);
    }

    @AfterEach
    void stopStub() {
        if (client != null) {
            client.close();
        }
        if (server != null) {
            server.stop(0);
        }
        if (serverExecutor != null) {
            serverExecutor.shutdownNow();
        }
    }

    // ------------------------------------------------------------------------------------
    // health
    // ------------------------------------------------------------------------------------

    @Test
    void health_parsesStatusAndCliVersion_withoutAuthHeader() {
        on("GET /health", exchange -> sendJson(exchange, 200, "{\"status\":\"ok\",\"cliVersion\":\"1.1.41\"}"));

        QoderBridgeClient.Health health = client.health();

        assertThat(health.status()).isEqualTo("ok");
        assertThat(health.cliVersion()).isEqualTo("1.1.41");
        RecordedRequest request = onlyRequest();
        assertThat(request.method()).isEqualTo("GET");
        assertThat(request.path()).isEqualTo("/health");
        assertThat(request.headers())
                .as("/health is the credential-free liveness route")
                .doesNotContainKey("authorization");
    }

    @Test
    void health_mapsAnUnreachableBridgeToUnreachable() {
        try (QoderBridgeClient offline = new QoderBridgeClient("http://127.0.0.1:1", TOKEN, Duration.ofSeconds(2))) {
            assertThatThrownBy(offline::health)
                    .isInstanceOf(QoderBridgeException.class)
                    .satisfies(e -> assertThat(causeOf(e)).isEqualTo(QoderBridgeException.Cause.UNREACHABLE));
        }
    }

    // ------------------------------------------------------------------------------------
    // POST /sessions
    // ------------------------------------------------------------------------------------

    @Test
    void createSession_postsTheC02Body_andReturnsTheBridgeSessionId() {
        on("POST /sessions", exchange -> sendJson(exchange, 201, "{\"bridgeSessionId\":\"bs-1\"}"));

        String sessionId = client.createSession(new QoderBridgeClient.CreateSessionRequest(
                "run-42", "agent-7", "/workspace", "efficient", 60L,
                List.of(new QoderBridgeClient.McpServer("aria", "http://host.docker.internal:8080/api/v1/mcp",
                        List.of(new QoderBridgeClient.Header("Authorization", "Bearer test-worker-token"))))));

        assertThat(sessionId).isEqualTo("bs-1");
        RecordedRequest request = onlyRequest();
        assertThat(request.method()).isEqualTo("POST");
        assertThat(request.path()).isEqualTo("/sessions");
        assertThat(request.headers()).containsEntry("authorization", "Bearer " + TOKEN);
        assertThat(request.headers().get("content-type")).contains("application/json");
        assertThat(json(request.body())).isEqualTo(json("""
                {
                  "runId": "run-42",
                  "agentId": "agent-7",
                  "cwd": "/workspace",
                  "model": "efficient",
                  "deadlineSeconds": 60,
                  "mcpServers": [
                    {
                      "name": "aria",
                      "url": "http://host.docker.internal:8080/api/v1/mcp",
                      "headers": [ { "name": "Authorization", "value": "Bearer test-worker-token" } ]
                    }
                  ]
                }
                """));
    }

    @Test
    void createSession_maps401ToUnauthorized() {
        on("POST /sessions", exchange -> sendJson(exchange, 401, "{\"error\":\"UNAUTHORIZED\"}"));

        assertThatThrownBy(() -> client.createSession(minimalCreateRequest()))
                .isInstanceOf(QoderBridgeException.class)
                .hasMessageContaining("401")
                .satisfies(e -> assertThat(causeOf(e)).isEqualTo(QoderBridgeException.Cause.UNAUTHORIZED));
    }

    @Test
    void createSession_maps400ToInvalidRequest() {
        on("POST /sessions", exchange -> sendJson(exchange, 400, "{\"error\":\"INVALID_REQUEST\"}"));

        assertThatThrownBy(() -> client.createSession(minimalCreateRequest()))
                .isInstanceOf(QoderBridgeException.class)
                .satisfies(e -> assertThat(causeOf(e)).isEqualTo(QoderBridgeException.Cause.INVALID_REQUEST));
    }

    @Test
    void createSession_mapsRequestTimeoutToTimeout() {
        on("POST /sessions", exchange -> {
            pause(2000);
            sendJson(exchange, 201, "{\"bridgeSessionId\":\"bs-1\"}");
        });

        try (QoderBridgeClient shortTimeout = new QoderBridgeClient(baseUrl, TOKEN, Duration.ofMillis(250))) {
            assertThatThrownBy(() -> shortTimeout.createSession(minimalCreateRequest()))
                    .isInstanceOf(QoderBridgeException.class)
                    .satisfies(e -> assertThat(causeOf(e)).isEqualTo(QoderBridgeException.Cause.TIMEOUT));
        }
    }

    // ------------------------------------------------------------------------------------
    // POST /sessions/{id}/prompt
    // ------------------------------------------------------------------------------------

    @Test
    void prompt_postsText_andAccepts202() {
        on("POST /sessions/" + SESSION_ID + "/prompt",
                exchange -> sendJson(exchange, 202, "{\"accepted\":true}"));

        client.prompt(SESSION_ID, "Reply with exactly: ok");

        RecordedRequest request = onlyRequest();
        assertThat(request.method()).isEqualTo("POST");
        assertThat(request.path()).isEqualTo("/sessions/" + SESSION_ID + "/prompt");
        assertThat(request.headers()).containsEntry("authorization", "Bearer " + TOKEN);
        assertThat(json(request.body())).isEqualTo(json("{\"text\":\"Reply with exactly: ok\"}"));
    }

    @Test
    void prompt_mapsUnknownSession404ToNotFound() {
        on("POST /sessions/bs-missing/prompt", exchange -> sendJson(exchange, 404, "{\"error\":\"NOT_FOUND\"}"));

        assertThatThrownBy(() -> client.prompt("bs-missing", "hello"))
                .isInstanceOf(QoderBridgeException.class)
                .satisfies(e -> assertThat(causeOf(e)).isEqualTo(QoderBridgeException.Cause.NOT_FOUND));
    }

    @Test
    void prompt_maps409SessionEndedToTypedConflict() {
        on("POST /sessions/" + SESSION_ID + "/prompt",
                exchange -> sendJson(exchange, 409, "{\"error\":\"SESSION_ENDED\"}"));

        assertThatThrownBy(() -> client.prompt(SESSION_ID, "hello"))
                .isInstanceOf(QoderBridgeException.class)
                .hasMessageContaining("SESSION_ENDED")
                .satisfies(e -> assertThat(causeOf(e)).isEqualTo(QoderBridgeException.Cause.SESSION_ENDED));
    }

    @Test
    void errorMapping_coversTheRemainingC02Statuses() {
        on("POST /sessions/" + SESSION_ID + "/prompt",
                exchange -> sendJson(exchange, 413, "{\"error\":\"PAYLOAD_TOO_LARGE\"}"));
        on("POST /sessions/" + SESSION_ID + "/cancel",
                exchange -> sendJson(exchange, 409, "{\"error\":\"SOMETHING_NEW\"}"));

        assertThatThrownBy(() -> client.prompt(SESSION_ID, "hello"))
                .isInstanceOf(QoderBridgeException.class)
                .satisfies(e -> assertThat(causeOf(e)).isEqualTo(QoderBridgeException.Cause.PAYLOAD_TOO_LARGE));
        assertThatThrownBy(() -> client.cancel(SESSION_ID))
                .isInstanceOf(QoderBridgeException.class)
                .hasMessageContaining("SOMETHING_NEW")
                .satisfies(e -> assertThat(causeOf(e)).isEqualTo(QoderBridgeException.Cause.CONFLICT));
    }

    @Test
    void prompt_isNotRetried_whenTheFirstMutationFails() {
        AtomicInteger calls = new AtomicInteger();
        on("POST /sessions/" + SESSION_ID + "/prompt", exchange -> {
            if (calls.incrementAndGet() == 1) {
                sendJson(exchange, 500, "{\"error\":\"INTERNAL_ERROR\"}");
            } else {
                sendJson(exchange, 202, "{\"accepted\":true}");
            }
        });

        assertThatThrownBy(() -> client.prompt(SESSION_ID, "hello"))
                .isInstanceOf(QoderBridgeException.class)
                .satisfies(e -> assertThat(causeOf(e)).isEqualTo(QoderBridgeException.Cause.PROVIDER_ERROR));

        assertThat(calls)
                .as("a prompt is a mutation: it must never be retried after a failure")
                .hasValue(1);
    }

    // ------------------------------------------------------------------------------------
    // GET /sessions/{id}/events (SSE)
    // ------------------------------------------------------------------------------------

    @Test
    void events_streamsDataLines_andTracksTheLastSequence() {
        on("GET /sessions/" + SESSION_ID + "/events", exchange -> sendSse(exchange,
                "{\"sequence\":1,\"type\":\"session_started\",\"model\":\"efficient\"}",
                "{\"sequence\":2,\"type\":\"agent_message\",\"text\":\"ok\"}",
                "{\"sequence\":3,\"type\":\"completed\",\"stopReason\":\"end_turn\"}"));

        List<QoderBridgeClient.BridgeEvent> events = new ArrayList<>();
        QoderBridgeClient.EventStream stream = client.openEventStream(SESSION_ID);
        stream.read(events::add);

        assertThat(events).extracting(QoderBridgeClient.BridgeEvent::sequence).containsExactly(1L, 2L, 3L);
        assertThat(events).extracting(QoderBridgeClient.BridgeEvent::type)
                .containsExactly("session_started", "agent_message", "completed");
        assertThat(events.get(0).payload().path("model").asText()).isEqualTo("efficient");
        assertThat(events.get(1).payload().path("text").asText()).isEqualTo("ok");
        assertThat(stream.lastSequence()).isEqualTo(3);

        RecordedRequest request = onlyRequest();
        assertThat(request.method()).isEqualTo("GET");
        assertThat(request.path()).isEqualTo("/sessions/" + SESSION_ID + "/events");
        assertThat(request.query()).isEqualTo("after=0");
        assertThat(request.headers()).containsEntry("authorization", "Bearer " + TOKEN);
        assertThat(request.headers().get("accept")).contains("text/event-stream");
    }

    @Test
    void events_resumeAfterStreamEnd_sendsTheLastSequenceAsAfter() {
        AtomicInteger calls = new AtomicInteger();
        on("GET /sessions/" + SESSION_ID + "/events", exchange -> {
            if (calls.incrementAndGet() == 1) {
                sendSse(exchange,
                        "{\"sequence\":1,\"type\":\"session_started\",\"model\":\"efficient\"}",
                        "{\"sequence\":2,\"type\":\"agent_message\",\"text\":\"first\"}");
            } else {
                sendSse(exchange, "{\"sequence\":3,\"type\":\"completed\",\"stopReason\":\"end_turn\"}");
            }
        });

        QoderBridgeClient.EventStream stream = client.openEventStream(SESSION_ID);
        List<QoderBridgeClient.BridgeEvent> first = new ArrayList<>();
        stream.read(first::add);
        assertThat(first).hasSize(2);
        assertThat(stream.lastSequence()).isEqualTo(2);

        List<QoderBridgeClient.BridgeEvent> second = new ArrayList<>();
        stream.read(second::add);

        assertThat(second).extracting(QoderBridgeClient.BridgeEvent::sequence).containsExactly(3L);
        assertThat(stream.lastSequence()).isEqualTo(3);
        assertThat(requests).hasSize(2);
        assertThat(requests.get(0).query()).isEqualTo("after=0");
        assertThat(requests.get(1).query())
                .as("a reconnect must resume from the last sequence, never from 0")
                .isEqualTo("after=2");
    }

    @Test
    void events_replayGap_mapsToTypedConflict_andKeepsTheCursor() {
        AtomicInteger calls = new AtomicInteger();
        on("GET /sessions/" + SESSION_ID + "/events", exchange -> {
            if (calls.incrementAndGet() == 1) {
                sendSse(exchange, "{\"sequence\":1,\"type\":\"session_started\",\"model\":\"efficient\"}");
            } else {
                sendJson(exchange, 409, "{\"error\":\"REPLAY_GAP\"}");
            }
        });

        QoderBridgeClient.EventStream stream = client.openEventStream(SESSION_ID);
        stream.read(event -> { });
        assertThat(stream.lastSequence()).isEqualTo(1);

        assertThatThrownBy(() -> stream.read(event -> { }))
                .isInstanceOf(QoderBridgeException.class)
                .hasMessageContaining("REPLAY_GAP")
                .satisfies(e -> assertThat(causeOf(e)).isEqualTo(QoderBridgeException.Cause.REPLAY_GAP));

        assertThat(stream.lastSequence())
                .as("a replay gap must surface as an error, never silently restart the stream from 0")
                .isEqualTo(1);
        assertThat(requests.get(1).query()).isEqualTo("after=1");
    }

    @Test
    void events_malformedEventJson_mapsToProviderError() {
        on("GET /sessions/" + SESSION_ID + "/events", exchange -> sendSse(exchange, "{not json"));

        QoderBridgeClient.EventStream stream = client.openEventStream(SESSION_ID);

        assertThatThrownBy(() -> stream.read(event -> { }))
                .isInstanceOf(QoderBridgeException.class)
                .satisfies(e -> assertThat(causeOf(e)).isEqualTo(QoderBridgeException.Cause.PROVIDER_ERROR));
    }

    @Test
    void events_closeUnblocksAReadInProgress() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        on("GET /sessions/" + SESSION_ID + "/events", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            OutputStream out = exchange.getResponseBody();
            try {
                out.write("data: {\"sequence\":1,\"type\":\"session_started\",\"model\":\"efficient\"}\n\n"
                        .getBytes(StandardCharsets.UTF_8));
                out.flush();
                release.await(10, TimeUnit.SECONDS);
            } catch (IOException | InterruptedException ignored) {
                // the client may have aborted; the stub must stay quiet
            } finally {
                try {
                    out.close();
                } catch (IOException ignored) {
                    // already closed by the aborted exchange
                }
            }
        });

        QoderBridgeClient.EventStream stream = client.openEventStream(SESSION_ID);
        CountDownLatch firstEvent = new CountDownLatch(1);
        AtomicReference<Throwable> readerFailure = new AtomicReference<>();
        Thread reader = Thread.ofVirtual().start(() -> {
            try {
                stream.read(event -> firstEvent.countDown());
            } catch (Throwable t) {
                readerFailure.set(t);
            }
        });

        assertThat(firstEvent.await(5, TimeUnit.SECONDS)).as("first event must arrive").isTrue();
        stream.close();
        reader.join(5000);
        release.countDown();

        assertThat(reader.isAlive()).as("close() must end a read blocked on the open stream").isFalse();
        assertThat(readerFailure.get()).isNull();
    }

    // ------------------------------------------------------------------------------------
    // POST /sessions/{id}/permissions/{requestId}
    // ------------------------------------------------------------------------------------

    @Test
    void decide_postsTheApprovedFlag_andParsesDelivered() {
        on("POST /sessions/" + SESSION_ID + "/permissions/pr-1",
                exchange -> sendJson(exchange, 200, "{\"outcome\":\"delivered\"}"));

        QoderBridgeClient.DecisionOutcome outcome = client.decide(SESSION_ID, "pr-1", true, null);

        assertThat(outcome).isEqualTo(QoderBridgeClient.DecisionOutcome.DELIVERED);
        RecordedRequest request = onlyRequest();
        assertThat(request.method()).isEqualTo("POST");
        assertThat(request.path()).isEqualTo("/sessions/" + SESSION_ID + "/permissions/pr-1");
        assertThat(request.headers()).containsEntry("authorization", "Bearer " + TOKEN);
        assertThat(json(request.body())).isEqualTo(json("{\"approved\":true}"));
    }

    @Test
    void decide_postsTheReasonWhenGiven_andParsesAlreadyResolved() {
        on("POST /sessions/" + SESSION_ID + "/permissions/pr-1",
                exchange -> sendJson(exchange, 200, "{\"outcome\":\"already_resolved\"}"));

        QoderBridgeClient.DecisionOutcome outcome = client.decide(SESSION_ID, "pr-1", false, "host restart");

        assertThat(outcome).isEqualTo(QoderBridgeClient.DecisionOutcome.ALREADY_RESOLVED);
        assertThat(json(onlyRequest().body()))
                .isEqualTo(json("{\"approved\":false,\"reason\":\"host restart\"}"));
    }

    @Test
    void decide_parsesTheRemainingOutcomes() {
        on("POST /sessions/" + SESSION_ID + "/permissions/pr-expired",
                exchange -> sendJson(exchange, 200, "{\"outcome\":\"expired\"}"));
        on("POST /sessions/" + SESSION_ID + "/permissions/pr-unknown",
                exchange -> sendJson(exchange, 200, "{\"outcome\":\"unknown\"}"));

        assertThat(client.decide(SESSION_ID, "pr-expired", false, null))
                .isEqualTo(QoderBridgeClient.DecisionOutcome.EXPIRED);
        assertThat(client.decide(SESSION_ID, "pr-unknown", false, null))
                .isEqualTo(QoderBridgeClient.DecisionOutcome.UNKNOWN);
    }

    @Test
    void decide_maps409AlreadyResolvedToTypedConflict() {
        on("POST /sessions/" + SESSION_ID + "/permissions/pr-1",
                exchange -> sendJson(exchange, 409, "{\"error\":\"ALREADY_RESOLVED\"}"));

        assertThatThrownBy(() -> client.decide(SESSION_ID, "pr-1", false, null))
                .isInstanceOf(QoderBridgeException.class)
                .hasMessageContaining("ALREADY_RESOLVED")
                .satisfies(e -> assertThat(causeOf(e)).isEqualTo(QoderBridgeException.Cause.ALREADY_RESOLVED));
    }

    @Test
    void decide_maps422UnsupportedOptionsToTypedError() {
        on("POST /sessions/" + SESSION_ID + "/permissions/pr-1",
                exchange -> sendJson(exchange, 422, "{\"error\":\"UNSUPPORTED_OPTIONS\"}"));

        assertThatThrownBy(() -> client.decide(SESSION_ID, "pr-1", true, null))
                .isInstanceOf(QoderBridgeException.class)
                .hasMessageContaining("UNSUPPORTED_OPTIONS")
                .satisfies(e -> assertThat(causeOf(e)).isEqualTo(QoderBridgeException.Cause.UNSUPPORTED_OPTIONS));
    }

    // ------------------------------------------------------------------------------------
    // POST /sessions/{id}/cancel
    // ------------------------------------------------------------------------------------

    @Test
    void cancel_parsesTheTerminatedFlag() {
        on("POST /sessions/" + SESSION_ID + "/cancel",
                exchange -> sendJson(exchange, 202, "{\"terminated\":true}"));

        assertThat(client.cancel(SESSION_ID)).isTrue();
        assertThat(onlyRequest().path()).isEqualTo("/sessions/" + SESSION_ID + "/cancel");
    }

    @Test
    void cancel_returnsFalse_whenTheSessionAlreadyEnded() {
        on("POST /sessions/" + SESSION_ID + "/cancel",
                exchange -> sendJson(exchange, 202, "{\"terminated\":false}"));

        assertThat(client.cancel(SESSION_ID)).isFalse();
    }

    // ------------------------------------------------------------------------------------
    // construction and configuration defaults
    // ------------------------------------------------------------------------------------

    @Test
    void constructor_rejectsABlankToken() {
        assertThatThrownBy(() -> new QoderBridgeClient(baseUrl, "  "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bridgeToken");
    }

    @Test
    void properties_defaults_matchTheQoderContract() {
        QoderProperties properties = new QoderProperties();

        assertThat(properties.getSandboxServerUrl()).isEqualTo("http://localhost:8080");
        assertThat(properties.getSandboxApiKey()).isEmpty();
        assertThat(properties.getImage()).isEqualTo("aria-conductor/qoder-sandbox:0.1");
        assertThat(properties.getPort()).isEqualTo(4097);
        assertThat(properties.getMaxTaskMinutes()).isEqualTo(45);
        assertThat(properties.getModel()).isEqualTo("auto");
    }

    // ------------------------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------------------------

    private void on(String methodAndPath, Route route) {
        routes.put(methodAndPath, route);
    }

    private static void sendJson(HttpExchange exchange, int status, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        try {
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        } catch (IOException ignored) {
            // The client may have aborted (timeout tests); the stub must not add noise.
        }
    }

    /** Streams C0.2 SSE frames ({@code data: {...}\n\n}) and then ends the response. */
    private static void sendSse(HttpExchange exchange, String... events) throws IOException {
        exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
        exchange.sendResponseHeaders(200, 0);
        try (OutputStream out = exchange.getResponseBody()) {
            for (String event : events) {
                out.write(("data: " + event + "\n\n").getBytes(StandardCharsets.UTF_8));
                out.flush();
            }
        } catch (IOException ignored) {
            // The client may have aborted; the stub must not add noise.
        }
    }

    private static void pause(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static QoderBridgeClient.CreateSessionRequest minimalCreateRequest() {
        return new QoderBridgeClient.CreateSessionRequest(
                "run-1", "agent-1", "/workspace", "efficient", null, List.of());
    }

    private static QoderBridgeException.Cause causeOf(Throwable e) {
        return ((QoderBridgeException) e).cause();
    }

    private RecordedRequest onlyRequest() {
        assertThat(requests).hasSize(1);
        return requests.get(0);
    }

    private static JsonNode json(String text) {
        try {
            return JSON.readTree(text);
        } catch (Exception e) {
            throw new AssertionError("invalid JSON in test: " + text, e);
        }
    }
}

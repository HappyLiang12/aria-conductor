package io.aria.conductor.execution.adk.qoder;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Minimal HTTP/SSE client for the in-sandbox Qoder bridge (frozen contract C0.2, implemented by
 * {@code agent-control-tower/qoder-sandbox/bridge/src/server.ts}).
 *
 * <p>Deliberately dependency-free (pure JDK {@link HttpClient}) and isolated in a single file so
 * that bridge version drift only touches this class. Consumed by {@code QoderAdkProvider} (B6).
 *
 * <p>Endpoints used:
 * <ul>
 *   <li>{@code GET /health} — bridge liveness, credential-free ({@link #health()})</li>
 *   <li>{@code POST /sessions} — create a session ({@link #createSession})</li>
 *   <li>{@code POST /sessions/{id}/prompt} — start one prompt ({@link #prompt})</li>
 *   <li>{@code GET /sessions/{id}/events?after=N} — SSE event stream ({@link #openEventStream})</li>
 *   <li>{@code POST /sessions/{id}/permissions/{requestId}} — deliver a decision ({@link #decide})</li>
 *   <li>{@code POST /sessions/{id}/cancel} — cancel the run ({@link #cancel})</li>
 * </ul>
 *
 * <p>Every route except {@code /health} carries {@code Authorization: Bearer <bridgeToken>}; the
 * token is only ever written to the request header, never to a log or an exception message.
 *
 * <p>Failures are mapped to {@link QoderBridgeException} with a typed
 * {@link QoderBridgeException.Cause} (401/404/400/409/413/422, timeouts and transport failures).
 * Mutating calls are never retried: a lost response to a {@code POST} could mean the mutation was
 * applied, so a retry could double-submit a prompt or a decision.
 */
@Slf4j
public class QoderBridgeClient implements AutoCloseable {

    /** Default per-request timeout for the short control-plane calls. */
    public static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(60);

    /** {@code /health} must fail fast: it is a liveness probe, not a task wait. */
    public static final Duration HEALTH_TIMEOUT = Duration.ofSeconds(5);

    private final String baseUrl;
    private final String bridgeToken;
    private final Duration requestTimeout;
    private final HttpClient httpClient;
    /** Owned executor feeding {@link #httpClient} — shut down in {@link #close()}. */
    private final ExecutorService executor;
    /**
     * A bare mapper is safe here: this client only ever serializes strings/numbers/arrays and
     * parses event payloads as trees, so no domain type (all of which carry {@code Instant}
     * fields) is ever bound through it.
     */
    private final ObjectMapper objectMapper;
    /** Event streams opened by this client, closed together with it. */
    private final Set<EventStream> streams = ConcurrentHashMap.newKeySet();

    public QoderBridgeClient(String baseUrl, String bridgeToken) {
        this(baseUrl, bridgeToken, DEFAULT_REQUEST_TIMEOUT);
    }

    /**
     * @param baseUrl        base URL of the sandbox-internal bridge (e.g. {@code http://127.0.0.1:4097})
     * @param bridgeToken    the sandbox's {@code BRIDGE_TOKEN} bearer value (required, fail closed)
     * @param requestTimeout default per-request timeout for the control-plane calls
     */
    public QoderBridgeClient(String baseUrl, String bridgeToken, Duration requestTimeout) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("baseUrl must be a non-blank bridge URL");
        }
        if (bridgeToken == null || bridgeToken.isBlank()) {
            // Fail closed, mirroring the bridge: without a token every route except /health is 401.
            throw new IllegalArgumentException("bridgeToken must be a non-blank bearer token");
        }
        this.baseUrl = stripTrailingSlash(baseUrl);
        this.bridgeToken = bridgeToken;
        this.requestTimeout = requestTimeout != null ? requestTimeout : DEFAULT_REQUEST_TIMEOUT;
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
        // Keep-alive is governed by the JDK's jdk.httpclient.keepalive.timeout system property
        // (default 20 min) — HttpClient.Builder has no per-client keepAlive(...) method.
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .executor(executor)
                .version(HttpClient.Version.HTTP_1_1)
                .build();
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Release this client's resources: every open event stream, the underlying {@link HttpClient}
     * (JDK 21+ {@code AutoCloseable}) and the owned executor. Idempotent.
     */
    @Override
    public void close() {
        for (EventStream stream : streams) {
            stream.close();
        }
        streams.clear();
        httpClient.close();
        executor.shutdownNow();
    }

    /**
     * Probe {@code GET /health} (no Authorization header; C0.2 keeps this route credential-free).
     *
     * @throws QoderBridgeException {@code UNREACHABLE} when the bridge is down,
     *                              {@code PROVIDER_ERROR} on a non-2xx status
     */
    public Health health() {
        HttpResponse<String> response = send("GET", "/health", null, HEALTH_TIMEOUT, false);
        if (response.statusCode() / 100 != 2) {
            throw errorFor(response.statusCode(), response.body(), "GET /health");
        }
        JsonNode node = parseJson(response.body(), "GET /health");
        return new Health(node.path("status").asText(null), node.path("cliVersion").asText(null));
    }

    /**
     * Create a bridge session ({@code POST /sessions}); the bridge answers 201
     * {@code {bridgeSessionId}} only after ACP initialize + {@code session/new} +
     * {@code session/set_model} succeeded.
     *
     * @return the bridge session id (the host's handle for prompt/events/permissions/cancel)
     */
    public String createSession(CreateSessionRequest request) {
        ObjectNode body = objectMapper.createObjectNode();
        if (request.runId() != null) {
            body.put("runId", request.runId());
        }
        if (request.agentId() != null) {
            body.put("agentId", request.agentId());
        }
        body.put("cwd", request.cwd());
        body.put("model", request.model());
        if (request.deadlineSeconds() != null) {
            body.put("deadlineSeconds", request.deadlineSeconds());
        }
        ArrayNode mcpServers = body.putArray("mcpServers");
        for (McpServer server : emptyIfNull(request.mcpServers())) {
            ObjectNode entry = mcpServers.addObject();
            entry.put("name", server.name());
            entry.put("url", server.url());
            ArrayNode headers = entry.putArray("headers");
            for (Header header : emptyIfNull(server.headers())) {
                headers.addObject().put("name", header.name()).put("value", header.value());
            }
        }
        HttpResponse<String> response = send("POST", "/sessions", toJson(body), requestTimeout, true);
        if (response.statusCode() / 100 != 2) {
            throw errorFor(response.statusCode(), response.body(), "POST /sessions");
        }
        String sessionId = parseJson(response.body(), "POST /sessions").path("bridgeSessionId").asText(null);
        if (sessionId == null || sessionId.isBlank()) {
            throw providerError("POST /sessions response did not contain a bridgeSessionId");
        }
        log.debug("Qoder bridge session created: {}", sessionId);
        return sessionId;
    }

    /**
     * Start one prompt ({@code POST /sessions/{id}/prompt}); the bridge answers 202
     * {@code {accepted:true}} and the completion arrives on the event stream.
     */
    public void prompt(String bridgeSessionId, String text) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("text", text);
        String path = "/sessions/" + pathSegment(bridgeSessionId) + "/prompt";
        HttpResponse<String> response = send("POST", path, toJson(body), requestTimeout, true);
        if (response.statusCode() / 100 != 2) {
            throw errorFor(response.statusCode(), response.body(), "POST " + path);
        }
        if (!parseJson(response.body(), "POST " + path).path("accepted").asBoolean(false)) {
            throw providerError("POST " + path + " did not accept the prompt");
        }
    }

    /**
     * Open the session's event stream ({@code GET /sessions/{id}/events}). The returned
     * {@link EventStream} tracks the last sequence seen so a reconnect resumes with
     * {@code after=<lastSequence>} instead of replaying from 0.
     */
    public EventStream openEventStream(String bridgeSessionId) {
        EventStream stream = new EventStream(bridgeSessionId);
        streams.add(stream);
        return stream;
    }

    /**
     * Deliver a permission decision ({@code POST /sessions/{id}/permissions/{requestId}}).
     *
     * @param reason optional, passed through when non-blank (the bridge accepts and ignores it)
     * @return the bridge's outcome for the request
     * @throws QoderBridgeException {@code ALREADY_RESOLVED} on a conflicting re-decision,
     *                              {@code UNSUPPORTED_OPTIONS} when approval has no
     *                              {@code allow_once} option, {@code NOT_FOUND} for an
     *                              unknown session
     */
    public DecisionOutcome decide(String bridgeSessionId, String requestId, boolean approved, String reason) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("approved", approved);
        if (reason != null && !reason.isBlank()) {
            body.put("reason", reason);
        }
        String path = "/sessions/" + pathSegment(bridgeSessionId) + "/permissions/" + pathSegment(requestId);
        HttpResponse<String> response = send("POST", path, toJson(body), requestTimeout, true);
        if (response.statusCode() / 100 != 2) {
            throw errorFor(response.statusCode(), response.body(), "POST " + path);
        }
        String outcome = parseJson(response.body(), "POST " + path).path("outcome").asText(null);
        return switch (outcome == null ? "" : outcome) {
            case "delivered" -> DecisionOutcome.DELIVERED;
            case "already_resolved" -> DecisionOutcome.ALREADY_RESOLVED;
            case "expired" -> DecisionOutcome.EXPIRED;
            case "unknown" -> DecisionOutcome.UNKNOWN;
            default -> throw providerError("POST " + path + " returned an unknown outcome: " + outcome);
        };
    }

    /**
     * Cancel the run ({@code POST /sessions/{id}/cancel}).
     *
     * @return {@code true} when a cancellation was issued, {@code false} when the session had
     *         already ended
     */
    public boolean cancel(String bridgeSessionId) {
        String path = "/sessions/" + pathSegment(bridgeSessionId) + "/cancel";
        HttpResponse<String> response = send("POST", path, null, requestTimeout, true);
        if (response.statusCode() / 100 != 2) {
            throw errorFor(response.statusCode(), response.body(), "POST " + path);
        }
        return parseJson(response.body(), "POST " + path).path("terminated").asBoolean(false);
    }

    // ------------------------------------------------------------------------------------
    // Wire types
    // ------------------------------------------------------------------------------------

    /** {@code GET /health} response body. */
    public record Health(String status, String cliVersion) { }

    /** {@code POST /sessions} request body (C0.2). */
    public record CreateSessionRequest(String runId, String agentId, String cwd, String model,
                                       Long deadlineSeconds, List<McpServer> mcpServers) { }

    /** One MCP server the CLI may call, with the headers the bridge must keep redacted. */
    public record McpServer(String name, String url, List<Header> headers) { }

    /** One MCP HTTP header (the PAT travels here as {@code Authorization: Bearer <PAT>}). */
    public record Header(String name, String value) { }

    /**
     * One parsed SSE event: the sequence, the frozen C0.2 type name and the full JSON object
     * (so additive fields such as {@code failed.code} stay reachable for the caller).
     */
    public record BridgeEvent(long sequence, String type, JsonNode payload) { }

    /** The four outcomes the bridge reports for a permission decision. */
    public enum DecisionOutcome {
        DELIVERED, ALREADY_RESOLVED, EXPIRED, UNKNOWN
    }

    /**
     * Reader over one bridge session's SSE stream. {@link #read(Consumer)} blocks until the
     * server ends the stream or {@link #close()} is called; the last sequence seen survives
     * across reads, so a reconnect resumes exactly where the previous one stopped.
     */
    public final class EventStream implements AutoCloseable {

        private final String bridgeSessionId;
        /** Also read by callers from other threads (progress reporting), hence volatile. */
        private volatile long lastSequence;
        /** The stream of the read in progress, so {@link #close()} can unblock it. */
        private volatile InputStream responseBody;
        private volatile boolean closed;

        private EventStream(String bridgeSessionId) {
            this.bridgeSessionId = bridgeSessionId;
        }

        /** Highest sequence handed to the consumer so far (0 before the first event). */
        public long lastSequence() {
            return lastSequence;
        }

        /**
         * Read from {@code after=lastSequence} to the end of the stream, handing each event to
         * the consumer. Returns normally on a server-side end of stream (a finished session
         * closes its stream) and when the stream is closed locally. One reader at a time: the
         * method blocks the calling thread for the lifetime of the subscription. An exception
         * thrown by the consumer propagates and ends the read.
         *
         * @throws QoderBridgeException {@code REPLAY_GAP} when the bridge can no longer replay
         *                              from the tracked sequence (the caller must not silently
         *                              restart from 0), {@code NOT_FOUND} for an unknown
         *                              session, {@code UNREACHABLE} when the read fails
         */
        public void read(Consumer<BridgeEvent> consumer) {
            if (closed) {
                throw new IllegalStateException("event stream is closed");
            }
            String path = "/sessions/" + pathSegment(bridgeSessionId) + "/events?after=" + lastSequence;
            HttpRequest request = authenticatedRequest(path)
                    .header("Accept", "text/event-stream")
                    // No per-request timeout: the stream lives for the whole run (bounded by the
                    // run deadline and by close()), unlike the short control-plane calls.
                    .GET()
                    .build();
            HttpResponse<InputStream> response;
            try {
                response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            } catch (HttpTimeoutException e) {
                throw timeoutError("GET", path, null, e);
            } catch (IOException e) {
                throw unreachableError("GET", path, e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw interruptedError("GET", path, e);
            }
            if (response.statusCode() != 200) {
                throw errorFor(response.statusCode(), readBodyQuietly(response.body()), "GET " + path);
            }
            try (InputStream body = response.body()) {
                responseBody = body;
                readFrames(body, consumer);
            } catch (IOException e) {
                if (closed) {
                    // A local close() is a graceful stop, not a bridge failure.
                    return;
                }
                throw unreachableError("GET", path, e);
            } finally {
                responseBody = null;
            }
        }

        /** End this subscription (unblocks an in-progress {@link #read}). Idempotent. */
        @Override
        public void close() {
            closed = true;
            InputStream body = responseBody;
            if (body != null) {
                try {
                    body.close();
                } catch (IOException ignored) {
                    // The stream is being discarded; a close failure has nothing left to recover.
                }
            }
            streams.remove(this);
        }

        /**
         * Minimal SSE parsing: {@code data:} lines are joined (one frame per blank line) and
         * every other field type is ignored. Each frame must parse to a JSON object with a
         * numeric {@code sequence} and a string {@code type}.
         */
        private void readFrames(InputStream body, Consumer<BridgeEvent> consumer) throws IOException {
            BufferedReader reader = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8));
            StringBuilder data = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) {
                    dispatchFrame(data, consumer);
                    continue;
                }
                if (line.startsWith("data:")) {
                    String value = line.substring("data:".length());
                    if (value.startsWith(" ")) {
                        value = value.substring(1);
                    }
                    if (!data.isEmpty()) {
                        data.append('\n');
                    }
                    data.append(value);
                }
                // event:, id:, retry: and comment lines carry no C0.2 payload and are ignored.
            }
            dispatchFrame(data, consumer);
        }

        private void dispatchFrame(StringBuilder data, Consumer<BridgeEvent> consumer) {
            if (data.isEmpty()) {
                return;
            }
            String payload = data.toString();
            data.setLength(0);
            JsonNode node;
            try {
                node = objectMapper.readTree(payload);
            } catch (Exception e) {
                // Never echo the payload: an event body is run content even after redaction.
                throw providerError("Qoder bridge sent a malformed SSE event (not JSON)");
            }
            if (!node.isObject() || !node.path("sequence").isIntegralNumber() || !node.path("type").isTextual()) {
                throw providerError("Qoder bridge sent an SSE event without a numeric sequence and a type");
            }
            long sequence = node.path("sequence").asLong();
            if (sequence > lastSequence) {
                lastSequence = sequence;
            }
            consumer.accept(new BridgeEvent(sequence, node.path("type").asText(), node));
        }
    }

    // ------------------------------------------------------------------------------------
    // Internal helpers
    // ------------------------------------------------------------------------------------

    private HttpResponse<String> send(String method, String path, String jsonBody, Duration timeout,
                                      boolean authenticated) {
        HttpRequest.Builder builder = (authenticated ? authenticatedRequest(path) : anonymousRequest(path))
                .timeout(timeout)
                .header("Accept", "application/json");
        if (jsonBody != null) {
            builder.header("Content-Type", "application/json");
        }
        switch (method) {
            case "POST" -> builder.POST(jsonBody == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(jsonBody));
            case "GET" -> builder.GET();
            default -> throw new IllegalArgumentException("Unsupported HTTP method " + method);
        }
        try {
            return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        } catch (HttpTimeoutException e) {
            throw timeoutError(method, path, timeout, e);
        } catch (IOException e) {
            throw unreachableError(method, path, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw interruptedError(method, path, e);
        }
    }

    /** Request builder for the authenticated routes (everything except {@code /health}). */
    private HttpRequest.Builder authenticatedRequest(String path) {
        return anonymousRequest(path).header("Authorization", "Bearer " + bridgeToken);
    }

    private HttpRequest.Builder anonymousRequest(String path) {
        return HttpRequest.newBuilder().uri(URI.create(baseUrl + path));
    }

    /** Map a bridge error response onto the typed exception; the server's error code decides. */
    private QoderBridgeException errorFor(int status, String body, String context) {
        String code = null;
        String reason = null;
        if (body != null && !body.isBlank()) {
            try {
                JsonNode node = objectMapper.readTree(body);
                code = node.path("error").asText(null);
                reason = node.path("reason").asText(null);
            } catch (Exception ignored) {
                // A non-JSON error body (proxy, crash) has no code to map: the status decides.
            }
        }
        QoderBridgeException.Cause cause = switch (status) {
            case 400 -> QoderBridgeException.Cause.INVALID_REQUEST;
            case 401 -> QoderBridgeException.Cause.UNAUTHORIZED;
            case 404 -> QoderBridgeException.Cause.NOT_FOUND;
            case 409 -> switch (code == null ? "" : code) {
                case "REPLAY_GAP" -> QoderBridgeException.Cause.REPLAY_GAP;
                case "ALREADY_RESOLVED" -> QoderBridgeException.Cause.ALREADY_RESOLVED;
                case "SESSION_ENDED" -> QoderBridgeException.Cause.SESSION_ENDED;
                default -> QoderBridgeException.Cause.CONFLICT;
            };
            case 413 -> QoderBridgeException.Cause.PAYLOAD_TOO_LARGE;
            case 422 -> QoderBridgeException.Cause.UNSUPPORTED_OPTIONS;
            default -> QoderBridgeException.Cause.PROVIDER_ERROR;
        };
        StringBuilder message = new StringBuilder("Qoder bridge ").append(context)
                .append(" failed: HTTP ").append(status);
        if (code != null) {
            message.append(' ').append(code);
        }
        if (reason != null) {
            // The bridge redacts this against the token and every session MCP header value.
            message.append(" — ").append(reason);
        }
        return new QoderBridgeException(cause, message.toString());
    }

    private JsonNode parseJson(String body, String context) {
        try {
            return objectMapper.readTree(body);
        } catch (Exception e) {
            throw providerError("Qoder bridge " + context + " returned malformed JSON");
        }
    }

    private String toJson(JsonNode node) {
        try {
            return objectMapper.writeValueAsString(node);
        } catch (Exception e) {
            throw providerError("Failed to serialize the bridge request payload", e);
        }
    }

    private static String readBodyQuietly(InputStream body) {
        try (InputStream in = body) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    private QoderBridgeException timeoutError(String method, String path, Duration timeout, Exception e) {
        String limit = timeout != null ? " after " + timeout.toMillis() + "ms" : "";
        return new QoderBridgeException(QoderBridgeException.Cause.TIMEOUT,
                "Qoder bridge request timed out" + limit + ": " + method + " " + path, e);
    }

    private QoderBridgeException unreachableError(String method, String path, IOException e) {
        return new QoderBridgeException(QoderBridgeException.Cause.UNREACHABLE,
                "Qoder bridge request failed: " + method + " " + path + " — " + e.getMessage(), e);
    }

    private QoderBridgeException interruptedError(String method, String path, InterruptedException e) {
        return new QoderBridgeException(QoderBridgeException.Cause.INTERRUPTED,
                "Qoder bridge request interrupted: " + method + " " + path, e);
    }

    private QoderBridgeException providerError(String message) {
        return new QoderBridgeException(QoderBridgeException.Cause.PROVIDER_ERROR, message);
    }

    private QoderBridgeException providerError(String message, Throwable t) {
        return new QoderBridgeException(QoderBridgeException.Cause.PROVIDER_ERROR, message, t);
    }

    /** Percent-encode one URL path segment (ids are opaque to this client). */
    private static String pathSegment(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static <T> List<T> emptyIfNull(List<T> list) {
        return list == null ? List.of() : list;
    }

    private static String stripTrailingSlash(String url) {
        if (url == null || url.isBlank()) {
            return "";
        }
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}

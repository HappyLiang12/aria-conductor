package io.aria.conductor.execution.runtime.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Minimal JDK HTTP/SSE client for the committed run-bound Qoder bridge
 * ({@code packages/qoder-acp-bridge}, task 8). The route contract is verified
 * there and is coded against here literally:
 *
 * <ul>
 *   <li>{@code GET /session} — authenticated with the
 *       {@value #CONTROL_SECRET_HEADER} header; the response names the run, the
 *       workspace, the pinned/observed model and the native session id. This is
 *       exactly the call shape the host backend authenticates a launch with, so
 *       a runtime that lost the secret is refused instead of trusted.</li>
 *   <li>{@code POST /prompt} — one prompt body {@code {"text": ...}}; a prompt
 *       already in flight is refused by the bridge.</li>
 *   <li>{@code GET /events?after=&follow=1} — sequenced SSE; {@code after} is
 *       exclusive and an evicted range answers an explicit replay gap instead of
 *       a silent partial answer.</li>
 *   <li>{@code POST /permission-response} — a choice vocabulary
 *       ({@code ALLOW_ONCE}/{@code DENY}), never a native option id.</li>
 *   <li>{@code POST /control} — the cooperative {@code cancel} action;
 *       {@code pause}/{@code resume} are refused with 501 because no native
 *       pause RPC exists.</li>
 * </ul>
 *
 * <p>An HTTP refusal is surfaced as {@link BridgeFailure} carrying the bridge's
 * exact error code and status, so a caller can distinguish "the core has no
 * such control API" from a transport failure instead of guessing. The control
 * secret never appears in a message, an exception or a log line.
 */
public final class QoderBridgeClient implements AutoCloseable {

    /** Header every bridge route but {@code /health} requires. */
    public static final String CONTROL_SECRET_HEADER = "x-bridge-control-secret";

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(30);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final URI endpoint;
    private final String controlSecret;
    private final HttpClient httpClient;

    public QoderBridgeClient(URI endpoint, String controlSecret) {
        this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
        this.controlSecret = Objects.requireNonNull(controlSecret, "controlSecret");
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NEVER)
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    /** The bridge's authenticated launch-time binding view. */
    public record SessionView(String runId, String workspace, String model, String observedModel,
            String sessionId, String state, CoreIdentity core, String protocolVersionJson) {

        /** The core's self-reported identity, when the bridge observed one. */
        public record CoreIdentity(String name, String title, String version) {
        }
    }

    /** The bridge's answer to one accepted prompt. */
    public record PromptAck(int promptId, String sessionId, String state) {
    }

    /** The bridge's truthful control acknowledgement. */
    public record ControlBody(String action, String state, boolean verified, String reason, String stopReason) {
    }

    /** The bridge's permission decision outcome. */
    public record PermissionDecision(int requestId, String choice, String optionId, String raw,
            boolean deduplicated) {
    }

    /** One sequenced SSE frame; {@code payloadJson} is the exact frame object. */
    public record Frame(long seq, String at, String type, String payloadJson) {
    }

    /**
     * Authenticated {@code GET /session} — the verified launch-time binding
     * (run id, workspace, pinned model, native session id).
     *
     * @throws BridgeFailure when the endpoint refuses the run's control secret
     */
    public SessionView session() {
        return session(DEFAULT_REQUEST_TIMEOUT);
    }

    public SessionView session(Duration timeout) {
        JsonNode body = request("GET", "/session", null, timeout);
        return new SessionView(
                body.path("runId").asText(null),
                body.path("workspace").asText(null),
                body.path("model").isTextual() ? body.path("model").asText() : null,
                body.path("observedModel").isTextual() ? body.path("observedModel").asText() : null,
                body.path("sessionId").isTextual() ? body.path("sessionId").asText() : null,
                body.path("state").asText(null),
                new SessionView.CoreIdentity(body.path("core").path("name").asText(null),
                        body.path("core").path("title").asText(null),
                        body.path("core").path("version").asText(null)),
                body.path("protocolVersion").isMissingNode() || body.path("protocolVersion").isNull()
                        ? null
                        : body.path("protocolVersion").toString());
    }

    /**
     * The same authenticated call, accepted only when the answer names the
     * expected run: a listener that does not hold this run's secret, or that
     * answers for another run, is refused instead of supervised on trust.
     */
    public SessionView sessionBoundTo(UUID expectedRunId) {
        Objects.requireNonNull(expectedRunId, "expectedRunId");
        SessionView view = session();
        if (!expectedRunId.toString().equals(view.runId())) {
            throw new IllegalStateException("The bridge at " + endpoint + " does not name run "
                    + expectedRunId + " (it named " + view.runId() + ")");
        }
        return view;
    }

    /**
     * Authenticated {@code POST /prompt} with the exact body
     * {@code {"text": ...}}.
     *
     * @throws BridgeFailure when the bridge refuses (for example
     *         {@code E_PROMPT_IN_PROGRESS} for a second concurrent prompt)
     */
    public PromptAck prompt(String text) {
        Objects.requireNonNull(text, "text");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("text", text);
        JsonNode answer = request("POST", "/prompt", body, DEFAULT_REQUEST_TIMEOUT);
        return new PromptAck(answer.path("promptId").asInt(-1), answer.path("sessionId").asText(null),
                answer.path("state").asText(null));
    }

    /**
     * Authenticated {@code POST /control} with the exact body
     * {@code {"action": ...}}. The answer is the bridge's truthful
     * acknowledgement: {@code verified} is only ever true when the core's
     * observed state confirmed the operation.
     *
     * @throws BridgeFailure {@code E_PAUSE_UNSUPPORTED} (501) when pause/resume
     *         is requested: no native pause RPC exists, the backend owns
     *         process-level suspension
     */
    public ControlBody control(String action) {
        return control(action, Duration.ofSeconds(60));
    }

    /**
     * The same authenticated call with a caller-supplied window (a run's
     * cleanup window is what bounds a control request; the core's confirmation
     * window is the bridge's own {@code --cancel-confirm-ms}).
     */
    public ControlBody control(String action, Duration timeout) {
        Objects.requireNonNull(action, "action");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("action", action);
        JsonNode answer = request("POST", "/control", body, timeout);
        return new ControlBody(answer.path("action").asText(null), answer.path("state").asText(null),
                answer.path("verified").asBoolean(false), answer.path("reason").asText(null),
                answer.path("stopReason").asText(null));
    }

    /**
     * Authenticated {@code POST /permission-response}: the bridge takes a
     * choice ({@code ALLOW_ONCE}/{@code DENY}) and maps it onto the core's own
     * offered option, so a native option id is never sent from here.
     *
     * @throws BridgeFailure {@code E_INVALID_CHOICE}, {@code E_UNKNOWN_REQUEST},
     *         {@code E_CONTROL_CONFLICT} or {@code E_OPTION_UNAVAILABLE}
     */
    public PermissionDecision permissionResponse(int requestId, String choice) {
        Objects.requireNonNull(choice, "choice");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("choice", choice);
        JsonNode answer = request("POST", "/permission-response", body, DEFAULT_REQUEST_TIMEOUT);
        return new PermissionDecision(answer.path("requestId").asInt(-1), answer.path("choice").asText(null),
                answer.path("optionId").asText(null), answer.path("raw").asText(null),
                answer.path("deduplicated").asBoolean(false));
    }

    /**
     * Open the sequenced SSE stream from {@code after} (exclusive) and follow
     * it. The caller owns the returned stream and must close it.
     *
     * @throws BridgeFailure on an explicit replay refusal ({@code E_REPLAY_GAP}
     *         / {@code E_REPLAY_AHEAD}) — never a silent partial answer
     */
    public EventStream events(long after) {
        HttpRequest request = HttpRequest.newBuilder(route("/events?after=" + after + "&follow=1"))
                .timeout(Duration.ofMinutes(30))
                .header(CONTROL_SECRET_HEADER, controlSecret)
                .header("accept", "text/event-stream")
                .GET()
                .build();
        HttpResponse<InputStream> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (IOException e) {
            throw new IllegalStateException("The Qoder bridge at " + endpoint
                    + " could not be reached for its event stream: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while opening the Qoder bridge event stream", e);
        }
        if (response.statusCode() != 200) {
            String body = readQuietly(response.body());
            throw refusal(response.statusCode(), body);
        }
        return new EventStream(response.body());
    }

    @Override
    public void close() {
        httpClient.close();
    }

    /**
     * One SSE consumer. {@link #next()} blocks until the next frame or the end
     * of the stream (an empty result); it never throws for a stream that was
     * closed locally, so a reader loop can end cleanly.
     */
    public final class EventStream implements AutoCloseable {

        private final BufferedReader reader;
        private final InputStream source;
        private volatile boolean closed;

        private EventStream(InputStream source) {
            this.source = source;
            this.reader = new BufferedReader(new InputStreamReader(source, StandardCharsets.UTF_8));
        }

        /** The next frame, or empty when the stream ended (or was closed). */
        public Optional<Frame> next() {
            try {
                String data = null;
                for (;;) {
                    String line = reader.readLine();
                    if (line == null) {
                        return Optional.empty();
                    }
                    if (line.isEmpty() || line.startsWith(":")) {
                        if (data == null) {
                            continue;
                        }
                        return Optional.of(parse(data));
                    }
                    if (line.startsWith("data:")) {
                        data = line.substring("data:".length()).trim();
                    }
                }
            } catch (IOException e) {
                if (closed) {
                    return Optional.empty();
                }
                throw new IllegalStateException("The Qoder bridge event stream failed: " + e.getMessage(), e);
            }
        }

        private Frame parse(String data) {
            try {
                JsonNode frame = JSON.readTree(data);
                return new Frame(frame.path("seq").asLong(-1), frame.path("at").asText(null),
                        frame.path("type").asText(null), data);
            } catch (IOException e) {
                throw new IllegalStateException("The Qoder bridge emitted an unparseable event frame: "
                        + data, e);
            }
        }

        @Override
        public void close() {
            closed = true;
            try {
                source.close();
            } catch (IOException ignored) {
                // closing an already broken stream is a no-op
            }
        }
    }

    // ------------------------------------------------------------ internals

    private JsonNode request(String method, String path, Map<String, Object> body, Duration timeout) {
        String payload = null;
        if (body != null) {
            try {
                payload = JSON.writeValueAsString(body);
            } catch (IOException e) {
                throw new IllegalStateException("Unable to serialize the Qoder bridge request body", e);
            }
        }
        HttpRequest.Builder builder = HttpRequest.newBuilder(route(path))
                .timeout(timeout)
                .header(CONTROL_SECRET_HEADER, controlSecret)
                .header("accept", "application/json");
        if ("POST".equals(method)) {
            builder.header("content-type", "application/json")
                    .POST(payload == null
                            ? HttpRequest.BodyPublishers.noBody()
                            : HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8));
        } else {
            builder.GET();
        }
        HttpResponse<String> response;
        try {
            response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("The Qoder bridge at " + endpoint + " is unreachable for "
                    + method + " " + path + ": " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted during " + method + " " + path
                    + " against the Qoder bridge", e);
        }
        if (response.statusCode() / 100 != 2) {
            throw refusal(response.statusCode(), response.body());
        }
        try {
            JsonNode parsed = JSON.readTree(response.body());
            if (parsed == null || !parsed.isObject()) {
                throw new IllegalStateException("The Qoder bridge answered " + method + " " + path
                        + " with a non-object body: " + response.body());
            }
            return parsed;
        } catch (IOException e) {
            throw new IllegalStateException("The Qoder bridge answered " + method + " " + path
                    + " with an unparseable body: " + response.body(), e);
        }
    }

    private BridgeFailure refusal(int status, String body) {
        String code = "E_HTTP_" + status;
        String message = "The Qoder bridge refused the request with status " + status;
        try {
            JsonNode error = JSON.readTree(body).path("error");
            if (error.isObject()) {
                if (error.path("code").isTextual()) {
                    code = error.path("code").asText();
                }
                if (error.path("message").isTextual()) {
                    message = error.path("message").asText();
                }
            }
        } catch (IOException ignored) {
            // a non-JSON refusal body keeps the generic diagnosis
        }
        return new BridgeFailure(code, status, message);
    }

    private static String readQuietly(InputStream stream) {
        try (InputStream body = stream) {
            return new String(body.readNBytes(64 * 1024), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    private URI route(String path) {
        String base = endpoint.toString();
        if (!base.endsWith("/")) {
            base = base + "/";
        }
        return URI.create(base + (path.startsWith("/") ? path.substring(1) : path));
    }

    /**
     * A refusal the bridge produced: its exact error code, its HTTP status and
     * its message. The control secret is never part of any of them.
     */
    public static final class BridgeFailure extends RuntimeException {

        private final String code;
        private final int status;

        BridgeFailure(String code, int status, String message) {
            super(message);
            this.code = code;
            this.status = status;
        }

        /** The bridge's own error code, for example {@code E_PAUSE_UNSUPPORTED}. */
        public String code() {
            return code;
        }

        /** The HTTP status the bridge answered with. */
        public int status() {
            return status;
        }
    }
}

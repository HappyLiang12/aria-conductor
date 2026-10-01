package io.aria.conductor.execution.adk.opencode;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.aria.conductor.execution.adk.TaskExecutionException;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Minimal HTTP client for the {@code opencode serve} API (see
 * <a href="https://opencode.ai/docs/server/">opencode server docs</a>).
 *
 * <p>Deliberately dependency-free (pure JDK {@link HttpClient}) and isolated in a
 * single file so that opencode version drift only touches this class.
 *
 * <p>Endpoints used:
 * <ul>
 *   <li>{@code POST /session} — create a session, returns {@code {id, ...}}</li>
 *   <li>{@code POST /session/:id/message} — send a message and wait for the response, returns {@code {info, parts}}</li>
 *   <li>{@code POST /session/:id/abort} — abort a running session, returns {@code boolean}</li>
 *   <li>{@code GET /global/health} — returns {@code {healthy, version}}</li>
 *   <li>{@code GET /session/:id/message} — list session messages {@code [{info, parts}]} (S7 progress pump)</li>
 * </ul>
 *
 * <p>HTTP errors are mapped to {@link TaskExecutionException} with
 * {@link TaskExecutionException.Cause#PROVIDER_ERROR}; request timeouts map to
 * {@link TaskExecutionException.Cause#TIMEOUT}.
 */
@Slf4j
public class OpenCodeHttpClient implements AutoCloseable {

    /**
     * Default request timeout applied to non-task paths (abort / health probes).
     * Task paths ({@link #sendMessage(String, String, String, Duration)}) use the
     * per-request deadline derived from the run budget instead of this default.
     */
    public static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofMinutes(5);

    private final String baseUrl;
    private final Duration requestTimeout;
    private final HttpClient httpClient;
    /** Owned executor feeding {@link #httpClient} — shut down in {@link #close()}. */
    private final ExecutorService executor;
    private final ObjectMapper objectMapper;

    public OpenCodeHttpClient(String baseUrl) {
        this(baseUrl, DEFAULT_REQUEST_TIMEOUT);
    }

    /**
     * @param baseUrl        base URL of the opencode serve instance (e.g. {@code http://localhost:4096})
     * @param requestTimeout default per-request timeout (can be overridden per call)
     */
    public OpenCodeHttpClient(String baseUrl, Duration requestTimeout) {
        this.baseUrl = stripTrailingSlash(baseUrl);
        this.requestTimeout = requestTimeout != null ? requestTimeout : DEFAULT_REQUEST_TIMEOUT;
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
        // Keep-alive is governed by the JDK's jdk.httpclient.keepalive.timeout
        // system property (default 20 min) — HttpClient.Builder has no
        // per-client keepAlive(...) method.
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .executor(executor)
                .version(HttpClient.Version.HTTP_1_1)
                .build();
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Release this client's resources: close the underlying {@link HttpClient}
     * (JDK 21+ {@code AutoCloseable}) and shut down the owned executor so the
     * virtual threads backing in-flight requests are interrupted and reclaimed.
     *
     * <p>Idempotent — safe to call more than once.
     */
    @Override
    public void close() {
        httpClient.close();
        executor.shutdownNow();
    }

    /**
     * Create a new session.
     *
     * @param title session title (typically the run id)
     * @return the created session id
     */
    public String createSession(String title) {
        return createSession(title, true);
    }

    /**
     * Open a new session with single-shot semantics (task 11 governed session
     * path): a retried {@code POST /session} whose response was lost would open
     * a second native session the run does not know about, so the governed path
     * never retries a non-idempotent creation call. The legacy
     * {@link #createSession(String)} keeps its retrying transport for the
     * existing ADK provider.
     *
     * @param title session title (typically the run id)
     * @return the created session id
     */
    public String openSession(String title) {
        return createSession(title, false);
    }

    private String createSession(String title, boolean retry) {
        ObjectNode body = objectMapper.createObjectNode();
        if (title != null && !title.isBlank()) {
            body.put("title", title);
        }
        String payload = body.isEmpty() ? "{}" : toJson(body);
        HttpResponse<String> resp = retry
                ? send("POST", "/session", payload, requestTimeout)
                : sendNoRetry("POST", "/session", payload, requestTimeout);
        if (resp.statusCode() / 100 != 2) {
            throw providerError("POST /session returned status " + resp.statusCode());
        }
        JsonNode node = parse(resp.body());
        String id = node.path("id").asText(null);
        if (id == null || id.isBlank()) {
            throw providerError("POST /session response did not contain a session id: " + resp.body());
        }
        log.debug("OpenCode session created: {}", id);
        return id;
    }

    /**
     * Send a message to a session and wait for the agent's response.
     *
     * @param sessionId    target session
     * @param systemPrompt system prompt (may be {@code null})
     * @param userPrompt   user task prompt
     * @return parsed message response
     */
    public MessageResponse sendMessage(String sessionId, String systemPrompt, String userPrompt) {
        return sendMessage(sessionId, systemPrompt, userPrompt, requestTimeout);
    }

    /**
     * Send a message with an explicit timeout (used to bound task execution).
     *
     * @throws TaskExecutionException {@code TIMEOUT} if the request exceeds {@code timeout},
     *                                {@code PROVIDER_ERROR} on HTTP errors
     */
    public MessageResponse sendMessage(String sessionId, String systemPrompt, String userPrompt, Duration timeout) {
        ObjectNode body = objectMapper.createObjectNode();
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            body.put("system", systemPrompt);
        }
        ArrayNode parts = body.putArray("parts");
        parts.addObject().put("type", "text").put("text", userPrompt == null ? "" : userPrompt);

        Duration effectiveTimeout = timeout != null ? timeout : requestTimeout;
        HttpResponse<String> resp = send("POST", "/session/" + sessionId + "/message",
                toJson(body), effectiveTimeout);
        if (resp.statusCode() / 100 != 2) {
            throw providerError("POST /session/" + sessionId + "/message returned status " + resp.statusCode());
        }
        return parseMessageResponse(resp.body());
    }

    /**
     * Abort a running session.
     *
     * @return {@code true} if the server acknowledged the abort
     */
    public boolean abortSession(String sessionId) {
        HttpResponse<String> resp = sendNoRetry("POST", "/session/" + sessionId + "/abort", "", requestTimeout);
        if (resp.statusCode() / 100 != 2) {
            throw providerError("POST /session/" + sessionId + "/abort returned status " + resp.statusCode());
        }
        try {
            return objectMapper.readTree(resp.body()).asBoolean(true);
        } catch (Exception e) {
            log.debug("Could not parse abort response body as boolean: {}", resp.body());
            return true;
        }
    }

    /**
     * The governed prompt path (task 11): one user message with optional system
     * material and the ordered text parts of the translated prompt. The reviewed
     * model pin is a session-level record, never a payload member: opencode >=
     * 1.18 refuses a string model member ("Expected object | null, got ... at
     * [\"model\"]"), so the server serves the prompt with its configured model.
     * Single-shot on purpose -- a retried non-idempotent message POST could
     * execute the same core action twice, and the native subset carries no
     * idempotency key.
     *
     * <p>The native envelope members an OpenCode server may omit stay absent in
     * the result: a message whose {@code info.tokens} is missing reports unknown
     * counters, never zero. A message envelope carrying {@code info.error} (the
     * recorded provider-failure shape, HTTP 200 with an error member) is a
     * failed prompt, never an empty completion.
     *
     * @throws TaskExecutionException {@code PROVIDER_ERROR} for a refusal or a
     *         provider error envelope, {@code TIMEOUT} when the deadline elapses
     */
    public MessageResult sendPrompt(String sessionId, String systemPrompt,
            List<String> textParts, Duration timeout) {
        ObjectNode body = objectMapper.createObjectNode();
        // No model member: the pin is a session-level record and this client has
        // no model-object spec to send, so the server's configured model serves.
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            body.put("system", systemPrompt);
        }
        ArrayNode parts = body.putArray("parts");
        for (String text : textParts) {
            parts.addObject().put("type", "text").put("text", text == null ? "" : text);
        }
        String path = "/session/" + sessionId + "/message";
        Duration effectiveTimeout = timeout != null ? timeout : requestTimeout;
        HttpResponse<String> resp = sendNoRetry("POST", path, toJson(body), effectiveTimeout);
        if (resp.statusCode() / 100 != 2) {
            throw refusalError(path, resp.statusCode(), resp.body());
        }
        MessageResult result = parseMessageResult(resp.body());
        if (result.providerError() != null) {
            throw providerError("OpenCode returned an assistant message error for " + path + ": "
                    + result.providerError());
        }
        return result;
    }

    /**
     * Parsed native assistant message envelope of {@link #sendPrompt}.
     *
     * @param rawJson       the exact response body (forwarded to CoreEvent payloads)
     * @param messageId     id of the response {@code Message} (null when absent)
     * @param finalOutput   concatenated text parts of the response
     * @param inputTokens   reported prompt tokens, null when the core reported none
     * @param outputTokens  reported completion tokens, null when the core reported none
     * @param observedModel the model the core reports it used, null when unreported
     * @param providerError flattened {@code info.error} diagnosis, null when the message succeeded
     */
    public record MessageResult(String rawJson, String messageId, String finalOutput,
            Long inputTokens, Long outputTokens, String observedModel, String providerError) {
    }

    /** Parse one native assistant message envelope; missing members stay unknown. */
    public MessageResult parseMessageResult(String body) {
        JsonNode root = parse(body);
        JsonNode info = root.path("info");
        JsonNode parts = root.path("parts");

        String messageId = info.path("id").asText(null);
        StringBuilder text = new StringBuilder();
        if (parts.isArray()) {
            for (JsonNode part : parts) {
                if ("text".equals(part.path("type").asText())) {
                    String t = part.path("text").asText();
                    if (!t.isBlank()) {
                        if (!text.isEmpty()) {
                            text.append('\n');
                        }
                        text.append(t);
                    }
                }
            }
        }
        JsonNode tokens = info.path("tokens");
        Long inputTokens = tokens.has("input") ? tokens.path("input").asLong() : null;
        Long outputTokens = tokens.has("output") ? tokens.path("output").asLong() : null;
        String observedModel = info.path("modelID").isTextual() ? info.path("modelID").asText() : null;
        return new MessageResult(body, messageId, text.toString(), inputTokens, outputTokens,
                observedModel, flattenProviderError(info.path("error")));
    }

    /** The recorded provider-failure member: {@code error.name} plus {@code error.data.message}. */
    private static String flattenProviderError(JsonNode error) {
        if (!error.isObject()) {
            return null;
        }
        String name = error.path("name").asText(null);
        String message = error.path("data").path("message").asText(null);
        return (name == null ? "error" : name) + ": " + (message == null ? "" : message);
    }

    /**
     * The refusal diagnosis of a non-2xx message response: the native provider
     * error envelope when the body carries one, the bare status otherwise.
     */
    private TaskExecutionException refusalError(String path, int status, String body) {
        String detail = null;
        try {
            JsonNode root = objectMapper.readTree(body);
            JsonNode error = root.path("error");
            if (error.isObject()) {
                detail = error.path("data").path("message").asText(null);
                if (detail == null) {
                    detail = error.path("message").asText(null);
                }
            } else if (root.path("data").isObject() && root.path("data").path("message").isTextual()) {
                // The native envelope of the pinned core:
                // {"name":"BadRequest","data":{"message":"...","kind":"Payload"}}
                detail = root.path("data").path("message").asText();
            }
        } catch (Exception e) {
            log.debug("Could not parse the OpenCode refusal body: {}", body);
        }
        String message = "OpenCode refused POST " + path + " (status " + status + ")"
                + (detail == null ? "" : ": " + detail);
        return providerError(message);
    }

    /**
     * Probe {@code GET /global/health}.
     *
     * @return {@code true} when the server reports healthy
     */
    public boolean isHealthy() {
        try {
            HttpResponse<String> resp = sendNoRetry("GET", "/global/health", null, Duration.ofSeconds(3));
            if (resp.statusCode() / 100 != 2) {
                return false;
            }
            JsonNode node = parse(resp.body());
            return node.path("healthy").asBoolean(false);
        } catch (TaskExecutionException e) {
            log.debug("OpenCode health probe failed: {}", e.getMessage());
            return false;
        }
    }

    /**
     * S7: snapshot of one session message for the progress pump.
     */
    public record MessageSnapshot(String id, List<PartSnapshot> parts) { }

    /**
     * S7: fault-tolerant snapshot of a message part. Missing fields stay null.
     */
    public record PartSnapshot(String id, String type, String text, String toolName, String state) { }

    /** Pump polling timeout — deliberately short; never reuse the 5min task timeout. */
    public static final Duration LIST_MESSAGES_TIMEOUT = Duration.ofSeconds(10);

    /**
     * S7: list session messages for the progress pump ({@code GET /session/:id/message}).
     *
     * <p>Degradation contract: any non-2xx, I/O failure or malformed body yields an
     * EMPTY list (logged at debug) — the pump must never blow up the task path.
     */
    public List<MessageSnapshot> listMessages(String sessionId) {
        try {
            HttpResponse<String> resp = sendNoRetry("GET", "/session/" + sessionId + "/message",
                    null, LIST_MESSAGES_TIMEOUT);
            if (resp.statusCode() / 100 != 2) {
                log.debug("OpenCode listMessages {} returned status {}", sessionId, resp.statusCode());
                return List.of();
            }
            JsonNode root = objectMapper.readTree(resp.body());
            if (!root.isArray()) {
                return List.of();
            }
            List<MessageSnapshot> out = new ArrayList<>();
            for (JsonNode msg : root) {
                String id = msg.path("info").path("id").asText(null);
                List<PartSnapshot> parts = new ArrayList<>();
                JsonNode partsNode = msg.path("parts");
                if (partsNode.isArray()) {
                    for (JsonNode p : partsNode) {
                        parts.add(new PartSnapshot(
                                p.path("id").asText(null),
                                p.path("type").asText(null),
                                p.has("text") ? p.path("text").asText(null) : null,
                                p.has("tool") ? p.path("tool").asText(null) : p.path("name").asText(null),
                                p.path("state").path("status").asText(null)));
                    }
                }
                out.add(new MessageSnapshot(id, parts));
            }
            return out;
        } catch (TaskExecutionException e) {
            log.debug("OpenCode listMessages failed for {}: {}", sessionId, e.getMessage());
            return List.of();
        } catch (Exception e) {
            log.debug("OpenCode listMessages parse failed for {}: {}", sessionId, e.getMessage());
            return List.of();
        }
    }

    // ---- internal helpers ----

    /**
     * Send a request with retry on transient I/O failures.
     *
     * <p>Retries (up to 2, backoff 1s then 4s) only on {@link IOException}s that
     * are NOT {@link HttpTimeoutException}: a connection reset is transient and
     * worth retrying, but a timeout means the server is too slow to answer and
     * must surface immediately as {@link TaskExecutionException.Cause#TIMEOUT}.
     * Non-2xx responses are returned as-is (no retry).
     */
    private HttpResponse<String> send(String method, String path, String jsonBody, Duration timeout) {
        return sendWithRetry(method, path, jsonBody, timeout);
    }

    /**
     * Retry wrapper over {@link #sendOnce} — see {@link #send} for the policy.
     */
    private HttpResponse<String> sendWithRetry(String method, String path, String jsonBody, Duration timeout) {
        final int maxRetries = 2;
        for (int attempt = 0; ; attempt++) {
            try {
                return sendOnce(method, path, jsonBody, timeout);
            } catch (HttpTimeoutException e) {
                // HttpTimeoutException extends IOException — catch it FIRST so a
                // timeout is translated to TIMEOUT without retrying.
                throw timeoutError(method, path, timeout, e);
            } catch (IOException e) {
                if (attempt >= maxRetries) {
                    throw providerError(method, path, e);
                }
                log.warn("OpenCode HTTP {} {} failed with {} - retrying attempt {}/{}",
                        method, path, e.getClass().getSimpleName(), attempt + 1, maxRetries);
                try {
                    Thread.sleep(attempt == 0 ? 1_000L : 4_000L);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw abortedError(method, path, ie);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw abortedError(method, path, e);
            }
        }
    }

    /**
     * Single-shot send with no retry — used by abort / health probes where a
     * dead sandbox must fail fast instead of amplifying latency via backoff.
     */
    private HttpResponse<String> sendNoRetry(String method, String path, String jsonBody, Duration timeout) {
        try {
            return sendOnce(method, path, jsonBody, timeout);
        } catch (HttpTimeoutException e) {
            throw timeoutError(method, path, timeout, e);
        } catch (IOException e) {
            throw providerError(method, path, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw abortedError(method, path, e);
        }
    }

    private HttpResponse<String> sendOnce(String method, String path, String jsonBody, Duration timeout)
            throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json");
        switch (method) {
            case "POST" -> builder.POST(jsonBody == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(jsonBody));
            case "GET" -> builder.GET();
            default -> throw new IllegalArgumentException("Unsupported HTTP method " + method);
        }
        return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private TaskExecutionException timeoutError(String method, String path, Duration timeout, HttpTimeoutException e) {
        return new TaskExecutionException(TaskExecutionException.Cause.TIMEOUT,
                "OpenCode request timed out after " + timeout.toMillis() + "ms: " + method + " " + path, e);
    }

    private TaskExecutionException providerError(String method, String path, IOException e) {
        return new TaskExecutionException(TaskExecutionException.Cause.PROVIDER_ERROR,
                "OpenCode request failed: " + method + " " + path + " — " + e.getMessage(), e);
    }

    private TaskExecutionException abortedError(String method, String path, InterruptedException e) {
        return new TaskExecutionException(TaskExecutionException.Cause.ABORTED,
                "OpenCode request interrupted: " + method + " " + path, e);
    }

    private MessageResponse parseMessageResponse(String body) {
        JsonNode root = parse(body);
        JsonNode info = root.path("info");
        JsonNode parts = root.path("parts");

        String messageId = info.path("id").asText(null);
        StringBuilder text = new StringBuilder();
        if (parts.isArray()) {
            for (JsonNode part : parts) {
                if ("text".equals(part.path("type").asText())) {
                    String t = part.path("text").asText();
                    if (!t.isBlank()) {
                        if (!text.isEmpty()) {
                            text.append('\n');
                        }
                        text.append(t);
                    }
                }
            }
        }
        JsonNode tokens = info.path("tokens");
        int inputTokens = tokens.path("input").asInt(0);
        int outputTokens = tokens.path("output").asInt(0);
        return new MessageResponse(messageId, text.toString(), inputTokens, outputTokens);
    }

    private JsonNode parse(String body) {
        try {
            return objectMapper.readTree(body);
        } catch (Exception e) {
            throw providerError("OpenCode returned malformed JSON: " + body, e);
        }
    }

    private String toJson(ObjectNode node) {
        try {
            return objectMapper.writeValueAsString(node);
        } catch (Exception e) {
            throw providerError("Failed to serialize JSON payload", e);
        }
    }

    private TaskExecutionException providerError(String message) {
        return new TaskExecutionException(TaskExecutionException.Cause.PROVIDER_ERROR, message);
    }

    private TaskExecutionException providerError(String message, Throwable t) {
        return new TaskExecutionException(TaskExecutionException.Cause.PROVIDER_ERROR, message, t);
    }

    private static String stripTrailingSlash(String url) {
        if (url == null || url.isBlank()) {
            return "";
        }
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    /**
     * Parsed result of {@code POST /session/:id/message}.
     *
     * @param messageId    id of the response {@code Message}
     * @param finalOutput  concatenated text parts of the response
     * @param inputTokens  prompt tokens reported by the message
     * @param outputTokens completion tokens reported by the message
     */
    public record MessageResponse(String messageId, String finalOutput,
                                  int inputTokens, int outputTokens) {
    }
}

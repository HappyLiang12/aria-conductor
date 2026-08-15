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
        ObjectNode body = objectMapper.createObjectNode();
        if (title != null && !title.isBlank()) {
            body.put("title", title);
        }
        String payload = body.isEmpty() ? "{}" : toJson(body);
        HttpResponse<String> resp = send("POST", "/session", payload, requestTimeout);
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

package io.aria.conductor.execution.adk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * HTTP client for talking to a local ADK runtime subprocess.
 *
 * <ul>
 *   <li>POST /run     — submit an action; the body is a server-sent-events stream.
 *   <li>GET  /health  — readiness probe.
 *   <li>POST /shutdown — request graceful stop with a bounded wait.
 * </ul>
 *
 * Uses Java 21 {@link java.net.http.HttpClient} with a virtual-thread executor so
 * that long-lived SSE reads do not pin platform threads.
 */
@Slf4j
@Component
public class AdkHttpClient {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration HEALTH_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration RUN_TIMEOUT = Duration.ofMinutes(5);
    private static final Duration SHUTDOWN_TIMEOUT = Duration.ofSeconds(10);

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final Executor virtualThreadExecutor;

    public AdkHttpClient() {
        this.virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .executor(virtualThreadExecutor)
                .build();
        this.objectMapper = new ObjectMapper();
    }

    // Test seam — inject a custom HttpClient (e.g. one pointed at WireMock).
    AdkHttpClient(HttpClient httpClient) {
        this.virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
        this.httpClient = httpClient;
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Submit an action to POST /run and parse the SSE stream.
     * The future completes once the stream terminates.
     */
    public CompletableFuture<AdkRunResponse> submitRun(String host, int port, AdkRunRequest request) {
        return CompletableFuture.supplyAsync(() -> doSubmitRun(host, port, request), virtualThreadExecutor);
    }

    private AdkRunResponse doSubmitRun(String host, int port, AdkRunRequest request) {
        URI uri = URI.create(String.format("http://%s:%d/run", host, port));
        try {
            String body = objectMapper.writeValueAsString(request);
            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(uri)
                    .timeout(RUN_TIMEOUT)
                    .header("Content-Type", "application/json")
                    .header("Accept", "text/event-stream")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            HttpResponse<InputStream> resp = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofInputStream());
            if (resp.statusCode() / 100 != 2) {
                String err = "ADK /run returned status " + resp.statusCode();
                log.warn(err);
                return AdkRunResponse.failure(err, List.of());
            }
            return parseSse(resp.body());
        } catch (Exception e) {
            log.warn("ADK /run failed for {}: {}", uri, e.getMessage());
            return AdkRunResponse.failure(e.getMessage(), List.of());
        }
    }

    /**
     * Parse an SSE response body. Lines beginning with {@code data:} are collected
     * as event payloads. The final aggregated output is the concatenation of any
     * {@code output} fields if events are JSON, otherwise the raw {@code data:} text.
     */
    private AdkRunResponse parseSse(InputStream body) {
        List<String> events = new ArrayList<>();
        StringBuilder aggregated = new StringBuilder();
        String sessionId = null;
        boolean errorSeen = false;
        String errorMessage = null;

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty() || !line.startsWith("data:")) {
                    continue;
                }
                String payload = line.substring("data:".length()).trim();
                if (payload.isEmpty() || "[DONE]".equals(payload)) {
                    continue;
                }
                events.add(payload);

                // Best-effort JSON parsing — events may be plain text too.
                try {
                    JsonNode node = objectMapper.readTree(payload);
                    if (node.has("session_id") && sessionId == null) {
                        sessionId = node.get("session_id").asText();
                    }
                    if (node.has("output")) {
                        aggregated.append(node.get("output").asText());
                    }
                    if (node.has("error")) {
                        errorSeen = true;
                        errorMessage = node.get("error").asText();
                    }
                } catch (Exception jsonEx) {
                    aggregated.append(payload);
                }
            }
        } catch (Exception e) {
            log.warn("Failed to read SSE stream: {}", e.getMessage());
            return AdkRunResponse.failure(e.getMessage(), events);
        }

        if (errorSeen) {
            return AdkRunResponse.failure(errorMessage, events);
        }
        return AdkRunResponse.success(sessionId, aggregated.toString(), events);
    }

    /**
     * Probe GET /health. Never throws — returns {@link AdkHealthStatus#UNREACHABLE}
     * for any I/O failure.
     */
    public AdkHealthStatus checkHealth(String host, int port) {
        URI uri = URI.create(String.format("http://%s:%d/health", host, port));
        HttpRequest req = HttpRequest.newBuilder()
                .uri(uri)
                .timeout(HEALTH_TIMEOUT)
                .GET()
                .build();
        try {
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            int code = resp.statusCode();
            if (code / 100 == 2) {
                return AdkHealthStatus.HEALTHY;
            }
            log.debug("ADK /health on {} returned {}", uri, code);
            return AdkHealthStatus.UNHEALTHY;
        } catch (Exception e) {
            log.debug("ADK /health on {} unreachable: {}", uri, e.getMessage());
            return AdkHealthStatus.UNREACHABLE;
        }
    }

    /**
     * Request graceful shutdown via POST /shutdown. Returns true on a 2xx response.
     * Bounded by a 10s timeout per the task contract.
     */
    public boolean shutdown(String host, int port) {
        URI uri = URI.create(String.format("http://%s:%d/shutdown", host, port));
        HttpRequest req = HttpRequest.newBuilder()
                .uri(uri)
                .timeout(SHUTDOWN_TIMEOUT)
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        try {
            HttpResponse<Void> resp = httpClient.send(req, HttpResponse.BodyHandlers.discarding());
            boolean ok = resp.statusCode() / 100 == 2;
            log.info("ADK /shutdown on {} -> {} (ok={})", uri, resp.statusCode(), ok);
            return ok;
        } catch (Exception e) {
            log.warn("ADK /shutdown on {} failed: {}", uri, e.getMessage());
            return false;
        }
    }
}

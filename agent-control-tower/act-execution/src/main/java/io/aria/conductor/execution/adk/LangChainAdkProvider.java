package io.aria.conductor.execution.adk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.aria.conductor.agent.repository.LlmProviderRepository;
import io.aria.conductor.common.model.Agent;
import io.aria.conductor.common.model.LlmProvider;
import io.aria.conductor.execution.llm.LlmMessage;
import io.aria.conductor.execution.llm.LlmProperties;
import io.aria.conductor.execution.llm.LlmResponse;
import io.aria.conductor.execution.llm.LlmToolCall;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * LangChain ADK provider — manages Python subprocess running FastAPI server.
 *
 * <p>Each agent gets its own uvicorn process on a dedicated port.
 * Communication is via HTTP + SSE (POST /run streams events back).
 *
 * <p>ADK subprocess failures are reported as explicit exceptions.
 * No silent fallback — if the ADK is unavailable, the call fails.
 */
@Slf4j
@Component
public class LangChainAdkProvider extends AbstractAdkProvider {

    /** Threshold of consecutive failed health probes before triggering restart. */
    private static final int RESTART_AFTER_FAILURES = 3;
    /** Initial backoff after the first restart attempt. */
    private static final long INITIAL_BACKOFF_MS = 1_000L;

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration RUN_TIMEOUT = Duration.ofMinutes(5);
    private static final Duration HEALTH_TIMEOUT = Duration.ofSeconds(3);

    private final LangChainAdkProperties properties;
    private final LlmProperties llmProperties;
    private final AdkProcessReaper reaper;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final LlmProviderRepository providerRepository;
    private final Map<UUID, AdkInstance> instances = new ConcurrentHashMap<>();
    private final AtomicInteger nextPort;

    public LangChainAdkProvider(LangChainAdkProperties properties,
                                LlmProperties llmProperties,
                                AdkProcessReaper reaper,
                                LlmProviderRepository providerRepository) {
        this.properties = properties;
        this.llmProperties = llmProperties;
        this.reaper = reaper;
        this.providerRepository = providerRepository;
        this.objectMapper = new ObjectMapper();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .executor(Executors.newVirtualThreadPerTaskExecutor())
                .version(HttpClient.Version.HTTP_1_1)  // avoid connection pooling issues with uvicorn
                .build();
        this.nextPort = new AtomicInteger(properties.getPortRangeStart());
    }

    @Override
    public String providerId() {
        return "langchain";
    }

    @Override
    public void prepareAgent(UUID agentId, Agent agent) {
        // agent param is part of AdkProvider contract but not needed here —
        // the agent record has already been upserted by the caller
        AdkInstance instance = getOrStartInstance(agentId);
        waitForReady(instance, agentId);
    }

    /**
     * Returns an existing healthy instance, or starts a new Python subprocess.
     */
    public AdkInstance getOrStartInstance(UUID agentId) {
        AdkInstance existing = instances.get(agentId);
        if (existing != null && existing.healthy()) {
            return existing;
        }

        if (existing != null && !existing.healthy()) {
            log.warn("Existing LangChain instance for agent {} is unhealthy, restarting...", agentId);
            stopInstance(existing);
            instances.remove(agentId);
        }

        return startNewInstance(agentId);
    }

    @Override
    public LlmResponse call(UUID agentId, List<LlmMessage> messages, List<Map<String, Object>> tools) {
        AdkInstance instance = getOrStartInstance(agentId);
        if (instance.port() == 0) {
            throw new IllegalStateException("ADK subprocess unavailable (port=0) for agent " + agentId);
        }
        // Wait for readiness if not yet confirmed healthy (new instances start with healthy=false)
        if (!instance.healthy()) {
            waitForReady(instance, agentId);
            instance = instances.get(agentId); // re-fetch after waitForReady updates health
            if (instance == null || !instance.healthy()) {
                throw new IllegalStateException("ADK subprocess unavailable for agent " + agentId);
            }
        }
        log.info("Calling LangChain ADK for agent {} on port {}", agentId, instance.port());
        return callViaHttp(agentId, instance.port(), messages, tools);
    }

    /** Block until the ADK server on the given instance responds to GET /health. */
    private void waitForReady(AdkInstance instance, UUID agentId) {
        int maxRetries = 120; // 60 seconds total (500ms * 120)
        for (int i = 0; i < maxRetries; i++) {
            if (checkHealth(properties.getHost(), instance.port()) == AdkHealthStatus.HEALTHY) {
                instance = instance.withHealthCheck(Instant.now(), true, 0);
                instances.put(agentId, instance);
                return;
            }
            try { Thread.sleep(500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
        }
        throw new IllegalStateException(
                "ADK server did not become ready within 60s for agent " + agentId + " on port " + instance.port());
    }

    @Override
    public boolean isHealthy(UUID agentId) {
        AdkInstance inst = instances.get(agentId);
        if (inst == null || inst.port() == 0) {
            return false;
        }
        return checkHealth(properties.getHost(), inst.port()) == AdkHealthStatus.HEALTHY;
    }

    @Override
    public void shutdownAgent(UUID agentId) {
        AdkInstance inst = instances.remove(agentId);
        stopInstance(inst);
    }

    @PreDestroy
    @Override
    public void shutdownAll() {
        log.info("Shutting down LangChain ADK provider — {} instances to stop", instances.size());
        for (AdkInstance inst : instances.values()) {
            stopInstance(inst);
        }
        instances.clear();
    }

    /**
     * Periodic health check for all LangChain instances.
     * Issues GET /health and updates the cached health flag.
     * After {@link #RESTART_AFTER_FAILURES} consecutive failures,
     * {@link #restartUnhealthy()} kicks in with exponential backoff.
     */
    @Scheduled(fixedRateString = "${adk.runtime.langchain.health-check-interval-ms:30000}")
    public void healthCheck() {
        for (Map.Entry<UUID, AdkInstance> entry : instances.entrySet()) {
            AdkInstance inst = entry.getValue();
            try {
                AdkHealthStatus status = checkHealth(properties.getHost(), inst.port());
                boolean healthy = status == AdkHealthStatus.HEALTHY;
                int failures = healthy ? 0 : inst.consecutiveFailures() + 1;
                instances.put(entry.getKey(), inst.withHealthCheck(Instant.now(), healthy, failures));
                log.debug("LangChain health check for agent {}: status={}, failures={}",
                        entry.getKey(), status, failures);
            } catch (Exception e) {
                int failures = inst.consecutiveFailures() + 1;
                instances.put(entry.getKey(), inst.withHealthCheck(Instant.now(), false, failures));
                log.warn("LangChain health check failed for agent {}: {}", entry.getKey(), e.getMessage());
            }
        }
        restartUnhealthy();
    }

    /**
     * Restart any LangChain instance that has crossed {@link #RESTART_AFTER_FAILURES}
     * consecutive failed probes, respecting exponential backoff.
     */
    public void restartUnhealthy() {
        Instant now = Instant.now();
        for (Map.Entry<UUID, AdkInstance> entry : instances.entrySet()) {
            AdkInstance inst = entry.getValue();
            if (inst.healthy() || inst.consecutiveFailures() < RESTART_AFTER_FAILURES) {
                continue;
            }
            if (inst.nextRestartAt() != null && now.isBefore(inst.nextRestartAt())) {
                log.debug("Skipping LangChain restart of {} — backoff until {}", inst.agentId(), inst.nextRestartAt());
                continue;
            }
            int nextAttempt = inst.restartAttempts() + 1;
            long backoff = computeBackoffMs(nextAttempt);
            log.warn("Restarting unhealthy LangChain instance for agent {} (attempt {}, next backoff {}ms)",
                    inst.agentId(), nextAttempt, backoff);
            try {
                stopInstance(inst);
                AdkInstance fresh = startNewInstance(inst.agentId());
                Instant nextAllowed = Instant.now().plusMillis(backoff);
                instances.put(entry.getKey(), fresh.withNextRestartAt(nextAllowed, nextAttempt));
            } catch (Exception e) {
                Instant nextAllowed = Instant.now().plusMillis(backoff);
                instances.put(entry.getKey(), inst.withNextRestartAt(nextAllowed, nextAttempt));
                log.error("LangChain restart failed for agent {}: {}", inst.agentId(), e.getMessage());
            }
        }
    }

    long computeBackoffMs(int attempt) {
        if (attempt < 1) {
            return INITIAL_BACKOFF_MS;
        }
        long max = properties.getMaxRestartBackoffMs();
        int safeExp = Math.min(attempt - 1, 30);
        long backoff = INITIAL_BACKOFF_MS * (1L << safeExp);
        if (backoff < 0 || backoff > max) {
            return max;
        }
        return backoff;
    }

    // ---- HTTP communication ----

    private LlmResponse callViaHttp(UUID agentId, int port, List<LlmMessage> messages, List<Map<String, Object>> tools) {
        try {
            String requestBody = buildRequestBody(agentId, messages, tools);
            URI uri = URI.create(String.format("http://%s:%d/run", properties.getHost(), port));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(uri)
                    .timeout(RUN_TIMEOUT)
                    .header("Content-Type", "application/json")
                    .header("Accept", "text/event-stream")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();

            HttpResponse<java.io.InputStream> resp = httpClient.send(
                    request, HttpResponse.BodyHandlers.ofInputStream());

            if (resp.statusCode() / 100 != 2) {
                throw new RuntimeException("LangChain /run returned status " + resp.statusCode() + " for agent " + agentId);
            }

            return parseSseResponse(resp.body());

        } catch (Exception e) {
            throw new RuntimeException("LangChain /run failed for agent " + agentId + ": " + e.getMessage(), e);
        }
    }

    /**
     * Parse the SSE stream from POST /run.
     *
     * <p>Expected event format:
     * <pre>
     * event: response
     * data: {"content":"...","tool_calls":[...],"finish_reason":"stop","usage":{...}}
     * </pre>
     */
    private LlmResponse parseSseResponse(java.io.InputStream body) throws IOException {
        String content = "";
        List<LlmToolCall> toolCalls = new ArrayList<>();
        String finishReason = "stop";
        int inputTokens = 0;
        int outputTokens = 0;
        String errorMessage = null;

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8))) {
            String currentEvent = null;
            String line;

            while ((line = reader.readLine()) != null) {
                if (line.startsWith("event:")) {
                    currentEvent = line.substring("event:".length()).trim();
                } else if (line.startsWith("data:")) {
                    String payload = line.substring("data:".length()).trim();
                    if (payload.isEmpty()) continue;

                    try {
                        JsonNode node = objectMapper.readTree(payload);

                        switch (currentEvent != null ? currentEvent : "") {
                            case "response" -> {
                                content = node.has("content") ? node.get("content").asText() : "";
                                finishReason = node.has("finish_reason") ? node.get("finish_reason").asText() : "stop";
                                if (node.has("usage")) {
                                    JsonNode usage = node.get("usage");
                                    inputTokens = usage.has("input_tokens") ? usage.get("input_tokens").asInt() : 0;
                                    outputTokens = usage.has("output_tokens") ? usage.get("output_tokens").asInt() : 0;
                                }
                                if (node.has("tool_calls") && node.get("tool_calls").isArray()) {
                                    for (JsonNode tc : node.get("tool_calls")) {
                                        toolCalls.add(new LlmToolCall(
                                                tc.has("id") ? tc.get("id").asText() : "",
                                                tc.has("name") ? tc.get("name").asText() : "",
                                                tc.has("arguments") ? tc.get("arguments").asText() : "{}"
                                        ));
                                    }
                                }
                            }
                            case "error" -> {
                                errorMessage = node.has("message") ? node.get("message").asText() : "Unknown error";
                            }
                            default -> { /* log/think events — could be persisted in future */ }
                        }
                    } catch (Exception jsonEx) {
                        log.debug("Could not parse SSE data as JSON: {}", payload);
                    }
                }
            }
        }

        if (errorMessage != null) {
            throw new RuntimeException("LangChain ADK error: " + errorMessage);
        }

        return new LlmResponse(content, inputTokens, outputTokens, finishReason, toolCalls);
    }

    private String buildRequestBody(UUID agentId, List<LlmMessage> messages, List<Map<String, Object>> tools) throws Exception {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("agent_id", agentId.toString());
        root.put("session_id", agentId.toString()); // Stateless — session managed by Control Tower
        // B3a: use the active DB provider's credentials/model so ADK matches the live config (fallback to env/YAML)
        LlmProvider activeProvider = providerRepository.findByActiveTrue().orElse(null);
        String adkModel = (activeProvider != null && activeProvider.getDefaultModel() != null && !activeProvider.getDefaultModel().isBlank())
                ? activeProvider.getDefaultModel() : properties.getLlmDefaultModel();
        String adkBaseUrl = (activeProvider != null && activeProvider.getBaseUrl() != null && !activeProvider.getBaseUrl().isBlank())
                ? activeProvider.getBaseUrl() : properties.getLlmBaseUrl();
        root.put("model", adkModel);
        root.put("llm_api_key", resolveAdkApiKey());
        root.put("llm_base_url", adkBaseUrl);
        root.put("max_tokens", llmProperties.getMaxTokens());

        ArrayNode messagesArray = objectMapper.createArrayNode();
        for (LlmMessage msg : messages) {
            ObjectNode msgNode = objectMapper.createObjectNode();
            msgNode.put("role", msg.role());
            if (msg.content() != null) {
                msgNode.put("content", msg.content());
            }
            // Serialize tool_call_id for tool messages (required by DeepSeek ordering)
            if (msg.toolCallId() != null) {
                msgNode.put("tool_call_id", msg.toolCallId());
            }
            // Serialize tool_calls for assistant messages (required by DeepSeek ordering)
            if (msg.toolCalls() != null && !msg.toolCalls().isEmpty()) {
                ArrayNode tcsNode = objectMapper.createArrayNode();
                for (var tc : msg.toolCalls()) {
                    ObjectNode tcNode = objectMapper.createObjectNode();
                    tcNode.put("id", tc.id());
                    tcNode.put("name", tc.name());
                    tcNode.put("arguments", tc.arguments());
                    tcsNode.add(tcNode);
                }
                msgNode.set("tool_calls", tcsNode);
            }
            messagesArray.add(msgNode);
        }
        root.set("messages", messagesArray);

        // Add tools array if present
        if (tools != null && !tools.isEmpty()) {
            ArrayNode toolsArray = objectMapper.createArrayNode();
            for (Map<String, Object> tool : tools) {
                toolsArray.add(objectMapper.valueToTree(tool));
            }
            root.set("tools", toolsArray);
        }

        // Empty context — RBAC will be filled by Control Tower in future
        ObjectNode context = objectMapper.createObjectNode();
        root.set("context", context);

        return objectMapper.writeValueAsString(root);
    }

    private AdkHealthStatus checkHealth(String host, int port) {
        URI uri = URI.create(String.format("http://%s:%d/health", host, port));
        HttpRequest req = HttpRequest.newBuilder()
                .uri(uri)
                .timeout(HEALTH_TIMEOUT)
                .GET()
                .build();
        try {
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            return resp.statusCode() / 100 == 2 ? AdkHealthStatus.HEALTHY : AdkHealthStatus.UNHEALTHY;
        } catch (Exception e) {
            return AdkHealthStatus.UNREACHABLE;
        }
    }

    // ---- Subprocess management ----

    private String resolveAdkApiKey() {
        String key = providerRepository.findByActiveTrue()
                .map(LlmProvider::getApiKey)
                .orElse(null);
        if (key == null || key.isBlank()) {
            key = System.getenv("LLM_API_KEY");
        }
        return key != null ? key : "";
    }

    private AdkInstance startNewInstance(UUID agentId) {
        // In remote mode, ADK runs as a standalone container — skip subprocess startup and port allocation
        if ("remote".equalsIgnoreCase(properties.getMode())) {
            int remotePort = properties.getPortRangeStart();
            log.info("ADK remote mode: connecting to {}:{} instead of spawning subprocess for agent {}",
                    properties.getHost(), remotePort, agentId);
            AdkInstance instance = new AdkInstance(agentId, remotePort, null, Instant.now(), Instant.now(), false, 0);
            instances.put(agentId, instance);
            return instance;
        }

        int port = allocatePort();
        log.info("Starting LangChain ADK instance for agent {} on port {}: python={} script={} apiKey={} baseUrl={}",
                agentId, port,
                properties.getPythonPath(), properties.getServerScript(),
                !resolveAdkApiKey().isBlank() ? "***" : "MISSING",
                properties.getLlmBaseUrl());
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    properties.getPythonPath(), properties.getServerScript(), String.valueOf(port));
            pb.redirectErrorStream(true);

            // Pass LLM config as environment variables to the Python process
            pb.environment().put("LLM_API_KEY", resolveAdkApiKey());
            pb.environment().put("LLM_BASE_URL", properties.getLlmBaseUrl());
            pb.environment().put("LLM_DEFAULT_MODEL", properties.getLlmDefaultModel());
            pb.environment().put("HOST", properties.getHost());
            pb.environment().put("PORT", String.valueOf(port));

            Process process = pb.start();
            AdkInstance instance = new AdkInstance(agentId, port, process, Instant.now(), Instant.now(), false, 0);
            instances.put(agentId, instance);

            // Log process output in background to capture startup errors
            Thread.ofVirtual().start(() -> {
                try (var reader = process.inputReader()) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        log.info("[ADK pid={} port={}] {}", process.pid(), port, line);
                    }
                } catch (Exception ignored) { }
            });

            if (reaper != null) {
                reaper.writePidFile(agentId.toString(), process.pid());
            }
            log.info("LangChain ADK instance started for agent {} on port {} (pid={})",
                    agentId, port, process.pid());
            return instance;

        } catch (IOException e) {
            log.error("Failed to start LangChain ADK instance for agent {}", agentId, e);
            AdkInstance failed = new AdkInstance(agentId, port, null, Instant.now(), Instant.now(), false, 1);
            instances.put(agentId, failed);
            return failed;
        }
    }

    private int allocatePort() {
        int start = properties.getPortRangeStart();
        int end = properties.getPortRangeEnd();
        int range = end - start;
        if (range <= 0) {
            return start;
        }
        int ticket = nextPort.getAndIncrement();
        return start + (ticket % range);
    }

    private void stopInstance(AdkInstance inst) {
        if (inst == null) return;

        Process p = inst.process();
        if (p != null && p.isAlive()) {
            log.info("Stopping LangChain ADK instance for agent {} on port {}", inst.agentId(), inst.port());
            long timeoutMs = properties.getShutdownTimeoutMs();
            try {
                p.destroy();
                if (!p.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                    log.warn("LangChain pid={} did not exit within {}ms, forcing", p.pid(), timeoutMs);
                    p.destroyForcibly();
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                p.destroyForcibly();
            }
        }
        if (reaper != null) {
            reaper.removePidFile(inst.agentId().toString());
        }
    }

    // ---- Test-only accessors ----

    /** Test-only: expose the live registry for assertion. */
    Map<UUID, AdkInstance> instancesForTest() {
        return instances;
    }

    /** Test-only: directly insert an instance (used by unit tests). */
    void putInstanceForTest(UUID agentId, AdkInstance inst) {
        instances.put(agentId, inst);
    }

    /** Test-only: look up an instance. */
    java.util.Optional<AdkInstance> getInstanceForTest(UUID agentId) {
        return java.util.Optional.ofNullable(instances.get(agentId));
    }

    /** Test-only: expose allocatePort for deterministic port tests. */
    int allocatePortForTest() {
        return allocatePort();
    }
}

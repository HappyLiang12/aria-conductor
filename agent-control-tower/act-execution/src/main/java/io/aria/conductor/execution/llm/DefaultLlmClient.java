package io.aria.conductor.execution.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.aria.conductor.common.model.LlmProvider;
import io.aria.conductor.common.model.LlmProviderType;
import io.aria.conductor.agent.repository.LlmProviderRepository;
import io.aria.conductor.agent.service.SystemConfigService;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Slf4j
public class DefaultLlmClient implements LlmClient {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(30);

    private final HttpClient httpClient;
    private final LlmProperties properties;
    private final LlmProviderRepository providerRepository;
    private final SystemConfigService systemConfigService;
    private final ObjectMapper objectMapper;

    public DefaultLlmClient(LlmProperties properties, LlmProviderRepository providerRepository, SystemConfigService systemConfigService) {
        this.properties = properties;
        this.providerRepository = providerRepository;
        this.systemConfigService = systemConfigService;
        this.objectMapper = new ObjectMapper();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .build();
    }

    /**
     * Resolve the active provider, falling back to YAML config if none is active.
     */
    private ResolvedProvider resolveProvider() {
        try {
            LlmProvider active = providerRepository.findByActiveTrue().orElse(null);
            if (active != null) {
                String url = active.getBaseUrl() + (active.getBaseUrl().endsWith("/") ? "" : "/") + "chat/completions";
                return new ResolvedProvider(active.getType(), active.getApiKey(), active.getDefaultModel(), active.getDefaultMaxTokens(), url);
            }
        } catch (Exception e) {
            log.warn("Failed to check active LLM provider, falling back to YAML config", e);
        }
        String url = properties.getBaseUrl() + (properties.getBaseUrl().endsWith("/") ? "" : "/") + "chat/completions";
        // Infer auth style from base URL: Azure uses the "api-key" header, everything else uses Bearer.
        LlmProviderType fallbackType = properties.getBaseUrl() != null && properties.getBaseUrl().toLowerCase().contains("azure")
                ? LlmProviderType.AZURE : LlmProviderType.OPENAI;
        return new ResolvedProvider(fallbackType, properties.getApiKey(), properties.getModel(), properties.getMaxTokens(), url);
    }

    @Override
    public LlmResponse complete(LlmRequest request) {
        Instant start = Instant.now();
        ResolvedProvider resolved = resolveProvider();

        // Use provider model if request doesn't specify one
        String model = request.model() != null ? request.model() : resolved.model();
        int maxTokens = request.maxTokens() > 0 ? request.maxTokens() : resolved.maxTokens();

        log.info("LLM request: model={}, messages={}, maxTokens={}",
                model, request.messages().size(), maxTokens);

        try {
            String requestBody = buildRequestBody(request, model, maxTokens);

            int timeoutSeconds = systemConfigService.getInt("llm.request.timeout.seconds", 600, 30, 3600);
            HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(resolved.completionsUrl()))
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody));

            if (resolved.type() == LlmProviderType.AZURE) {
                reqBuilder.header("api-key", resolved.apiKey());
            } else {
                reqBuilder.header("Authorization", "Bearer " + resolved.apiKey());
            }

            HttpResponse<String> httpResponse = httpClient.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofString());

            int statusCode = httpResponse.statusCode();
            if (statusCode >= 400) {
                log.error("LLM request failed with status {}: {}", statusCode, httpResponse.body());
                throw new LlmHttpException(statusCode, httpResponse.body());
            }

            LlmResponse response = parseResponse(httpResponse.body());
            long latencyMs = Duration.between(start, Instant.now()).toMillis();
            log.info("LLM response: inputTokens={}, outputTokens={}, finishReason={}, latencyMs={}",
                    response.inputTokens(), response.outputTokens(), response.finishReason(), latencyMs);

            return response;
        } catch (LlmHttpException e) {
            throw e; // ponytail: let decorator handle HTTP errors
        } catch (Exception e) {
            log.error("LLM request failed", e);
            throw new RuntimeException("LLM request failed: " + e.getMessage(), e);
        }
    }

    @Override
    public reactor.core.publisher.Flux<String> stream(LlmRequest request) {
        // Streaming via Java HttpClient is not yet supported;
        // return an error flux so callers know it's unavailable.
        log.warn("LLM streaming is not supported with Java HttpClient backend");
        return reactor.core.publisher.Flux.error(
                new UnsupportedOperationException("Streaming is not supported with the current HTTP client"));
    }

    private String buildRequestBody(LlmRequest request, String model, int maxTokens) {
        try {
            ObjectNode body = objectMapper.createObjectNode();
            body.put("model", model);
            body.set("messages", buildMessagesArray(request.messages()));
            body.put("max_tokens", maxTokens);
            body.put("temperature", request.temperature());
            if (request.hasTools()) {
                body.set("tools", buildToolsArray(request.tools()));
            }
            return objectMapper.writeValueAsString(body);
        } catch (Exception e) {
            throw new RuntimeException("Failed to build request body", e);
        }
    }

    private ArrayNode buildMessagesArray(List<LlmMessage> messages) {
        ArrayNode array = objectMapper.createArrayNode();
        for (LlmMessage msg : messages) {
            ObjectNode msgNode = objectMapper.createObjectNode();
            msgNode.put("role", msg.role());
            if (msg.content() != null) {
                msgNode.put("content", msg.content());
            }
            if (msg.toolCallId() != null) {
                msgNode.put("tool_call_id", msg.toolCallId());
            }
            if (msg.toolCalls() != null && !msg.toolCalls().isEmpty()) {
                ArrayNode toolCallsNode = objectMapper.createArrayNode();
                for (LlmToolCall tc : msg.toolCalls()) {
                    ObjectNode tcNode = objectMapper.createObjectNode();
                    tcNode.put("id", tc.id());
                    tcNode.put("type", "function");
                    ObjectNode fnNode = objectMapper.createObjectNode();
                    fnNode.put("name", tc.name());
                    fnNode.put("arguments", tc.arguments());
                    tcNode.set("function", fnNode);
                    toolCallsNode.add(tcNode);
                }
                msgNode.set("tool_calls", toolCallsNode);
            }
            array.add(msgNode);
        }
        return array;
    }

    private ArrayNode buildToolsArray(java.util.List<java.util.Map<String, Object>> tools) {
        ArrayNode toolsArray = objectMapper.createArrayNode();
        for (java.util.Map<String, Object> tool : tools) {
            toolsArray.add(objectMapper.valueToTree(tool));
        }
        return toolsArray;
    }

    private LlmResponse parseResponse(String responseBody) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode choices = root.path("choices");
            if (choices.isEmpty()) {
                return new LlmResponse("", 0, 0, "error", List.of());
            }

            JsonNode firstChoice = choices.get(0);
            JsonNode message = firstChoice.path("message");
            String content = message.path("content").asText("");
            String finishReason = firstChoice.path("finish_reason").asText("stop");

            List<LlmToolCall> toolCalls = new ArrayList<>();
            JsonNode toolCallsNode = message.path("tool_calls");
            if (toolCallsNode.isArray()) {
                for (JsonNode tcNode : toolCallsNode) {
                    String id = tcNode.path("id").asText();
                    JsonNode function = tcNode.path("function");
                    String functionName = function.path("name").asText();
                    String arguments = function.path("arguments").asText();
                    toolCalls.add(new LlmToolCall(id, functionName, arguments));
                }
            }

            JsonNode usage = root.path("usage");
            int inputTokens = usage.path("prompt_tokens").asInt(0);
            int outputTokens = usage.path("completion_tokens").asInt(0);

            return new LlmResponse(content, inputTokens, outputTokens, finishReason, toolCalls);
        } catch (Exception e) {
            log.error("Failed to parse LLM response: {}", responseBody, e);
            throw new RuntimeException("Failed to parse LLM response", e);
        }
    }

    /**
     * Internal record holding resolved provider config for building an HTTP request.
     */
    private record ResolvedProvider(LlmProviderType type, String apiKey, String model, int maxTokens, String completionsUrl) {}
}

package io.aria.conductor.mcp.tools;

import io.aria.conductor.agent.dto.LlmProviderRequest;
import io.aria.conductor.agent.dto.LlmProviderResponse;
import io.aria.conductor.agent.service.LlmProviderService;
import io.aria.conductor.common.exception.ResourceNotFoundException;
import io.aria.conductor.common.model.LlmProviderType;
import io.aria.conductor.execution.mcp.McpProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

/**
 * LLM provider management tools. Thin wrappers over the same LlmProviderService
 * the REST controller calls, so every result is an LlmProviderResponse carrying
 * apiKeyMasked only. SECURITY: the raw apiKey is never returned — in particular
 * LlmProviderService.getActiveProvider() is deliberately never called, since it
 * returns the entity with the unmasked key.
 */
@Component
@RequiredArgsConstructor
public class ProviderTools implements McpTool {

    private final LlmProviderService llmProviderService;
    private final McpProperties mcpProperties;

    @Tool(name = "list_llm_providers",
            description = "List LLM providers. API keys are never returned raw; each response carries apiKeyMasked (**** + last 4 characters).")
    public String listLlmProviders() {
        try {
            return ToolResponses.ok(llmProviderService.listAll());
        } catch (Exception e) {
            return ToolResponses.error("PROVIDER_LIST_FAILED", e.getMessage(), e, mcpProperties.isDebug());
        }
    }

    @Tool(name = "get_llm_provider",
            description = "Get one LLM provider by id. The API key is masked, never returned in full.")
    public String getLlmProvider(@ToolParam(description = "Provider id") UUID id) {
        try {
            LlmProviderResponse provider = llmProviderService.getById(id);
            return ToolResponses.ok(provider);
        } catch (ResourceNotFoundException e) {
            return ToolResponses.error("NOT_FOUND", e.getMessage(), e, mcpProperties.isDebug());
        } catch (Exception e) {
            return ToolResponses.error("PROVIDER_READ_FAILED", e.getMessage(), e, mcpProperties.isDebug());
        }
    }

    @Tool(name = "create_llm_provider",
            description = "Create an LLM provider (created inactive). type is OPENAI, ANTHROPIC, AZURE or LOCAL. The apiKey is stored and returned masked; maxTokens defaults to 4096.")
    public String createLlmProvider(
            @ToolParam(description = "Display name") String name,
            @ToolParam(description = "OPENAI, ANTHROPIC, AZURE or LOCAL") String type,
            @ToolParam(description = "Base URL, e.g. https://api.openai.com/v1", required = false) String baseUrl,
            @ToolParam(description = "API key (stored; never returned raw)", required = false) String apiKey,
            @ToolParam(description = "Default model name", required = false) String defaultModel,
            @ToolParam(description = "Default max tokens (defaults to 4096)", required = false) Integer maxTokens) {
        try {
            LlmProviderResponse created = llmProviderService.create(LlmProviderRequest.builder()
                    .name(name)
                    .type(parseType(type))
                    .baseUrl(baseUrl)
                    .apiKey(apiKey)
                    .defaultModel(defaultModel)
                    .maxTokens(maxTokens)
                    .build());
            return ToolResponses.ok(created);
        } catch (IllegalArgumentException e) {
            return ToolResponses.error("VALIDATION", e.getMessage(), e, mcpProperties.isDebug());
        } catch (ResourceNotFoundException e) {
            return ToolResponses.error("NOT_FOUND", e.getMessage(), e, mcpProperties.isDebug());
        } catch (Exception e) {
            return ToolResponses.error("PROVIDER_CREATE_FAILED", e.getMessage(), e, mcpProperties.isDebug());
        }
    }

    @Tool(name = "update_llm_provider",
            description = "Update an LLM provider. Omitted (null/blank) arguments keep their current value. type is OPENAI, ANTHROPIC, AZURE or LOCAL. The apiKey is stored and returned masked.")
    public String updateLlmProvider(
            @ToolParam(description = "Provider id") UUID id,
            @ToolParam(description = "New display name", required = false) String name,
            @ToolParam(description = "OPENAI, ANTHROPIC, AZURE or LOCAL", required = false) String type,
            @ToolParam(description = "New base URL", required = false) String baseUrl,
            @ToolParam(description = "New API key (stored; never returned raw)", required = false) String apiKey,
            @ToolParam(description = "New default model name", required = false) String defaultModel,
            @ToolParam(description = "New default max tokens", required = false) Integer maxTokens) {
        try {
            LlmProviderRequest request = LlmProviderRequest.builder()
                    .name(name)
                    .type(type == null || type.isBlank() ? null : parseType(type))
                    .baseUrl(baseUrl)
                    .apiKey(apiKey)
                    .defaultModel(defaultModel)
                    .maxTokens(maxTokens)
                    .build();
            LlmProviderResponse updated = llmProviderService.update(id, request);
            return ToolResponses.ok(updated);
        } catch (IllegalArgumentException e) {
            return ToolResponses.error("VALIDATION", e.getMessage(), e, mcpProperties.isDebug());
        } catch (ResourceNotFoundException e) {
            return ToolResponses.error("NOT_FOUND", e.getMessage(), e, mcpProperties.isDebug());
        } catch (Exception e) {
            return ToolResponses.error("PROVIDER_UPDATE_FAILED", e.getMessage(), e, mcpProperties.isDebug());
        }
    }

    @Tool(name = "delete_llm_provider",
            description = "Delete an LLM provider by id.")
    public String deleteLlmProvider(@ToolParam(description = "Provider id") UUID id) {
        try {
            llmProviderService.delete(id);
            return ToolResponses.ok(Map.of("id", id.toString(), "deleted", true));
        } catch (ResourceNotFoundException e) {
            return ToolResponses.error("NOT_FOUND", e.getMessage(), e, mcpProperties.isDebug());
        } catch (Exception e) {
            return ToolResponses.error("PROVIDER_DELETE_FAILED", e.getMessage(), e, mcpProperties.isDebug());
        }
    }

    @Tool(name = "activate_llm_provider",
            description = "Activate an LLM provider and deactivate the previously active one. The apiKey is returned masked.")
    public String activateLlmProvider(@ToolParam(description = "Provider id") UUID id) {
        try {
            LlmProviderResponse activated = llmProviderService.activate(id);
            return ToolResponses.ok(activated);
        } catch (ResourceNotFoundException e) {
            return ToolResponses.error("NOT_FOUND", e.getMessage(), e, mcpProperties.isDebug());
        } catch (Exception e) {
            return ToolResponses.error("PROVIDER_ACTIVATE_FAILED", e.getMessage(), e, mcpProperties.isDebug());
        }
    }

    @Tool(name = "test_llm_provider",
            description = "Test connectivity to an LLM provider by sending a minimal completion request. Returns reachable=false when the provider does not answer successfully.")
    public String testLlmProvider(@ToolParam(description = "Provider id") UUID id) {
        try {
            boolean reachable = llmProviderService.testConnection(id);
            return ToolResponses.ok(Map.of("id", id.toString(), "reachable", reachable));
        } catch (ResourceNotFoundException e) {
            return ToolResponses.error("NOT_FOUND", e.getMessage(), e, mcpProperties.isDebug());
        } catch (Exception e) {
            return ToolResponses.error("PROVIDER_TEST_FAILED", e.getMessage(), e, mcpProperties.isDebug());
        }
    }

    private static LlmProviderType parseType(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException(
                    "Provider type is required. Valid: OPENAI, ANTHROPIC, AZURE, LOCAL");
        }
        try {
            return LlmProviderType.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid type '" + raw
                    + "'. Valid: OPENAI, ANTHROPIC, AZURE, LOCAL");
        }
    }
}

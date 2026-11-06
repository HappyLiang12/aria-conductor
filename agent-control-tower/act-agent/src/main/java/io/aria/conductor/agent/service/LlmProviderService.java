package io.aria.conductor.agent.service;

import io.aria.conductor.agent.dto.LlmProviderRequest;
import io.aria.conductor.agent.dto.LlmProviderResponse;
import io.aria.conductor.common.exception.ResourceNotFoundException;
import io.aria.conductor.common.model.LlmProvider;
import io.aria.conductor.agent.repository.LlmProviderRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
public class LlmProviderService {

    private final LlmProviderRepository providerRepository;

    public LlmProviderService(LlmProviderRepository providerRepository) {
        this.providerRepository = providerRepository;
    }

    @Transactional
    public LlmProviderResponse create(LlmProviderRequest request) {
        log.info("Creating LLM provider: name={}, type={}", request.getName(), request.getType());

        LlmProvider provider = LlmProvider.builder()
                .name(request.getName())
                .type(request.getType())
                .baseUrl(request.getBaseUrl())
                .apiKey(request.getApiKey())
                .defaultModel(request.getDefaultModel())
                .defaultMaxTokens(request.getMaxTokens() != null ? request.getMaxTokens() : 4096)
                .active(false)
                .build();

        LlmProvider saved = providerRepository.save(provider);
        log.info("LLM provider created: id={}", saved.getId());
        return toResponse(saved);
    }

    @Transactional(readOnly = true)
    public List<LlmProviderResponse> listAll() {
        return providerRepository.findAll().stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public LlmProviderResponse getById(UUID id) {
        return toResponse(findOrThrow(id));
    }

    @Transactional
    public LlmProviderResponse update(UUID id, LlmProviderRequest request) {
        LlmProvider provider = findOrThrow(id);
        log.info("Updating LLM provider: id={}", id);

        if (request.getName() != null) provider.setName(request.getName());
        if (request.getType() != null) provider.setType(request.getType());
        if (request.getBaseUrl() != null) provider.setBaseUrl(request.getBaseUrl());
        if (request.getApiKey() != null) provider.setApiKey(request.getApiKey());
        if (request.getDefaultModel() != null) provider.setDefaultModel(request.getDefaultModel());
        if (request.getMaxTokens() != null) provider.setDefaultMaxTokens(request.getMaxTokens());

        LlmProvider saved = providerRepository.save(provider);
        return toResponse(saved);
    }

    @Transactional
    public void delete(UUID id) {
        LlmProvider provider = findOrThrow(id);
        log.info("Deleting LLM provider: id={}, name={}", id, provider.getName());
        providerRepository.delete(provider);
    }

    @Transactional
    public LlmProviderResponse activate(UUID id) {
        LlmProvider provider = findOrThrow(id);
        log.info("Activating LLM provider: id={}, name={}", id, provider.getName());

        // If this provider is already active, no-op
        if (provider.isActive()) {
            log.info("LLM provider already active: id={}", id);
            return toResponse(provider);
        }

        // Deactivate any currently active provider (if different from target)
        providerRepository.findByActiveTrue().ifPresent(currentActive -> {
            if (!currentActive.getId().equals(provider.getId())) {
                currentActive.setActive(false);
                providerRepository.save(currentActive);
                log.info("Deactivated previous active provider: id={}", currentActive.getId());
            }
        });

        provider.setActive(true);
        LlmProvider saved = providerRepository.save(provider);
        log.info("LLM provider activated: id={}", saved.getId());
        return toResponse(saved);
    }

    @Transactional(readOnly = true)
    public LlmProvider getActiveProvider() {
        return providerRepository.findByActiveTrue().orElse(null);
    }

    /**
     * Test connectivity by sending a simple "hello" completion request.
     * Returns true if the provider responds successfully.
     */
    public boolean testConnection(UUID id) {
        LlmProvider provider = findOrThrow(id);
        log.info("Testing connection for LLM provider: id={}, name={}", id, provider.getName());

        try {
            java.net.http.HttpClient httpClient = java.net.http.HttpClient.newBuilder()
                    .connectTimeout(java.time.Duration.ofSeconds(10))
                    .build();

            String baseUrl = provider.getBaseUrl();
            if (baseUrl.endsWith("/")) {
                baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
            }

            String url = baseUrl + "/chat/completions";
            String authHeader = provider.getType().name().equals("AZURE") ? "api-key" : "Authorization";

            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            com.fasterxml.jackson.databind.node.ObjectNode body = mapper.createObjectNode();
            body.put("model", provider.getDefaultModel());
            body.put("max_tokens", 5);
            com.fasterxml.jackson.databind.node.ArrayNode messages = body.putArray("messages");
            com.fasterxml.jackson.databind.node.ObjectNode msg = messages.addObject();
            msg.put("role", "user");
            msg.put("content", "Hi");
            String jsonBody = mapper.writeValueAsString(body);

            String headerValue = provider.getType().name().equals("AZURE")
                    ? provider.getApiKey()
                    : "Bearer " + provider.getApiKey();

            java.net.http.HttpRequest httpRequest = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create(url))
                    .header("Content-Type", "application/json")
                    .header(authHeader, headerValue)
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString(jsonBody))
                    .timeout(java.time.Duration.ofSeconds(30))
                    .build();

            java.net.http.HttpResponse<String> response = httpClient.send(
                    httpRequest, java.net.http.HttpResponse.BodyHandlers.ofString());

            boolean success = response.statusCode() >= 200 && response.statusCode() < 300;
            if (success) {
                log.info("LLM provider connection test successful: id={}", id);
            } else {
                log.warn("LLM provider connection test failed: id={}, status={}, body={}",
                        id, response.statusCode(), response.body());
            }
            return success;
        } catch (Exception e) {
            log.error("LLM provider connection test error: id={}", id, e);
            return false;
        }
    }

    private LlmProvider findOrThrow(UUID id) {
        return providerRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("LlmProvider", id));
    }

    private LlmProviderResponse toResponse(LlmProvider provider) {
        return LlmProviderResponse.builder()
                .id(provider.getId())
                .name(provider.getName())
                .type(provider.getType())
                .baseUrl(provider.getBaseUrl())
                .apiKeyMasked(maskApiKey(provider.getApiKey()))
                .defaultModel(provider.getDefaultModel())
                .defaultMaxTokens(provider.getDefaultMaxTokens())
                .active(provider.isActive())
                .createdAt(provider.getCreatedAt())
                .updatedAt(provider.getUpdatedAt())
                .build();
    }

    private String maskApiKey(String apiKey) {
        if (apiKey == null || apiKey.length() <= 4) {
            return "****";
        }
        return "****" + apiKey.substring(apiKey.length() - 4);
    }
}

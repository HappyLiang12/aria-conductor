package io.aria.conductor.agent.dto;

import io.aria.conductor.common.model.LlmProviderType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LlmProviderResponse {

    private UUID id;
    private String name;
    private LlmProviderType type;
    private String baseUrl;
    private String apiKeyMasked;
    private String defaultModel;
    private int defaultMaxTokens;
    private boolean active;
    private Instant createdAt;
    private Instant updatedAt;
}

package io.aria.conductor.agent.dto;

import io.aria.conductor.common.model.LlmProviderType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LlmProviderRequest {

    @NotBlank(message = "Provider name is required")
    private String name;

    @NotNull(message = "Provider type is required")
    private LlmProviderType type;

    private String baseUrl;

    private String apiKey;

    private String defaultModel;

    private Integer maxTokens;
}

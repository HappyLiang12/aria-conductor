package io.aria.conductor.agent.dto;

import io.aria.conductor.common.model.AgentType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateAgentRequest {

    @NotBlank(message = "Agent name is required")
    private String name;

    private String description;

    @NotNull(message = "Agent type is required")
    private AgentType agentType;

    private String role;

    private String model;

    private String provider;

    private String adkProvider;

    private Map<String, Object> config;
}

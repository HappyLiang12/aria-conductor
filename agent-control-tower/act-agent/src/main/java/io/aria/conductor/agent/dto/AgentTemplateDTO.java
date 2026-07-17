package io.aria.conductor.agent.dto;

import io.aria.conductor.common.model.AgentType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentTemplateDTO {
    private String id;
    private String label;
    private AgentType agentType;
    private String role;
    private String model;
    private String provider;
    private String adkProvider;
    private String description;
}

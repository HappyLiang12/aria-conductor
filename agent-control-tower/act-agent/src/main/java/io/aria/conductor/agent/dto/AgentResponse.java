package io.aria.conductor.agent.dto;

import io.aria.conductor.common.model.AgentType;
import io.aria.conductor.common.model.HealthStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentResponse {

    private UUID id;
    private String name;
    private String description;
    private AgentType agentType;
    private String role;
    private String model;
    private String provider;
    private String adkProvider;
    private String config;
    private HealthStatus healthStatus;
    private Instant createdAt;
    private Instant updatedAt;
    private Instant retiredAt;
    private List<String> skills;
    private List<String> tools;
}

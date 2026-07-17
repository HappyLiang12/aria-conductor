package io.aria.conductor.agent.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UpdateAgentRequest {

    private String name;

    private String description;

    private String role;

    private String model;

    private String provider;

    private String adkProvider;

    private Map<String, Object> config;
}

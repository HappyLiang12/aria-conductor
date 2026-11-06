package io.aria.conductor.agent.dto;

import lombok.Builder;
import lombok.Data;

import java.util.Map;

@Data
@Builder
public class ReuseWorkflowRequest {
    private Map<String, String> parameters;
}

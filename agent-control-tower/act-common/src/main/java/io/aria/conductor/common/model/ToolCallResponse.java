package io.aria.conductor.common.model;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.UUID;

@Data
@Builder
public class ToolCallResponse {
    private UUID id;
    private UUID runId;
    private String toolName;
    private String arguments;
    private String result;
    private String status;
    private int latencyMs;
    private Instant createdAt;

    public static ToolCallResponse from(ToolCall tc) {
        return ToolCallResponse.builder()
                .id(tc.getId())
                .runId(tc.getRunId())
                .toolName(tc.getToolName())
                .arguments(tc.getArguments())
                .result(tc.getResult())
                .status(tc.getStatus().name())
                .latencyMs(tc.getLatencyMs())
                .createdAt(tc.getCreatedAt())
                .build();
    }
}

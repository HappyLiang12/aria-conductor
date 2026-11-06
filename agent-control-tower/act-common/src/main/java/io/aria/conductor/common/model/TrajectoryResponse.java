package io.aria.conductor.common.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.UUID;

@Data
@Builder
public class TrajectoryResponse {
    private UUID id;
    private UUID runId;
    private int turnNumber;
    private String role;
    private String content;
    private String toolCalls;
    private String toolCallId;
    private int inputTokens;
    private int outputTokens;
    private int latencyMs;
    private Instant createdAt;

    public static TrajectoryResponse from(SessionTrajectory t) {
        return TrajectoryResponse.builder()
                .id(t.getId())
                .runId(t.getRunId())
                .turnNumber(t.getTurnNumber())
                .role(t.getRole())
                .content(t.getContent())
                .toolCalls(formatToolCallsForDisplay(t.getToolCalls()))
                .toolCallId(t.getToolCallId())
                .inputTokens(t.getInputTokens())
                .outputTokens(t.getOutputTokens())
                .latencyMs(t.getLatencyMs())
                .createdAt(t.getCreatedAt())
                .build();
    }

    /**
     * If toolCalls is a JSON array (new format), extract human-readable
     * "name(args)" format for API consumers. Otherwise pass through as-is.
     */
    static String formatToolCallsForDisplay(String raw) {
        if (raw == null || raw.isBlank() || !raw.trim().startsWith("[")) return raw;
        try {
            ObjectMapper mapper = new ObjectMapper();
            JsonNode arr = mapper.readTree(raw);
            if (!arr.isArray()) return raw;
            StringBuilder sb = new StringBuilder();
            for (JsonNode tc : arr) {
                if (!sb.isEmpty()) sb.append(", ");
                sb.append(tc.path("name").asText("?"))
                  .append("(").append(tc.path("arguments").asText("{}")).append(")");
            }
            return sb.toString();
        } catch (Exception e) {
            return raw;
        }
    }
}

package io.aria.conductor.knowledge.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PromptCallStatsResponse {

    private UUID agentId;
    private long totalCalls;
    private long totalInputTokens;
    private long totalOutputTokens;
}

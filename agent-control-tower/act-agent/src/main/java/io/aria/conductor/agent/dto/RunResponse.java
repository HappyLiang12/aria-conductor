package io.aria.conductor.agent.dto;

import io.aria.conductor.common.model.RunStatus;
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
public class RunResponse {

    private UUID id;
    private UUID agentId;
    private RunStatus status;
    private String promptSeed;
    private int maxIterations;
    private long totalTokensUsed;
    private int iterationCount;
    private String errorMessage;
    private String finalOutput;
    private Instant createdAt;
    private Instant updatedAt;
    private Instant completedAt;
}

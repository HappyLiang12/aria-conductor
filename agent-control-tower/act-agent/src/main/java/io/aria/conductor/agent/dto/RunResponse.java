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
    /** Stable identity and repair surface of an actionable failure (null otherwise). */
    private String errorCode;
    private String errorSurface;
    private String finalOutput;
    /**
     * The frozen runtime of the run (Fleet Runtime Governance): the core and
     * placement the run was dispatched with, read from its immutable
     * {@code RunExecutionBinding} row. Null when the run has no binding (legacy
     * runs, or runs not yet dispatched) -- the dashboard renders '-'.
     */
    private String coreId;
    private String executionMode;
    private Instant createdAt;
    private Instant updatedAt;
    private Instant completedAt;
}

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
    /**
     * The usage the provider actually reported (F9), or null when this run never
     * observed any. The stored total defaults to 0, so a primitive here claimed a
     * zero cost for every run whose core reported nothing; a genuine reported zero
     * still arrives as 0.
     */
    private Long totalTokensUsed;
    /**
     * Provider-reported input tokens (Task 7 / spec 5.4), or null when this run
     * reported no measurement. An all-zero placeholder report is null here, never 0:
     * the API must not hand a consumer a number the provider never stated.
     */
    private Long inputTokens;
    /** Provider-reported output tokens; null for the same reason as {@link #inputTokens}. */
    private Long outputTokens;
    /**
     * {@code REPORTED} when the run reported a real measurement, {@code NOT_REPORTED}
     * when it never did (never observed, or the pinned CLI's all-zero placeholder).
     * The UI shows "not reported" from this field instead of rendering a zero cost.
     */
    private String usageState;
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

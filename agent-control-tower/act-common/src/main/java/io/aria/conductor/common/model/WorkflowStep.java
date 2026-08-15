package io.aria.conductor.common.model;

import lombok.*;

import java.util.UUID;

/**
 * A single step within a {@link WorkflowChain}.
 * Stored as part of the steps_json CLOB, not a separate table.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WorkflowStep {

    public enum Status { PENDING, RUNNING, COMPLETED, FAILED, SKIPPED }

    /** Semantic role of the step; drives SDD routing. Null-safe via @Builder.Default. */
    public enum StepKind { GENERIC, BA, DEV, QA, CODE_REVIEW }

    /** Agent to execute this step. */
    private UUID agentId;

    /** Prompt template; may contain {@code {previousOutput}} and {@code {specRef}} placeholders. */
    private String promptTemplate;

    /** Max LLM iterations for this step's run. */
    @Builder.Default
    private int maxIterations = 3;

    /** Semantic kind for SDD routing. Defaults to GENERIC (existing behaviour). */
    @Builder.Default
    private StepKind kind = StepKind.GENERIC;

    /** Number of times this step has been (re)scheduled. */
    @Builder.Default
    private int attemptCount = 0;

    /** Run ID once this step has been started. */
    private UUID runId;

    /** Current step status. */
    @Builder.Default
    private Status status = Status.PENDING;

    /** The finalOutput from this step's run (populated on completion). */
    private String output;
}

package io.aria.conductor.agent.dto;

import io.aria.conductor.common.model.WorkflowChain;
import io.aria.conductor.common.model.WorkflowStep;
import lombok.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WorkflowResponse {

    private UUID id;
    private String name;
    private WorkflowChain.Status status;
    private int currentStepIndex;
    private int totalSteps;
    private List<StepInfo> steps;
    private Instant createdAt;
    private Instant completedAt;
    private boolean isTemplate;
    private UUID knowledgeItemId;
    private String description;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class StepInfo {
        private int index;
        private UUID agentId;
        private String promptTemplate;
        private WorkflowStep.Status status;
        private UUID runId;
        private String outputPreview;
    }
}

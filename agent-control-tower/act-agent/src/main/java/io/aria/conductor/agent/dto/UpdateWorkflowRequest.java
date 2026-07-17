package io.aria.conductor.agent.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class UpdateWorkflowRequest {
    private String name;
    private String description;
    private List<CreateWorkflowRequest.StepDef> steps;
}

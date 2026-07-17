package io.aria.conductor.agent.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.UUID;

@Data
@Builder
public class MergeWorkflowRequest {
    @NotEmpty
    private List<UUID> sourceIds;
    @NotBlank
    private String name;
}

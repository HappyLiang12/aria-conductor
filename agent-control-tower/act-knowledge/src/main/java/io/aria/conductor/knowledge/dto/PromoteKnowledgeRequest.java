package io.aria.conductor.knowledge.dto;

import io.aria.conductor.common.model.KnowledgeType;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PromoteKnowledgeRequest {

    @NotNull(message = "Target type is required")
    private KnowledgeType targetType;

    private String targetName;
}

package io.aria.conductor.knowledge.dto;

import io.aria.conductor.common.model.KnowledgeType;
import io.aria.conductor.common.model.Sensitivity;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateKnowledgeRequest {

    @NotBlank(message = "Name is required")
    private String name;

    @NotNull(message = "Type is required")
    private KnowledgeType type;

    private String description;

    @NotBlank(message = "Content is required")
    private String content;

    /** Optional YAML content for WORKFLOW-type templates. Stored in knowledge_versions.yaml_content. */
    private String yamlContent;

    private Sensitivity sensitivity;
}

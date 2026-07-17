package io.aria.conductor.knowledge.dto;

import io.aria.conductor.common.model.Sensitivity;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UpdateKnowledgeRequest {

    private String description;

    private String content;

    private Sensitivity sensitivity;
}

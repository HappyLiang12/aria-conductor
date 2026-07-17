package io.aria.conductor.knowledge.dto;

import io.aria.conductor.common.model.KnowledgeStatus;
import io.aria.conductor.common.model.KnowledgeType;
import io.aria.conductor.common.model.Sensitivity;
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
public class KnowledgeItemResponse {

    private UUID id;
    private String name;
    private KnowledgeType type;
    private String description;
    private String currentVersion;
    private KnowledgeStatus status;
    private Sensitivity sensitivity;
    private String filePath;
    private Instant createdAt;
    private Instant updatedAt;
    private Instant retiredAt;
    private KnowledgeVersionResponse latestVersion;
}

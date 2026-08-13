package io.aria.conductor.knowledge.dto;

import io.aria.conductor.common.model.VersionStatus;
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
public class KnowledgeVersionResponse {

    private UUID id;
    private String version;
    private VersionStatus status;
    private String content;
    private String yamlContent;
    private Instant createdAt;
    private Instant approvedAt;
}

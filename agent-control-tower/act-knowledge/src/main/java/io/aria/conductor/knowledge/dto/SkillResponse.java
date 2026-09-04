package io.aria.conductor.knowledge.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SkillResponse {
    private String id;
    private String name;
    private String description;
    private String template;
    private String triggerConditions;
    private String examples;
    private String stage;
    private boolean enabled;
    private String tier;
    private int usageCount;
    private String knowledgeItemId;
    private Instant createdAt;
    private Instant updatedAt;
}

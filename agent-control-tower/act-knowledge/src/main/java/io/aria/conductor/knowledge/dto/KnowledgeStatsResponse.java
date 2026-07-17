package io.aria.conductor.knowledge.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KnowledgeStatsResponse {

    private long totalItems;
    private Map<String, Long> countByType;
    private Map<String, Long> countByStatus;
}

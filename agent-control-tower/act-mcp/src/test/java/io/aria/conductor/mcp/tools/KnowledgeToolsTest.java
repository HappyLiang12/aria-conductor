package io.aria.conductor.mcp.tools;

import io.aria.conductor.common.model.KnowledgeType;
import io.aria.conductor.common.model.KnowledgeStatus;
import io.aria.conductor.execution.mcp.McpProperties;
import io.aria.conductor.knowledge.dto.KnowledgeItemResponse;
import io.aria.conductor.knowledge.service.KnowledgeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KnowledgeToolsTest {

    @Mock KnowledgeService knowledgeService;
    McpProperties mcpProperties;
    KnowledgeTools tools;

    @BeforeEach
    void setUp() {
        mcpProperties = new McpProperties();
        tools = new KnowledgeTools(knowledgeService, mcpProperties);
    }

    @Test
    void listKnowledge_delegatesWithFilters() {
        when(knowledgeService.listKnowledge(KnowledgeType.WORKFLOW, KnowledgeStatus.APPROVED))
                .thenReturn(List.of(KnowledgeItemResponse.builder()
                        .id(UUID.randomUUID()).name("development-workflow").build()));

        String json = tools.listKnowledge("WORKFLOW", "APPROVED");

        verify(knowledgeService).listKnowledge(KnowledgeType.WORKFLOW, KnowledgeStatus.APPROVED);
        assertThat(json).contains("development-workflow").contains("\"ok\":true");
    }

    @Test
    void listKnowledge_blankFiltersListAll() {
        when(knowledgeService.listKnowledge(isNull(), isNull())).thenReturn(List.of());

        String json = tools.listKnowledge(null, null);

        assertThat(json).contains("\"ok\":true");
    }
}

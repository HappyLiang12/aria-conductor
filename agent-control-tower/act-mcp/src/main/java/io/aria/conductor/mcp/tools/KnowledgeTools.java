package io.aria.conductor.mcp.tools;

import io.aria.conductor.common.model.KnowledgeType;
import io.aria.conductor.common.model.KnowledgeStatus;
import io.aria.conductor.execution.mcp.McpProperties;
import io.aria.conductor.knowledge.service.KnowledgeService;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class KnowledgeTools implements McpTool {

    private final KnowledgeService knowledgeService;
    private final McpProperties mcpProperties;

    @Tool(name = "list_knowledge",
            description = "List knowledge items. Optional type (WORKFLOW/SKILL/DOCUMENT/TOOL/PROMPT) and status (PENDING/APPROVED/REJECTED/RETIRED).")
    public String listKnowledge(
            @ToolParam(description = "KnowledgeType name or blank", required = false) String type,
            @ToolParam(description = "KnowledgeStatus name or blank", required = false) String status) {
        try {
            KnowledgeType t = type == null || type.isBlank() ? null : KnowledgeType.valueOf(type.trim().toUpperCase());
            KnowledgeStatus s = status == null || status.isBlank() ? null : KnowledgeStatus.valueOf(status.trim().toUpperCase());
            return ToolResponses.ok(knowledgeService.listKnowledge(t, s));
        } catch (IllegalArgumentException e) {
            return ToolResponses.error("VALIDATION", e.getMessage(), e, mcpProperties.isDebug());
        } catch (Exception e) {
            return ToolResponses.error("KNOWLEDGE_LIST_FAILED", e.getMessage(), e, mcpProperties.isDebug());
        }
    }
}

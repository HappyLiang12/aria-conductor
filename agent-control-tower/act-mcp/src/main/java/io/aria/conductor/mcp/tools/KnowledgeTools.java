package io.aria.conductor.mcp.tools;

import io.aria.conductor.common.exception.InvalidStateTransitionException;
import io.aria.conductor.common.exception.ResourceNotFoundException;
import io.aria.conductor.common.model.KnowledgeType;
import io.aria.conductor.common.model.KnowledgeStatus;
import io.aria.conductor.execution.mcp.McpProperties;
import io.aria.conductor.knowledge.dto.CreateKnowledgeRequest;
import io.aria.conductor.knowledge.dto.KnowledgeItemResponse;
import io.aria.conductor.knowledge.dto.ReviewDecisionRequest;
import io.aria.conductor.knowledge.service.KnowledgeService;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class KnowledgeTools implements McpTool {

    private final KnowledgeService knowledgeService;
    private final McpProperties mcpProperties;

    @Tool(name = "list_knowledge",
            description = "List knowledge items. Optional type (SKILL/SCRIPT/PROMPT/TOOL/TEMPLATE/GUIDELINE/WORKFLOW/SPEC) and status (DRAFT/PENDING/APPROVED/REJECTED/RETIRED).")
    public String listKnowledge(
            @ToolParam(description = "KnowledgeType name or blank", required = false) String type,
            @ToolParam(description = "KnowledgeStatus name or blank", required = false) String status) {
        try {
            KnowledgeType t = type == null || type.isBlank() ? null : parseType(type);
            KnowledgeStatus s = status == null || status.isBlank() ? null : parseStatus(status);
            return ToolResponses.ok(knowledgeService.listKnowledge(t, s));
        } catch (IllegalArgumentException e) {
            return ToolResponses.error("VALIDATION", e.getMessage(), e, mcpProperties.isDebug());
        } catch (Exception e) {
            return ToolResponses.error("KNOWLEDGE_LIST_FAILED", e.getMessage(), e, mcpProperties.isDebug());
        }
    }

    @Tool(name = "store_knowledge",
            description = "Submit a new knowledge item for human review. name and content are required; type defaults to SKILL (SKILL/SCRIPT/PROMPT/TOOL/TEMPLATE/GUIDELINE/WORKFLOW/SPEC). The item is stored as PENDING and is NOT usable by agents until a human approves it via review_knowledge.")
    public String storeKnowledge(
            @ToolParam(description = "Knowledge item name") String name,
            @ToolParam(description = "Knowledge content (markdown, code or YAML)") String content,
            @ToolParam(description = "KnowledgeType name, default SKILL", required = false) String type,
            @ToolParam(description = "Short description", required = false) String description) {
        try {
            requireText(name, "name");
            requireText(content, "content");
            KnowledgeType t = type == null || type.isBlank() ? KnowledgeType.SKILL : parseType(type);
            CreateKnowledgeRequest request = CreateKnowledgeRequest.builder()
                    .name(name)
                    .type(t)
                    .content(content)
                    .description(description)
                    .build();
            return ToolResponses.ok(knowledgeService.submitKnowledge(request));
        } catch (IllegalArgumentException e) {
            return ToolResponses.error("VALIDATION", e.getMessage(), e, mcpProperties.isDebug());
        } catch (Exception e) {
            return ToolResponses.error("KNOWLEDGE_STORE_FAILED", e.getMessage(), e, mcpProperties.isDebug());
        }
    }

    @Tool(name = "query_knowledge",
            description = "Agent-facing knowledge search: returns up to 20 APPROVED, non-retired items whose name, description or current version content contains the query (case-insensitive). A blank query returns the approved items unranked.")
    public String queryKnowledge(
            @ToolParam(description = "Keyword to search for; blank returns approved items as-is", required = false) String query) {
        try {
            List<KnowledgeItemResponse> approved = knowledgeService.listKnowledge(null, KnowledgeStatus.APPROVED);
            String keyword = query == null ? "" : query.trim().toLowerCase();
            List<KnowledgeItemResponse> matches = approved.stream()
                    .filter(item -> keyword.isEmpty() || matchesQuery(item, keyword))
                    .limit(20)
                    .toList();
            return ToolResponses.ok(matches);
        } catch (Exception e) {
            return ToolResponses.error("KNOWLEDGE_QUERY_FAILED", e.getMessage(), e, mcpProperties.isDebug());
        }
    }

    @Tool(name = "review_knowledge",
            description = "Approve or reject a PENDING knowledge item. decision is APPROVED or REJECTED; reason is optional and recorded for rejections. Only APPROVED items become usable by agents.")
    public String reviewKnowledge(
            @ToolParam(description = "KnowledgeItem id (UUID)") String id,
            @ToolParam(description = "APPROVED or REJECTED") String decision,
            @ToolParam(description = "Review reason", required = false) String reason) {
        try {
            requireText(id, "id");
            requireText(decision, "decision");
            ReviewDecisionRequest request = ReviewDecisionRequest.builder()
                    .decision(parseDecision(decision))
                    .reason(reason)
                    .build();
            return ToolResponses.ok(knowledgeService.reviewKnowledge(UUID.fromString(id), request));
        } catch (ResourceNotFoundException e) {
            return ToolResponses.error("NOT_FOUND", e.getMessage(), e, mcpProperties.isDebug());
        } catch (IllegalArgumentException e) {
            return ToolResponses.error("VALIDATION", e.getMessage(), e, mcpProperties.isDebug());
        } catch (InvalidStateTransitionException | IllegalStateException e) {
            // Parity with GlobalExceptionHandler: both map to 409 CONFLICT over REST.
            return ToolResponses.error("CONFLICT", e.getMessage(), e, mcpProperties.isDebug());
        } catch (Exception e) {
            return ToolResponses.error("KNOWLEDGE_REVIEW_FAILED", e.getMessage(), e, mcpProperties.isDebug());
        }
    }

    @Tool(name = "retire_knowledge",
            description = "Retire an APPROVED knowledge item so agents can no longer use it. Only APPROVED items can be retired.")
    public String retireKnowledge(@ToolParam(description = "KnowledgeItem id (UUID)") String id) {
        try {
            requireText(id, "id");
            return ToolResponses.ok(knowledgeService.retireKnowledge(UUID.fromString(id)));
        } catch (ResourceNotFoundException e) {
            return ToolResponses.error("NOT_FOUND", e.getMessage(), e, mcpProperties.isDebug());
        } catch (IllegalArgumentException e) {
            return ToolResponses.error("VALIDATION", e.getMessage(), e, mcpProperties.isDebug());
        } catch (InvalidStateTransitionException | IllegalStateException e) {
            return ToolResponses.error("CONFLICT", e.getMessage(), e, mcpProperties.isDebug());
        } catch (Exception e) {
            return ToolResponses.error("KNOWLEDGE_RETIRE_FAILED", e.getMessage(), e, mcpProperties.isDebug());
        }
    }

    private static KnowledgeType parseType(String raw) {
        try {
            return KnowledgeType.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid type '" + raw
                    + "'. Valid: SKILL, SCRIPT, PROMPT, TOOL, TEMPLATE, GUIDELINE, WORKFLOW, SPEC");
        }
    }

    private static KnowledgeStatus parseStatus(String raw) {
        try {
            return KnowledgeStatus.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid status '" + raw
                    + "'. Valid: DRAFT, PENDING, APPROVED, REJECTED, RETIRED");
        }
    }

    private static ReviewDecisionRequest.ReviewDecision parseDecision(String raw) {
        try {
            return ReviewDecisionRequest.ReviewDecision.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid decision '" + raw
                    + "'. Valid: APPROVED, REJECTED");
        }
    }

    private static boolean matchesQuery(KnowledgeItemResponse item, String keyword) {
        if (item.getName() != null && item.getName().toLowerCase().contains(keyword)) {
            return true;
        }
        if (item.getDescription() != null && item.getDescription().toLowerCase().contains(keyword)) {
            return true;
        }
        String content = item.getLatestVersion() != null ? item.getLatestVersion().getContent() : null;
        return content != null && content.toLowerCase().contains(keyword);
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
    }
}

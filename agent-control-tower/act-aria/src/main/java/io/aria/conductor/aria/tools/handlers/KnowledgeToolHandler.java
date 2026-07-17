package io.aria.conductor.aria.tools.handlers;

import io.aria.conductor.common.model.KnowledgeItem;
import io.aria.conductor.common.model.KnowledgeStatus;
import io.aria.conductor.common.model.KnowledgeType;
import io.aria.conductor.execution.tool.ToolHandler;
import io.aria.conductor.knowledge.dto.CreateKnowledgeRequest;
import io.aria.conductor.knowledge.dto.KnowledgeItemResponse;
import io.aria.conductor.knowledge.dto.ReviewDecisionRequest;
import io.aria.conductor.knowledge.repository.KnowledgeItemRepository;
import io.aria.conductor.knowledge.service.KnowledgeService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Component("knowledgeToolHandler")
public class KnowledgeToolHandler implements ToolHandler {

    private final KnowledgeService knowledgeService;
    private final KnowledgeItemRepository knowledgeItemRepository;

    public KnowledgeToolHandler(KnowledgeService knowledgeService,
                                KnowledgeItemRepository knowledgeItemRepository) {
        this.knowledgeService = knowledgeService;
        this.knowledgeItemRepository = knowledgeItemRepository;
    }

    @Override
    public String execute(Map<String, Object> arguments) {
        String toolName = Objects.toString(arguments.get("toolName"), "");
        try {
            return switch (toolName) {
                case "create_knowledge" -> createKnowledge(arguments);
                case "store_knowledge" -> createKnowledge(arguments);
                case "search_knowledge" -> searchKnowledge(arguments);
                case "list_knowledge" -> listKnowledge(arguments);
                case "query_knowledge" -> searchKnowledge(arguments);
                case "review_knowledge" -> reviewKnowledge(arguments);
                case "retire_knowledge" -> retireKnowledge(arguments);
                default -> error("Unknown tool: " + toolName);
            };
        } catch (Exception e) {
            log.error("KnowledgeToolHandler failed for {}", toolName, e);
            return error(e.getMessage());
        }
    }

    private String createKnowledge(Map<String, Object> args) {
        String name = Objects.toString(args.get("name"), "");
        String content = Objects.toString(args.get("content"), "");
        String typeStr = Objects.toString(args.get("type"), "");
        if (name.isEmpty()) return error("Missing required parameter: name");
        if (content.isEmpty()) return error("Missing required parameter: content");

        KnowledgeType type = KnowledgeType.SKILL;
        if (!typeStr.isEmpty()) {
            try {
                type = KnowledgeType.valueOf(typeStr.toUpperCase());
            } catch (IllegalArgumentException e) {
                return error("Invalid type: " + typeStr + ". Valid: " + Arrays.toString(KnowledgeType.values()));
            }
        }

        CreateKnowledgeRequest request = CreateKnowledgeRequest.builder()
                .name(name)
                .type(type)
                .content(content)
                .description(Objects.toString(args.get("description"), ""))
                .build();
        KnowledgeItemResponse response = knowledgeService.submitKnowledge(request);
        return "Knowledge '" + name + "' created (id: " + response.getId() + ", status: PENDING)";
    }

    private String searchKnowledge(Map<String, Object> args) {
        String keyword = Objects.toString(args.get("keyword"), "");
        String typeStr = Objects.toString(args.get("type"), "");

        KnowledgeType type = null;
        if (!typeStr.isEmpty()) {
            try {
                type = KnowledgeType.valueOf(typeStr.toUpperCase());
            } catch (IllegalArgumentException e) {
                return error("Invalid type: " + typeStr + ". Valid: " + Arrays.toString(KnowledgeType.values()));
            }
        }

        List<KnowledgeItem> results;
        if (type != null) {
            results = knowledgeItemRepository.findByTypeAndStatus(type, KnowledgeStatus.APPROVED).stream()
                    .filter(k -> keyword.isEmpty()
                            || k.getName().toLowerCase().contains(keyword.toLowerCase())
                            || (k.getDescription() != null && k.getDescription().toLowerCase().contains(keyword.toLowerCase())))
                    .collect(Collectors.toList());
        } else {
            results = knowledgeItemRepository.findByStatus(KnowledgeStatus.APPROVED).stream()
                    .filter(k -> keyword.isEmpty()
                            || k.getName().toLowerCase().contains(keyword.toLowerCase())
                            || (k.getDescription() != null && k.getDescription().toLowerCase().contains(keyword.toLowerCase())))
                    .collect(Collectors.toList());
        }

        if (results.isEmpty()) {
            return keyword.isEmpty()
                    ? "No approved knowledge items found."
                    : "No knowledge items found matching keyword: " + keyword;
        }

        StringBuilder sb = new StringBuilder("Knowledge items matching '" + keyword + "' (" + results.size() + " total):\n");
        for (KnowledgeItem k : results) {
            sb.append("  - ").append(k.getName())
                    .append(" | Type: ").append(k.getType())
                    .append(" | Status: ").append(k.getStatus())
                    .append("\n");
        }
        return sb.toString().trim();
    }

    private String listKnowledge(Map<String, Object> args) {
        String typeStr = Objects.toString(args.get("type"), "");
        String statusStr = Objects.toString(args.get("status"), "");

        KnowledgeType type = null;
        if (!typeStr.isEmpty()) {
            try {
                type = KnowledgeType.valueOf(typeStr.toUpperCase());
            } catch (IllegalArgumentException e) {
                return error("Invalid type: " + typeStr + ". Valid: " + Arrays.toString(KnowledgeType.values()));
            }
        }

        KnowledgeStatus status = null;
        if (!statusStr.isEmpty()) {
            try {
                status = KnowledgeStatus.valueOf(statusStr.toUpperCase());
            } catch (IllegalArgumentException e) {
                return error("Invalid status: " + statusStr + ". Valid: " + Arrays.toString(KnowledgeStatus.values()));
            }
        }

        List<KnowledgeItemResponse> items = knowledgeService.listKnowledge(type, status);
        if (items.isEmpty()) return "No knowledge items found.";

        StringBuilder sb = new StringBuilder("Knowledge items (" + items.size() + " total):\n");
        for (KnowledgeItemResponse item : items) {
            sb.append("  - ").append(item.getName())
                    .append(" | Type: ").append(item.getType() != null ? item.getType().name() : "N/A")
                    .append(" | Status: ").append(item.getStatus() != null ? item.getStatus().name() : "N/A")
                    .append("\n");
        }
        return sb.toString().trim();
    }

    private String reviewKnowledge(Map<String, Object> args) {
        String id = Objects.toString(args.get("id"), "");
        String decision = Objects.toString(args.get("decision"), "");
        if (id.isEmpty()) return error("Missing required parameter: id");
        if (decision.isEmpty()) return error("Missing required parameter: decision");

        UUID uuid = UUID.fromString(id);
        ReviewDecisionRequest.ReviewDecision dec;
        try {
            dec = ReviewDecisionRequest.ReviewDecision.valueOf(decision.toUpperCase());
        } catch (IllegalArgumentException e) {
            return error("Invalid decision: " + decision + ". Valid: APPROVED, REJECTED");
        }

        ReviewDecisionRequest request = ReviewDecisionRequest.builder()
                .decision(dec)
                .reason(Objects.toString(args.get("reason"), ""))
                .build();
        KnowledgeItemResponse response = knowledgeService.reviewKnowledge(uuid, request);
        return "Knowledge " + id + " reviewed. Decision: " + dec.name() + ". Status: " + response.getStatus();
    }

    private String retireKnowledge(Map<String, Object> args) {
        String id = Objects.toString(args.get("id"), "");
        if (id.isEmpty()) return error("Missing required parameter: id");
        UUID uuid = UUID.fromString(id);
        KnowledgeItemResponse response = knowledgeService.retireKnowledge(uuid);
        return "Knowledge " + id + " retired successfully. Status: " + (response.getStatus() != null ? response.getStatus().name() : "UNKNOWN");
    }

    private String error(String msg) {
        return "Error: " + msg;
    }
}

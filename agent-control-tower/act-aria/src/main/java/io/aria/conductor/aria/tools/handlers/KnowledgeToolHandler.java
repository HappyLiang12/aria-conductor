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
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.util.*;

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
                case "search_knowledge" -> searchKnowledge(arguments, null);
                case "list_knowledge" -> listKnowledge(arguments);
                case "query_knowledge" -> searchKnowledge(arguments, KnowledgeStatus.APPROVED);
                case "review_knowledge" -> reviewKnowledge(arguments);
                case "retire_knowledge" -> retireKnowledge(arguments);
                case "find_knowledge" -> findKnowledge(arguments);
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

    private String searchKnowledge(Map<String, Object> args, KnowledgeStatus statusFilter) {
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

        // #31: search name + description + version content at the DB layer. A null statusFilter
        // includes PENDING/DRAFT/APPROVED (operator-facing search_knowledge) so freshly stored
        // items are found; APPROVED restricts the agent-facing query_knowledge path.
        String kw = keyword.isBlank() ? null : "%" + keyword.toLowerCase() + "%";
        List<KnowledgeItem> results = knowledgeItemRepository.searchByKeyword(
                kw, type, statusFilter, PageRequest.of(0, 20));

        if (results.isEmpty()) {
            return keyword.isEmpty()
                    ? "No knowledge items found."
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

        ReviewDecisionRequest.ReviewDecision dec;
        try {
            dec = ReviewDecisionRequest.ReviewDecision.valueOf(decision.toUpperCase());
        } catch (IllegalArgumentException e) {
            return error("Invalid decision: " + decision + ". Valid: APPROVED, REJECTED");
        }

        Resolution resolution = resolveIdOrName(id);
        if (resolution.missing()) return error("Missing required parameter: id");
        if (resolution.error() != null) return error(resolution.error());

        ReviewDecisionRequest request = ReviewDecisionRequest.builder()
                .decision(dec)
                .reason(Objects.toString(args.get("reason"), ""))
                .build();
        KnowledgeItemResponse response = knowledgeService.reviewKnowledge(resolution.uuid(), request);
        return "Knowledge " + id + " reviewed. Decision: " + dec.name() + ". Status: " + response.getStatus();
    }

    private String retireKnowledge(Map<String, Object> args) {
        String id = Objects.toString(args.get("id"), "");
        if (id.isEmpty()) return error("Missing required parameter: id");
        Resolution resolution = resolveIdOrName(id);
        if (resolution.missing()) return error("Missing required parameter: id");
        if (resolution.error() != null) return error(resolution.error());
        KnowledgeItemResponse response = knowledgeService.retireKnowledge(resolution.uuid());
        return "Knowledge " + id + " retired successfully. Status: " + (response.getStatus() != null ? response.getStatus().name() : "UNKNOWN");
    }

    private String findKnowledge(Map<String, Object> args) {
        String name = Objects.toString(args.get("name"), "");
        if (name.isBlank()) return error("Missing required parameter: name");
        List<KnowledgeItem> matches = knowledgeItemRepository.findByName(name.trim());
        if (matches.isEmpty()) return error("Knowledge not found: " + name.trim());

        StringBuilder sb = new StringBuilder();
        for (KnowledgeItem k : matches) {
            if (sb.length() > 0) sb.append("\n");
            sb.append(k.getName()).append(" | ")
                    .append(k.getId()).append(" | ")
                    .append(k.getType()).append(" | ")
                    .append(k.getStatus());
        }
        return sb.toString();
    }

    /**
     * Resolve a knowledge id-or-name to a UUID, mirroring {@code AgentToolHandler.resolveAgentId}
     * (#38). A value that parses as a UUID is used directly; otherwise it is treated as a name and
     * looked up via {@link KnowledgeItemRepository#findByName}. Names are not unique, so multiple
     * matches surface the candidates instead of silently acting on an arbitrary row.
     */
    private Resolution resolveIdOrName(String idOrName) {
        if (idOrName == null || idOrName.isBlank()) return Resolution.unresolved();
        try {
            return Resolution.of(UUID.fromString(idOrName.trim()));
        } catch (IllegalArgumentException e) {
            String name = idOrName.trim();
            List<KnowledgeItem> matches = knowledgeItemRepository.findByName(name);
            if (matches.isEmpty()) {
                return Resolution.failure("Knowledge not found: " + name);
            }
            if (matches.size() > 1) {
                StringBuilder sb = new StringBuilder("Multiple knowledge items found with name '")
                        .append(name).append("'. Specify the UUID: ");
                for (int i = 0; i < matches.size(); i++) {
                    if (i > 0) sb.append(", ");
                    KnowledgeItem k = matches.get(i);
                    sb.append(k.getName()).append(" | ").append(k.getId())
                            .append(" | ").append(k.getType()).append(" | ").append(k.getStatus());
                }
                return Resolution.failure(sb.toString());
            }
            return Resolution.of(matches.get(0).getId());
        }
    }

    /** Result of id-or-name resolution: either a UUID, a missing-parameter marker, or an error message. */
    private record Resolution(UUID uuid, boolean missing, String error) {
        static Resolution of(UUID uuid) {
            return new Resolution(uuid, false, null);
        }

        static Resolution unresolved() {
            return new Resolution(null, true, null);
        }

        static Resolution failure(String error) {
            return new Resolution(null, false, error);
        }
    }

    private String error(String msg) {
        return "Error: " + msg;
    }
}

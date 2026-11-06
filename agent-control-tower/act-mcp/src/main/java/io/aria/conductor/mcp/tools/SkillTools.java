package io.aria.conductor.mcp.tools;

import io.aria.conductor.agent.service.AgentService;
import io.aria.conductor.common.exception.InvalidStateTransitionException;
import io.aria.conductor.common.exception.ResourceNotFoundException;
import io.aria.conductor.execution.mcp.McpProperties;
import io.aria.conductor.knowledge.dto.SkillResponse;
import io.aria.conductor.knowledge.selfimprove.SkillDefinition;
import io.aria.conductor.knowledge.selfimprove.SkillDefinitionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Skill lifecycle tools. Thin wrappers over the same SkillDefinitionRepository
 * and AgentService that SkillController and AgentController call — REST/dashboard
 * and MCP stay at parity, including the SkillResponse projection. Error types
 * mirror GlobalExceptionHandler's REST status mapping (409 CONFLICT for
 * InvalidStateTransitionException/IllegalStateException).
 */
@Component
@RequiredArgsConstructor
public class SkillTools implements McpTool {

    private static final Set<String> STAGES = Set.of("SKILL", "SCRIPT", "WORKFLOW");

    private final SkillDefinitionRepository skillRepo;
    private final AgentService agentService;
    private final McpProperties mcpProperties;

    @Tool(name = "list_skills",
            description = "List skills. Optional stage (SKILL/SCRIPT/WORKFLOW) and enabled flag (true = enabled only, false = disabled only); omit both to list all.")
    public String listSkills(
            @ToolParam(description = "SKILL, SCRIPT or WORKFLOW; blank lists every stage", required = false) String stage,
            @ToolParam(description = "true = enabled only, false = disabled only, blank for both", required = false) Boolean enabled) {
        try {
            String s = stage == null || stage.isBlank() ? null : parseStage(stage);
            List<SkillDefinition> skills;
            if (s != null) {
                if (Boolean.TRUE.equals(enabled)) {
                    skills = skillRepo.findByStageAndEnabledTrue(s);
                } else if (Boolean.FALSE.equals(enabled)) {
                    skills = skillRepo.findByStageAndEnabledFalse(s);
                } else {
                    skills = skillRepo.findByStage(s);
                }
            } else if (Boolean.TRUE.equals(enabled)) {
                skills = skillRepo.findByEnabledTrue();
            } else if (Boolean.FALSE.equals(enabled)) {
                skills = skillRepo.findByEnabledFalse();
            } else {
                skills = skillRepo.findAll();
            }
            return ToolResponses.ok(skills.stream().map(SkillTools::toResponse).toList());
        } catch (IllegalArgumentException e) {
            return ToolResponses.error("VALIDATION", e.getMessage(), e, mcpProperties.isDebug());
        } catch (Exception e) {
            return ToolResponses.error("SKILL_LIST_FAILED", e.getMessage(), e, mcpProperties.isDebug());
        }
    }

    @Tool(name = "get_skill",
            description = "Get one skill by id: template, trigger conditions, examples, stage (SKILL/SCRIPT/WORKFLOW), enabled, tier and usageCount.")
    public String getSkill(@ToolParam(description = "Skill id") String id) {
        try {
            SkillDefinition skill = skillRepo.findById(id)
                    .orElseThrow(() -> new ResourceNotFoundException("Skill", id));
            return ToolResponses.ok(toResponse(skill));
        } catch (ResourceNotFoundException e) {
            return ToolResponses.error("NOT_FOUND", e.getMessage(), e, mcpProperties.isDebug());
        } catch (Exception e) {
            return ToolResponses.error("SKILL_READ_FAILED", e.getMessage(), e, mcpProperties.isDebug());
        }
    }

    @Tool(name = "toggle_skill",
            description = "Flip a skill's enabled flag. Returns the skill with its new enabled state.")
    public String toggleSkill(@ToolParam(description = "Skill id") String id) {
        try {
            int updated = skillRepo.toggleEnabled(id, Instant.now());
            if (updated == 0) {
                throw new ResourceNotFoundException("Skill", id);
            }
            SkillDefinition skill = skillRepo.findById(id)
                    .orElseThrow(() -> new ResourceNotFoundException("Skill", id));
            return ToolResponses.ok(toResponse(skill));
        } catch (ResourceNotFoundException e) {
            return ToolResponses.error("NOT_FOUND", e.getMessage(), e, mcpProperties.isDebug());
        } catch (Exception e) {
            return ToolResponses.error("SKILL_TOGGLE_FAILED", e.getMessage(), e, mcpProperties.isDebug());
        }
    }

    @Tool(name = "list_agent_skills",
            description = "List the enabled skills assigned to an agent. Use list_agents to obtain agentId.")
    public String listAgentSkills(@ToolParam(description = "Agent id") UUID agentId) {
        try {
            return ToolResponses.ok(agentService.getAgentSkills(agentId));
        } catch (ResourceNotFoundException e) {
            return ToolResponses.error("NOT_FOUND", e.getMessage(), e, mcpProperties.isDebug());
        } catch (Exception e) {
            return ToolResponses.error("AGENT_SKILL_LIST_FAILED", e.getMessage(), e, mcpProperties.isDebug());
        }
    }

    @Tool(name = "assign_skill",
            description = "Assign an enabled SKILL-stage skill to an agent (idempotent). Only approved/enabled SKILL-stage skills are assignable.")
    public String assignSkill(
            @ToolParam(description = "Agent id") UUID agentId,
            @ToolParam(description = "Skill id") String skillId) {
        try {
            agentService.assignSkill(agentId, skillId);
            return ToolResponses.ok(Map.of(
                    "agentId", agentId.toString(), "skillId", skillId, "assigned", true));
        } catch (ResourceNotFoundException e) {
            return ToolResponses.error("NOT_FOUND", e.getMessage(), e, mcpProperties.isDebug());
        } catch (IllegalArgumentException e) {
            return ToolResponses.error("VALIDATION", e.getMessage(), e, mcpProperties.isDebug());
        } catch (InvalidStateTransitionException | IllegalStateException e) {
            // Parity with GlobalExceptionHandler: both map to 409 CONFLICT over REST.
            return ToolResponses.error("CONFLICT", e.getMessage(), e, mcpProperties.isDebug());
        } catch (Exception e) {
            return ToolResponses.error("SKILL_ASSIGN_FAILED", e.getMessage(), e, mcpProperties.isDebug());
        }
    }

    @Tool(name = "unassign_skill",
            description = "Remove a skill from an agent (idempotent: unknown assignments are ignored).")
    public String unassignSkill(
            @ToolParam(description = "Agent id") UUID agentId,
            @ToolParam(description = "Skill id") String skillId) {
        try {
            agentService.unassignSkill(agentId, skillId);
            return ToolResponses.ok(Map.of(
                    "agentId", agentId.toString(), "skillId", skillId, "unassigned", true));
        } catch (ResourceNotFoundException e) {
            return ToolResponses.error("NOT_FOUND", e.getMessage(), e, mcpProperties.isDebug());
        } catch (IllegalArgumentException e) {
            return ToolResponses.error("VALIDATION", e.getMessage(), e, mcpProperties.isDebug());
        } catch (InvalidStateTransitionException | IllegalStateException e) {
            return ToolResponses.error("CONFLICT", e.getMessage(), e, mcpProperties.isDebug());
        } catch (Exception e) {
            return ToolResponses.error("SKILL_UNASSIGN_FAILED", e.getMessage(), e, mcpProperties.isDebug());
        }
    }

    private static String parseStage(String raw) {
        String value = raw.trim().toUpperCase();
        if (!STAGES.contains(value)) {
            throw new IllegalArgumentException("Invalid stage '" + raw
                    + "'. Valid: SKILL, SCRIPT, WORKFLOW");
        }
        return value;
    }

    private static SkillResponse toResponse(SkillDefinition skill) {
        return SkillResponse.builder()
                .id(skill.getId())
                .name(skill.getName())
                .description(skill.getDescription())
                .template(skill.getTemplate())
                .stage(skill.getStage())
                .tier(skill.getTier())
                .enabled(skill.isEnabled())
                .usageCount(skill.getUsageCount())
                .knowledgeItemId(skill.getKnowledgeItemId())
                .createdAt(skill.getCreatedAt())
                .updatedAt(skill.getUpdatedAt())
                .build();
    }
}

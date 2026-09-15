package io.aria.conductor.mcp.tools;

import io.aria.conductor.agent.service.AgentService;
import io.aria.conductor.common.exception.ResourceNotFoundException;
import io.aria.conductor.common.model.SkillContext;
import io.aria.conductor.execution.mcp.McpProperties;
import io.aria.conductor.knowledge.selfimprove.SkillDefinition;
import io.aria.conductor.knowledge.selfimprove.SkillDefinitionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SkillToolsTest {

    @Mock SkillDefinitionRepository skillRepo;
    @Mock AgentService agentService;
    McpProperties mcpProperties;
    SkillTools tools;

    @BeforeEach
    void setUp() {
        mcpProperties = new McpProperties();
        tools = new SkillTools(skillRepo, agentService, mcpProperties);
    }

    private static SkillDefinition skill(String id, String name, boolean enabled) {
        return SkillDefinition.builder()
                .id(id)
                .name(name)
                .description(name + " skill")
                .template("run " + name)
                .triggerConditions("{\"when\":\"always\"}")
                .examples("[]")
                .sourcePromptIds("p1,p2")
                .knowledgeItemId("ki-1")
                .usageCount(3)
                .stage("SKILL")
                .enabled(enabled)
                .tier("T1")
                .createdAt(Instant.parse("2026-01-01T00:00:00Z"))
                .updatedAt(Instant.parse("2026-01-02T00:00:00Z"))
                .build();
    }

    @Test
    void listSkills_stageAndEnabled_usesCombinedQuery() {
        when(skillRepo.findByStageAndEnabledTrue("SKILL"))
                .thenReturn(List.of(skill("skill-1", "code-review", true)));

        String json = tools.listSkills("skill", true);

        assertThat(json).contains("\"ok\":true").contains("code-review").contains("\"enabled\":true");
    }

    @Test
    void listSkills_stageOnly_usesStageQuery() {
        when(skillRepo.findByStage("WORKFLOW"))
                .thenReturn(List.of(skill("skill-2", "release-flow", false)));

        String json = tools.listSkills("WORKFLOW", null);

        assertThat(json).contains("\"ok\":true").contains("release-flow");
    }

    @Test
    void listSkills_noFilters_listsAll() {
        when(skillRepo.findAll()).thenReturn(List.of(skill("skill-1", "code-review", true)));

        String json = tools.listSkills(null, null);

        assertThat(json).contains("\"ok\":true").contains("code-review");
    }

    @Test
    void listSkills_invalidStage_mapsValidationWithoutStack() {
        String json = tools.listSkills("bogus", null);

        assertThat(json).contains("\"errorType\":\"VALIDATION\"");
        assertThat(json).contains("SKILL, SCRIPT, WORKFLOW");
        assertThat(json).doesNotContain("stackTrace");
    }

    @Test
    void getSkill_returnsResponseShape() {
        when(skillRepo.findById("skill-1")).thenReturn(Optional.of(skill("skill-1", "code-review", true)));

        String json = tools.getSkill("skill-1");

        assertThat(json).contains("\"ok\":true")
                .contains("code-review")
                .contains("\"stage\":\"SKILL\"")
                .contains("\"enabled\":true");
    }

    @Test
    void getSkill_missing_mapsNotFound() {
        when(skillRepo.findById("missing")).thenReturn(Optional.empty());

        String json = tools.getSkill("missing");

        assertThat(json).contains("\"errorType\":\"NOT_FOUND\"");
        assertThat(json).contains("missing");
        assertThat(json).doesNotContain("stackTrace");
    }

    @Test
    void toggleSkill_reportsNewState() {
        when(skillRepo.toggleEnabled(eq("skill-1"), any(Instant.class))).thenReturn(1);
        when(skillRepo.findById("skill-1")).thenReturn(Optional.of(skill("skill-1", "code-review", true)));

        String json = tools.toggleSkill("skill-1");

        assertThat(json).contains("\"ok\":true").contains("code-review").contains("\"enabled\":true");
    }

    @Test
    void toggleSkill_unknownId_mapsNotFound() {
        when(skillRepo.toggleEnabled(eq("missing"), any(Instant.class))).thenReturn(0);

        String json = tools.toggleSkill("missing");

        assertThat(json).contains("\"errorType\":\"NOT_FOUND\"");
    }

    @Test
    void listAgentSkills_returnsAssignedSkills() {
        UUID agentId = UUID.randomUUID();
        when(agentService.getAgentSkills(agentId)).thenReturn(List.of(
                new SkillContext("skill-1", "code-review", "reviews code", "run code-review", "SKILL")));

        String json = tools.listAgentSkills(agentId);

        assertThat(json).contains("\"ok\":true").contains("code-review").contains("reviews code");
    }

    @Test
    void listAgentSkills_unknownAgent_mapsNotFound() {
        UUID agentId = UUID.randomUUID();
        when(agentService.getAgentSkills(agentId))
                .thenThrow(new ResourceNotFoundException("Agent", agentId));

        String json = tools.listAgentSkills(agentId);

        assertThat(json).contains("\"errorType\":\"NOT_FOUND\"");
    }

    @Test
    void assignSkill_delegatesAndWraps() {
        UUID agentId = UUID.randomUUID();

        String json = tools.assignSkill(agentId, "skill-1");

        verify(agentService).assignSkill(agentId, "skill-1");
        assertThat(json).contains("\"ok\":true").contains("\"assigned\":true");
    }

    @Test
    void assignSkill_governanceRejection_mapsConflictWithoutStack() {
        UUID agentId = UUID.randomUUID();
        doThrow(new IllegalStateException("Skill 'skill-1' is not an approved/enabled SKILL and cannot be assigned"))
                .when(agentService).assignSkill(agentId, "skill-1");

        String json = tools.assignSkill(agentId, "skill-1");

        assertThat(json).contains("\"errorType\":\"CONFLICT\"");
        assertThat(json).contains("not an approved/enabled SKILL");
        assertThat(json).doesNotContain("stackTrace");
    }

    @Test
    void unassignSkill_delegatesAndWraps() {
        UUID agentId = UUID.randomUUID();

        String json = tools.unassignSkill(agentId, "skill-1");

        verify(agentService).unassignSkill(agentId, "skill-1");
        assertThat(json).contains("\"ok\":true").contains("\"unassigned\":true");
    }
}

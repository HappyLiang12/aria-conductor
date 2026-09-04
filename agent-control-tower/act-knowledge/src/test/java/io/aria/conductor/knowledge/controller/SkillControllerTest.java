package io.aria.conductor.knowledge.controller;

import io.aria.conductor.common.exception.GlobalExceptionHandler;
import io.aria.conductor.knowledge.selfimprove.SkillDefinition;
import io.aria.conductor.knowledge.selfimprove.SkillDefinitionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.core.env.Environment;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SkillControllerTest {

    private SkillDefinitionRepository skillRepo;
    private MockMvc mockMvc;
    private final Environment mockEnv = mock(Environment.class);
    {
        when(mockEnv.getActiveProfiles()).thenReturn(new String[0]);
    }

    @BeforeEach
    void setUp() {
        skillRepo = mock(SkillDefinitionRepository.class);
        SkillController controller = new SkillController(skillRepo);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler(mockEnv))
                .build();
    }

    private SkillDefinition sampleSkill(String id, String name, String stage, boolean enabled) {
        SkillDefinition s = new SkillDefinition();
        s.setId(id);
        s.setName(name);
        s.setDescription("Test description for " + name);
        s.setStage(stage);
        s.setEnabled(enabled);
        s.setUsageCount(0);
        s.setKnowledgeItemId("ki-" + id);
        s.setCreatedAt(Instant.now());
        s.setUpdatedAt(Instant.now());
        return s;
    }

    @Test
    void listSkills_returns200_withAllSkills() throws Exception {
        when(skillRepo.findAll()).thenReturn(List.of(
                sampleSkill("s1", "skill-one", "SKILL", true),
                sampleSkill("s2", "skill-two", "SCRIPT", false)
        ));

        mockMvc.perform(get("/api/v1/skills"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].name").value("skill-one"))
                .andExpect(jsonPath("$[0].enabled").value(true))
                .andExpect(jsonPath("$[1].name").value("skill-two"));
    }

    @Test
    void listSkills_returns200_emptyList() throws Exception {
        when(skillRepo.findAll()).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/skills"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void listSkills_withStageFilter_returnsFiltered() throws Exception {
        when(skillRepo.findByStage("SKILL")).thenReturn(List.of(
                sampleSkill("s1", "skill-only", "SKILL", true)
        ));

        mockMvc.perform(get("/api/v1/skills").param("stage", "SKILL"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].stage").value("SKILL"));

        verify(skillRepo).findByStage("SKILL");
    }

    @Test
    void listSkills_withEnabledTrue_filtersEnabled() throws Exception {
        when(skillRepo.findByEnabledTrue()).thenReturn(List.of(
                sampleSkill("s1", "enabled-skill", "SKILL", true)
        ));

        mockMvc.perform(get("/api/v1/skills").param("enabled", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].enabled").value(true));

        verify(skillRepo).findByEnabledTrue();
    }

    @Test
    void listSkills_withStageAndEnabled_filtersBoth() throws Exception {
        when(skillRepo.findByStageAndEnabledTrue("SCRIPT")).thenReturn(List.of(
                sampleSkill("s1", "script-tool", "SCRIPT", true)
        ));

        mockMvc.perform(get("/api/v1/skills")
                        .param("stage", "SCRIPT")
                        .param("enabled", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].stage").value("SCRIPT"))
                .andExpect(jsonPath("$[0].enabled").value(true));

        verify(skillRepo).findByStageAndEnabledTrue("SCRIPT");
    }

    @Test
    void listSkills_withEnabledFalse_returnsDisabled() throws Exception {
        when(skillRepo.findByEnabledFalse()).thenReturn(List.of(
                sampleSkill("s1", "disabled-skill", "SKILL", false)
        ));

        mockMvc.perform(get("/api/v1/skills").param("enabled", "false"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].enabled").value(false));

        verify(skillRepo).findByEnabledFalse();
    }

    @Test
    void listSkills_withStageAndEnabledFalse_filtersBoth() throws Exception {
        when(skillRepo.findByStageAndEnabledFalse("SCRIPT")).thenReturn(List.of(
                sampleSkill("s1", "disabled-script", "SCRIPT", false)
        ));

        mockMvc.perform(get("/api/v1/skills")
                        .param("stage", "SCRIPT")
                        .param("enabled", "false"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].stage").value("SCRIPT"))
                .andExpect(jsonPath("$[0].enabled").value(false));

        verify(skillRepo).findByStageAndEnabledFalse("SCRIPT");
    }

    @Test
    void listSkillsAlias_returns200() throws Exception {
        when(skillRepo.findAll()).thenReturn(List.of(
                sampleSkill("s1", "skill-one", "SKILL", true)
        ));

        mockMvc.perform(get("/api/v1/skills/list"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void getSkill_returns200_whenFound() throws Exception {
        SkillDefinition skill = sampleSkill("s1", "my-skill", "SKILL", true);
        when(skillRepo.findById("s1")).thenReturn(Optional.of(skill));

        mockMvc.perform(get("/api/v1/skills/s1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("s1"))
                .andExpect(jsonPath("$.name").value("my-skill"))
                .andExpect(jsonPath("$.stage").value("SKILL"));
    }

    @Test
    void listSkills_exposesTemplateFieldForFiltering() throws Exception {
        // PR #74 review item 6: the skills list/detail DTO must carry the `template`
        // so the dashboard can filter template-less rows.
        SkillDefinition withTemplate = sampleSkill("s1", "templated-skill", "SKILL", true);
        withTemplate.setTemplate("You are a meticulous code reviewer.");
        SkillDefinition noTemplate = sampleSkill("s2", "template-less-skill", "SKILL", true);
        when(skillRepo.findAll()).thenReturn(List.of(withTemplate, noTemplate));

        mockMvc.perform(get("/api/v1/skills"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].template").value("You are a meticulous code reviewer."))
                .andExpect(jsonPath("$[1].template").doesNotExist());
    }

    @Test
    void getSkill_exposesTemplateField() throws Exception {
        SkillDefinition skill = sampleSkill("s1", "my-skill", "SKILL", true);
        skill.setTemplate("Prompt body for the skill");
        when(skillRepo.findById("s1")).thenReturn(Optional.of(skill));

        mockMvc.perform(get("/api/v1/skills/s1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.template").value("Prompt body for the skill"));
    }

    @Test
    void getSkill_returns404_whenNotFound() throws Exception {
        when(skillRepo.findById("nonexistent")).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/skills/nonexistent"))
                .andExpect(status().isNotFound());
    }

    @Test
    void toggleSkill_returns200_withToggledState() throws Exception {
        SkillDefinition skill = sampleSkill("s1", "toggle-me", "SKILL", false);
        when(skillRepo.findById("s1")).thenReturn(Optional.of(skill));
        when(skillRepo.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        mockMvc.perform(post("/api/v1/skills/s1/toggle"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("s1"))
                .andExpect(jsonPath("$.enabled").value(true));

        verify(skillRepo).save(any());
    }

    @Test
    void toggleSkill_returns404_whenNotFound() throws Exception {
        when(skillRepo.findById("nonexistent")).thenReturn(Optional.empty());

        mockMvc.perform(post("/api/v1/skills/nonexistent/toggle"))
                .andExpect(status().isNotFound());
    }
}

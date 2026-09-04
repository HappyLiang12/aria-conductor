package io.aria.conductor.knowledge.controller;

import io.aria.conductor.common.exception.GlobalExceptionHandler;
import io.aria.conductor.knowledge.dto.SkillCreateRequest;
import io.aria.conductor.knowledge.selfimprove.SkillDefinition;
import io.aria.conductor.knowledge.selfimprove.SkillDefinitionRepository;
import io.aria.conductor.knowledge.service.SkillApprovalService;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.core.env.Environment;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

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
    private SkillApprovalService skillApprovalService;
    private MockMvc mockMvc;
    private final Environment mockEnv = mock(Environment.class);
    {
        when(mockEnv.getActiveProfiles()).thenReturn(new String[0]);
    }

    @BeforeEach
    void setUp() {
        skillRepo = mock(SkillDefinitionRepository.class);
        skillApprovalService = mock(SkillApprovalService.class);
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        SkillController controller = new SkillController(skillRepo, skillApprovalService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setValidator(validator)
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

    @Test
    void createSkill_returns201_withDisabledSkill() throws Exception {
        SkillDefinition authored = sampleSkill("s1", "Summarizer", "SKILL", false);
        authored.setTemplate("Summarize in {{count}} bullets: {{text}}");
        authored.setDescription("3-bullet summarizer");
        authored.setTier("TIER_2");
        when(skillApprovalService.submitSkillForApproval(any(SkillCreateRequest.class)))
                .thenReturn(authored);

        mockMvc.perform(post("/api/v1/skills")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Summarizer","template":"Summarize in {{count}} bullets: {{text}}","description":"3-bullet summarizer"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value("s1"))
                .andExpect(jsonPath("$.name").value("Summarizer"))
                .andExpect(jsonPath("$.stage").value("SKILL"))
                .andExpect(jsonPath("$.enabled").value(false))
                .andExpect(jsonPath("$.template").value("Summarize in {{count}} bullets: {{text}}"))
                .andExpect(jsonPath("$.description").value("3-bullet summarizer"));
    }

    @Test
    void createSkill_blankNameOrTemplate_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/skills")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"  ","template":"   "}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createSkill_missingBody_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/skills")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createSkill_duplicateName_returns409() throws Exception {
        when(skillApprovalService.submitSkillForApproval(any(SkillCreateRequest.class)))
                .thenThrow(new IllegalStateException("A skill named 'Summarizer' already exists"));

        mockMvc.perform(post("/api/v1/skills")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Summarizer","template":"Summarize {{text}}"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409));
    }
}

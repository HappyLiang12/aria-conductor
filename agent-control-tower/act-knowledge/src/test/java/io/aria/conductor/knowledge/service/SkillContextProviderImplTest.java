package io.aria.conductor.knowledge.service;

import io.aria.conductor.common.model.SkillContext;
import io.aria.conductor.common.repository.AgentSkillRepository;
import io.aria.conductor.knowledge.selfimprove.SkillDefinition;
import io.aria.conductor.knowledge.selfimprove.SkillDefinitionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SkillContextProviderImplTest {

    @Mock AgentSkillRepository agentSkillRepository;
    @Mock SkillDefinitionRepository skillDefinitionRepository;
    @InjectMocks SkillContextProviderImpl provider;

    @Test
    void returnsEnabledSkillStageSkillsWithTemplates() {
        SkillDefinition matched = SkillDefinition.builder()
                .id("s1").name("triage").description("d")
                .template("When triaging, check logs first").stage("SKILL").enabled(true).build();
        SkillDefinition script = SkillDefinition.builder()
                .id("s2").name("deploy").description("d")
                .template(null).stage("SCRIPT").enabled(true).build();
        SkillDefinition disabled = SkillDefinition.builder()
                .id("s3").name("old").description("d")
                .template("x").stage("SKILL").enabled(false).build();

        when(agentSkillRepository.findSkillIdsByAgentId("agent-1"))
                .thenReturn(List.of("s1", "s2", "s3"));
        when(skillDefinitionRepository.findAllById(List.of("s1", "s2", "s3")))
                .thenReturn(List.of(matched, script, disabled));

        List<SkillContext> result = provider.getEnabledSkillsForAgent("agent-1");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).name()).isEqualTo("triage");
        assertThat(result.get(0).template()).isEqualTo("When triaging, check logs first");
    }

    @Test
    void returnsEmptyWhenAgentHasNoSkills() {
        when(agentSkillRepository.findSkillIdsByAgentId("agent-2")).thenReturn(List.of());
        assertThat(provider.getEnabledSkillsForAgent("agent-2")).isEmpty();
    }
}
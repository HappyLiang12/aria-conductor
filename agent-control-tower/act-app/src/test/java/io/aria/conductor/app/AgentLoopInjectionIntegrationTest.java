package io.aria.conductor.app;

import io.aria.conductor.common.model.*;
import io.aria.conductor.common.repository.AgentSkillRepository;
import io.aria.conductor.common.repository.AgentToolRepository;
import io.aria.conductor.common.repository.ToolDefinitionRepository;
import io.aria.conductor.execution.adk.AdkProvider;
import io.aria.conductor.execution.adk.AdkProviderRegistry;
import io.aria.conductor.execution.engine.AgentLoopEngine;
import io.aria.conductor.execution.llm.LlmMessage;
import io.aria.conductor.execution.llm.LlmResponse;
import io.aria.conductor.agent.repository.AgentRepository;
import io.aria.conductor.agent.repository.RunRepository;
import io.aria.conductor.knowledge.repository.KnowledgeItemRepository;
import io.aria.conductor.knowledge.selfimprove.SkillDefinition;
import io.aria.conductor.knowledge.selfimprove.SkillDefinitionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AgentLoopInjectionIntegrationTest extends BaseH2IntegrationTest {

    @Autowired AgentLoopEngine agentLoopEngine;
    @Autowired AgentRepository agentRepository;
    @Autowired RunRepository runRepository;
    @Autowired ToolDefinitionRepository toolDefinitionRepository;
    @Autowired AgentToolRepository agentToolRepository;
    @Autowired KnowledgeItemRepository knowledgeItemRepository;
    @Autowired SkillDefinitionRepository skillDefinitionRepository;
    @Autowired AgentSkillRepository agentSkillRepository;

    @MockBean AdkProviderRegistry adkProviderRegistry;
    private AdkProvider adkProvider;

    @BeforeEach
    void setupAdk() {
        adkProvider = Mockito.mock(AdkProvider.class);
        when(adkProviderRegistry.resolve(any())).thenReturn(adkProvider);
        when(adkProvider.isHealthy(any())).thenReturn(true);
        when(adkProvider.call(any(), any(), any(), any()))
                .thenReturn(new LlmResponse("done", 10, 5, "stop", null));
        when(adkProvider.parseActionsFromResponse(any())).thenReturn(List.of());
    }

    @Test
    void regularAgentRun_receivesToolsSkillsAndKnowledge() {
        // --- seed (committed — no @Transactional on the test class) ---
        Agent agent = agentRepository.save(Agent.builder()
                .id(UUID.randomUUID()).name("test-agent").description("Agent for injection test")
                .agentType(AgentType.NATIVE).role("tester").model("gpt-4o-mini")
                .provider("openai").config("{}").healthStatus(HealthStatus.HEALTHY)
                .createdAt(Instant.now()).build());
        Run run = runRepository.save(Run.builder()
                .id(UUID.randomUUID()).agentId(agent.getId()).status(RunStatus.PENDING)
                .promptSeed("do the work").maxIterations(50).totalTokensUsed(0)
                .iterationCount(0).createdAt(Instant.now()).build());

        ToolDefinition tool = toolDefinitionRepository.save(ToolDefinition.builder()
                .id(UUID.randomUUID().toString()).name("search").description("Search the web")
                .tier("TIER_1").category("GENERAL")
                .parameters("{\"type\":\"object\",\"properties\":{}}")
                .sandboxMode("NONE").timeoutMs(30000).enabled(true).version(1)
                .createdAt(Instant.now()).build());
        agentToolRepository.save(AgentTool.builder()
                .id(new AgentToolId(agent.getId().toString(), tool.getId()))
                .assignedBy("USER")
                .assignedAt(Instant.now()).build());

        knowledgeItemRepository.save(KnowledgeItem.builder()
                .id(UUID.randomUUID()).name("deploy-proc").type(KnowledgeType.GUIDELINE)
                .description("Always blue-green deploy").status(KnowledgeStatus.APPROVED)
                .sensitivity(Sensitivity.INTERNAL).currentVersion("1.0.0")
                .createdAt(Instant.now()).updatedAt(Instant.now()).build());

        SkillDefinition skill = skillDefinitionRepository.save(SkillDefinition.builder()
                .id(UUID.randomUUID().toString()).name("triage").description("triage skill")
                .template("When triaging, check logs first").stage("SKILL").enabled(true)
                .createdAt(Instant.now()).updatedAt(Instant.now()).build());
        agentSkillRepository.save(AgentSkill.builder()
                .id(new AgentSkillId(agent.getId().toString(), skill.getId())).build());

        // --- act ---
        agentLoopEngine.startRun(run.getId());

        // --- await the LLM call (loop runs on a virtual thread) ---
        // S12: the engine invokes the 4-arg call(agentId, messages, tools, streamSink).
        await().atMost(java.time.Duration.ofSeconds(20))
                .untilAsserted(() -> verify(adkProvider, atLeast(1))
                        .call(eq(agent.getId()), any(), any(), any()));

        // --- capture + assert ---
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<LlmMessage>> msgCaptor = ArgumentCaptor.forClass(List.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Map<String, Object>>> toolCaptor = ArgumentCaptor.forClass(List.class);
        verify(adkProvider).call(eq(agent.getId()), msgCaptor.capture(), toolCaptor.capture(), any());

        List<LlmMessage> messages = msgCaptor.getValue();
        Optional<LlmMessage> systemMsg = messages.stream()
                .filter(m -> "system".equals(m.role())).findFirst();
        assertThat(systemMsg).as("system message must be present").isPresent();
        String systemPrompt = systemMsg.get().content();
        assertThat(systemPrompt)
                .as("knowledge must be injected for non-Aria agents")
                .contains("## Knowledge Context", "deploy-proc");
        assertThat(systemPrompt)
                .as("enabled skills must be injected (resolves #56 skills orphan)")
                .contains("## Skills", "When triaging, check logs first");

        assertThat(toolCaptor.getValue())
                .as("agent's tools must be passed to the ADK provider")
                .hasSize(1);
    }
}
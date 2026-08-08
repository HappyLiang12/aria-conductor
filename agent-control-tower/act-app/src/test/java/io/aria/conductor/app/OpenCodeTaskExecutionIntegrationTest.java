package io.aria.conductor.app;

import io.aria.conductor.agent.repository.AgentRepository;
import io.aria.conductor.agent.repository.RunRepository;
import io.aria.conductor.common.model.Agent;
import io.aria.conductor.common.model.AgentSkill;
import io.aria.conductor.common.model.AgentSkillId;
import io.aria.conductor.common.model.AgentType;
import io.aria.conductor.common.model.HealthStatus;
import io.aria.conductor.common.model.KnowledgeItem;
import io.aria.conductor.common.model.KnowledgeStatus;
import io.aria.conductor.common.model.KnowledgeType;
import io.aria.conductor.common.model.Run;
import io.aria.conductor.common.model.RunStatus;
import io.aria.conductor.common.model.Sensitivity;
import io.aria.conductor.common.repository.AgentSkillRepository;
import io.aria.conductor.execution.adk.AdkProvider;
import io.aria.conductor.execution.adk.AdkProviderRegistry;
import io.aria.conductor.execution.adk.TaskExecutionException;
import io.aria.conductor.execution.adk.TaskResult;
import io.aria.conductor.execution.engine.AgentLoopEngine;
import io.aria.conductor.execution.repository.PromptCallRepository;
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

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Integration coverage for the engine delegation branch with a task-level
 * (OpenCode-style) provider. Mirrors {@link AgentLoopInjectionIntegrationTest}:
 * real Spring context + H2, only {@link AdkProviderRegistry} is mocked so the
 * provider under test is fully controlled.
 *
 * <p>REST coverage for {@code GET /api/v1/adk/providers} lives in the standalone
 * {@code AdkProviderControllerTest} (act-execution) — a {@code @MockBean} registry
 * here would shadow the real provider list needed for that assertion.
 */
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class OpenCodeTaskExecutionIntegrationTest extends BaseH2IntegrationTest {

    @Autowired AgentLoopEngine agentLoopEngine;
    @Autowired AgentRepository agentRepository;
    @Autowired RunRepository runRepository;
    @Autowired PromptCallRepository promptCallRepository;
    @Autowired KnowledgeItemRepository knowledgeItemRepository;
    @Autowired SkillDefinitionRepository skillDefinitionRepository;
    @Autowired AgentSkillRepository agentSkillRepository;

    @MockBean AdkProviderRegistry adkProviderRegistry;
    private AdkProvider taskProvider;

    @BeforeEach
    void setupAdk() {
        taskProvider = Mockito.mock(AdkProvider.class);
        when(adkProviderRegistry.resolve(any())).thenReturn(taskProvider);
        when(taskProvider.supportsTaskExecution()).thenReturn(true);
        when(taskProvider.providerId()).thenReturn("opencode");
    }

    @Test
    void taskProviderRun_completesWithSystemRulePromptAndAudit() {
        // --- seed: task-level (opencode) agent + knowledge + skill ---
        Agent agent = agentRepository.save(Agent.builder()
                .id(UUID.randomUUID()).name("opencode-agent").description("task agent")
                .agentType(AgentType.NATIVE).role("tester").model("gpt-4o")
                .provider("openai").config("{\"maxToolCallRounds\":9,\"taskApprovalRequired\":false}")
                .adkProvider("opencode").healthStatus(HealthStatus.HEALTHY)
                .createdAt(Instant.now()).build());
        Run run = runRepository.save(Run.builder()
                .id(UUID.randomUUID()).agentId(agent.getId()).status(RunStatus.PENDING)
                .promptSeed("refactor the module").maxIterations(0).totalTokensUsed(0)
                .iterationCount(0).createdAt(Instant.now()).build());

        seedKnowledgeAndSkill(agent);

        when(taskProvider.executeTask(any(), any(), anyString(), any())).thenAnswer(inv -> {
            UUID runId = inv.getArgument(1);
            return new TaskResult(runId, "sess-oc-1", "OpenCode finished the job", 100, 40, false);
        });

        // --- act ---
        agentLoopEngine.startRun(run.getId());

        // --- the delegation branch must call executeTask (never turn-level call) ---
        await().atMost(Duration.ofSeconds(20))
                .untilAsserted(() -> verify(taskProvider, atLeast(1))
                        .executeTask(any(), eq(run.getId()), anyString(), any()));

        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(taskProvider).executeTask(any(), eq(run.getId()), promptCaptor.capture(), any());
        String taskPrompt = promptCaptor.getValue();
        assertThat(taskPrompt).as("skills must be injected via buildMessages reuse")
                .contains("## Skills", "When triaging, check logs first");
        assertThat(taskPrompt).as("knowledge must be injected via buildMessages reuse")
                .contains("## Knowledge Context", "deploy-proc");
        assertThat(taskPrompt).as("user request must be merged into the task prompt")
                .contains("refactor the module");

        verify(taskProvider, never()).call(any(), any(), any());

        // --- run COMPLETED + finalOutput + token audit ---
        await().atMost(Duration.ofSeconds(20))
                .until(() -> runRepository.findById(run.getId())
                        .map(r -> r.getStatus() == RunStatus.COMPLETED).orElse(false));
        Run completed = runRepository.findById(run.getId()).orElseThrow();
        assertThat(completed.getFinalOutput()).isEqualTo("OpenCode finished the job");
        assertThat(completed.getTotalTokensUsed()).isEqualTo(140);
        assertThat(completed.getIterationCount()).isEqualTo(1);

        assertThat(promptCallRepository.findByRunId(run.getId())).hasSize(1);
        assertThat(promptCallRepository.findByRunId(run.getId()).get(0).getInputTokens()).isEqualTo(100);
        assertThat(promptCallRepository.findByRunId(run.getId()).get(0).getOutputTokens()).isEqualTo(40);
    }

    @Test
    void timeoutException_abortsRunAndCallsProviderAbort() {
        Agent agent = agentRepository.save(Agent.builder()
                .id(UUID.randomUUID()).name("opencode-timeout").description("task agent")
                .agentType(AgentType.NATIVE).role("tester").model("gpt-4o")
                .provider("openai").config("{\"taskApprovalRequired\":false}")
                .adkProvider("opencode").healthStatus(HealthStatus.HEALTHY)
                .createdAt(Instant.now()).build());
        Run run = runRepository.save(Run.builder()
                .id(UUID.randomUUID()).agentId(agent.getId()).status(RunStatus.PENDING)
                .promptSeed("do it now").maxIterations(0).totalTokensUsed(0)
                .iterationCount(0).createdAt(Instant.now()).build());

        when(taskProvider.executeTask(any(), any(), anyString(), any())).thenThrow(
                new TaskExecutionException(TaskExecutionException.Cause.TIMEOUT,
                        "task exceeded 30m budget"));

        agentLoopEngine.startRun(run.getId());

        await().atMost(Duration.ofSeconds(20))
                .until(() -> runRepository.findById(run.getId())
                        .map(r -> r.getStatus() == RunStatus.ABORTED).orElse(false));

        Run aborted = runRepository.findById(run.getId()).orElseThrow();
        assertThat(aborted.getStatus()).isEqualTo(RunStatus.ABORTED);
        assertThat(aborted.getErrorMessage()).contains("budget");

        verify(taskProvider).abortTask(run.getId());
        verify(taskProvider, never()).call(any(), any(), any());
        // Failure path does not write a PromptCall audit entry (only successful tasks are audited)
        assertThat(promptCallRepository.findByRunId(run.getId())).isEmpty();
    }

    private void seedKnowledgeAndSkill(Agent agent) {
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
    }
}

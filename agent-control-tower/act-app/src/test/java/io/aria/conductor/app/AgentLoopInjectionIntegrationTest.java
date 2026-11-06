package io.aria.conductor.app;

import io.aria.conductor.common.model.*;
import io.aria.conductor.common.repository.AgentSkillRepository;
import io.aria.conductor.common.runtime.ExecutionMode;
import io.aria.conductor.execution.adk.opencode.OpenCodeProperties;
import io.aria.conductor.execution.engine.AgentLoopEngine;
import io.aria.conductor.execution.repository.PromptCallRepository;
import io.aria.conductor.execution.repository.RunExecutionBindingRepository;
import io.aria.conductor.execution.runtime.CoreRunLauncher;
import io.aria.conductor.execution.runtime.CoreTask;
import io.aria.conductor.agent.repository.AgentRepository;
import io.aria.conductor.agent.repository.RunRepository;
import io.aria.conductor.knowledge.repository.KnowledgeItemRepository;
import io.aria.conductor.knowledge.selfimprove.SkillDefinition;
import io.aria.conductor.knowledge.selfimprove.SkillDefinitionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.test.annotation.DirtiesContext;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.verify;

/**
 * Integration coverage of the Task 18 cutover dispatch for a default-core agent
 * (no explicit core resolves to {@code opencode}, no explicit mode to SANDBOX):
 * the run is owned by the run coordinator through {@link CoreRunLauncher}. The
 * {@link CoreTask} the engine assembles for the core carries the same injection
 * material the turn loop built (knowledge + skills via {@code buildMessages}
 * reuse), and with no sandbox endpoint in the test environment the run
 * terminates {@code FAILED} with the coordinator's exact sandbox-launch refusal.
 */
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AgentLoopInjectionIntegrationTest extends BaseH2IntegrationTest {

    @Autowired AgentLoopEngine agentLoopEngine;
    @Autowired AgentRepository agentRepository;
    @Autowired RunRepository runRepository;
    @Autowired KnowledgeItemRepository knowledgeItemRepository;
    @Autowired SkillDefinitionRepository skillDefinitionRepository;
    @Autowired AgentSkillRepository agentSkillRepository;
    @Autowired RunExecutionBindingRepository runExecutionBindingRepository;
    @Autowired PromptCallRepository promptCallRepository;
    @Autowired OpenCodeProperties openCodeProperties;

    @SpyBean CoreRunLauncher coreRunLauncher;

    @Test
    void regularAgentRun_isCoordinatorOwned_carriesSystemRuleTask() {
        // --- seed (committed — no @Transactional on the test class) ---
        Agent agent = agentRepository.save(Agent.builder()
                .id(UUID.randomUUID()).name("test-agent").description("Agent for injection test")
                .agentType(AgentType.ADK).role("tester").model("gpt-4o-mini")
                .provider("openai").config("{}").healthStatus(HealthStatus.HEALTHY)
                .createdAt(Instant.now()).build());
        Run run = runRepository.save(Run.builder()
                .id(UUID.randomUUID()).agentId(agent.getId()).status(RunStatus.PENDING)
                .promptSeed("do the work").maxIterations(50).totalTokensUsed(0)
                .iterationCount(0).createdAt(Instant.now()).build());

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

        // --- the coordinated attempt reaches its terminal refusal deterministically ---
        await().atMost(Duration.ofSeconds(20))
                .until(() -> runRepository.findById(run.getId())
                        .map(r -> r.getStatus() == RunStatus.FAILED).orElse(false));

        // --- the CoreTask handed to the run-owned core carries the injection material ---
        ArgumentCaptor<CoreTask> taskCaptor = ArgumentCaptor.forClass(CoreTask.class);
        verify(coreRunLauncher).execute(
                argThat(r -> run.getId().equals(r.getId())),
                argThat(a -> agent.getId().equals(a.getId())),
                taskCaptor.capture());
        CoreTask task = taskCaptor.getValue();
        assertThat(task.systemPrompt())
                .as("knowledge must be injected for non-Aria agents")
                .contains("## Knowledge Context", "deploy-proc");
        assertThat(task.systemPrompt())
                .as("enabled skills must be injected (resolves #56 skills orphan)")
                .contains("## Skills", "When triaging, check logs first");
        assertThat(task.userPrompt()).isEqualTo("do the work");

        // --- the frozen binding captures exactly this run on the production core ---
        RunExecutionBinding binding = runExecutionBindingRepository.findById(run.getId()).orElseThrow();
        assertThat(binding.getRunId()).isEqualTo(run.getId());
        assertThat(binding.getAgentId()).isEqualTo(agent.getId());
        assertThat(binding.getCoreId()).isEqualTo("opencode");
        assertThat(binding.getExecutionMode()).isEqualTo(ExecutionMode.SANDBOX);
        assertThat(binding.getDeadline()).as("every attempt freezes a deadline").isNotNull();
        assertThat(binding.getDeadline().getNano())
                .as("the frozen deadline is persisted at TIMESTAMP granularity")
                .isZero();

        // --- terminal state: the coordinator's exact sandbox-launch refusal ---
        Run failed = runRepository.findById(run.getId()).orElseThrow();
        assertThat(failed.getStatus()).isEqualTo(RunStatus.FAILED);
        assertThat(failed.getErrorMessage())
                .startsWith("OpenSandbox sandbox creation failed for image "
                        + openCodeProperties.getImage() + ": Network connectivity error: Failed to connect to")
                .endsWith(":18080");

        assertThat(promptCallRepository.findByRunId(run.getId())).isEmpty();
    }
}

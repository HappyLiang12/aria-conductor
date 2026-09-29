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
import io.aria.conductor.common.model.RunExecutionBinding;
import io.aria.conductor.common.model.RunStatus;
import io.aria.conductor.common.model.Sensitivity;
import io.aria.conductor.common.repository.AgentSkillRepository;
import io.aria.conductor.common.runtime.ExecutionMode;
import io.aria.conductor.execution.adk.AdkProvider;
import io.aria.conductor.execution.adk.AdkProviderRegistry;
import io.aria.conductor.execution.adk.TaskExecutionException;
import io.aria.conductor.execution.adk.TaskResult;
import io.aria.conductor.execution.adk.opencode.OpenCodeProperties;
import io.aria.conductor.execution.engine.AgentLoopEngine;
import io.aria.conductor.execution.repository.PromptCallRepository;
import io.aria.conductor.execution.repository.RunExecutionBindingRepository;
import io.aria.conductor.execution.runtime.CoreRunLauncher;
import io.aria.conductor.execution.runtime.CoreTask;
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
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.test.annotation.DirtiesContext;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Integration coverage of the Task 18 cutover dispatch for a core that is also a
 * registered production core ({@code opencode}): {@link AgentLoopEngine} hands the
 * run to the run coordinator through {@link CoreRunLauncher} (immutable binding,
 * then the run-owned attempt) and never to the {@link AdkProvider} double. The
 * {@link CoreTask} the engine assembles for the core carries the same system
 * material the legacy task path built (skills, knowledge, the user request via
 * {@code buildMessages} reuse).
 *
 * <p>The legacy expectations this class used to pin -- a provider-driven
 * {@code executeTask} run completing COMPLETED with the provider's final output and
 * token audit, or a provider time-out turning the run ABORTED through
 * {@code abortTask} -- no longer exist in production: a registered production core
 * is run-owned (AgentLoopEngine.java:775-779), and the provider-double task path
 * stays below for doubles only. That path keeps its own unit pin
 * ({@code AgentLoopEngineTaskPathTest}, act-execution, without the cutover wiring).
 *
 * <p>The test environment has no sandbox control endpoint
 * ({@code opencode.sandbox-server-url} points at a closed port), so the coordinated
 * attempt is refused at sandbox creation and the run terminates {@code FAILED} with
 * that exact refusal -- the observable cutover contract asserted here, together
 * with zero provider interactions and zero PromptCall audit rows.
 *
 * <p>{@link CoreRunLauncher} is spied (not mocked) so the real freeze+dispatch runs:
 * the frozen binding row is read back and the {@link CoreTask} actually handed to
 * the coordinator is captured.
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
    @Autowired RunExecutionBindingRepository runExecutionBindingRepository;
    @Autowired OpenCodeProperties openCodeProperties;

    @MockBean AdkProviderRegistry adkProviderRegistry;
    @SpyBean CoreRunLauncher coreRunLauncher;
    private AdkProvider taskProvider;

    @BeforeEach
    void setupAdk() {
        taskProvider = Mockito.mock(AdkProvider.class);
        when(adkProviderRegistry.resolve(any())).thenReturn(taskProvider);
        when(taskProvider.supportsTaskExecution()).thenReturn(true);
        when(taskProvider.providerId()).thenReturn("opencode");
    }

    @Test
    void coordinatorOwnedOpencodeRun_carriesSystemRuleTaskToTheCore_andNeverConsultsTheProvider() {
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

        // The double is armed with a ready success answer; the cutover must never ask it.
        when(taskProvider.executeTask(any(), any(), anyString(), any())).thenAnswer(inv -> {
            UUID runId = inv.getArgument(1);
            return new TaskResult(runId, "sess-oc-1", "OpenCode finished the job", 100, 40, false);
        });

        // --- act ---
        agentLoopEngine.startRun(run.getId());

        // --- the coordinated attempt reaches its terminal refusal deterministically ---
        await().atMost(Duration.ofSeconds(20))
                .until(() -> runRepository.findById(run.getId())
                        .map(r -> r.getStatus() == RunStatus.FAILED).orElse(false));

        // --- the CoreTask handed to the run-owned core is the legacy system material ---
        ArgumentCaptor<CoreTask> taskCaptor = ArgumentCaptor.forClass(CoreTask.class);
        verify(coreRunLauncher).execute(
                argThat(r -> run.getId().equals(r.getId())),
                argThat(a -> agent.getId().equals(a.getId())),
                taskCaptor.capture());
        CoreTask task = taskCaptor.getValue();
        assertThat(task.systemPrompt()).as("skills must be injected via buildMessages reuse")
                .contains("## Skills", "When triaging, check logs first");
        assertThat(task.systemPrompt()).as("knowledge must be injected via buildMessages reuse")
                .contains("## Knowledge Context", "deploy-proc");
        assertThat(task.userPrompt()).as("user request must be merged into the task prompt")
                .isEqualTo("refactor the module");

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

        // --- the provider double is never consulted: no task execution, no turn call ---
        verify(taskProvider, never()).executeTask(any(), any(), anyString(), any());
        verify(taskProvider, never()).call(any(), any(), any());
        verify(taskProvider, never()).call(any(), any(), any(), any());

        // --- terminal state: the coordinator's exact sandbox-launch refusal, no audit row, no usage ---
        Run failed = runRepository.findById(run.getId()).orElseThrow();
        assertThat(failed.getStatus()).isEqualTo(RunStatus.FAILED);
        assertThat(failed.getErrorMessage())
                .startsWith("OpenSandbox sandbox creation failed for image "
                        + openCodeProperties.getImage() + ": Network connectivity error: Failed to connect to")
                .endsWith(":18080");
        assertThat(failed.getFinalOutput()).isNull();
        assertThat(failed.getTotalTokensUsed()).isZero();
        assertThat(failed.getIterationCount()).isZero();
        assertThat(promptCallRepository.findByRunId(run.getId())).isEmpty();
    }

    @Test
    void providerDoubleTimeoutIsNeverConsulted_andTheCoordinatorRefusalIsTheTerminalError() {
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

        // The double would time out if consulted; the run-owned core means it never is, so
        // neither the double's time-out nor abortTask may shape the terminal state.
        when(taskProvider.executeTask(any(), any(), anyString(), any())).thenThrow(
                new TaskExecutionException(TaskExecutionException.Cause.TIMEOUT,
                        "task exceeded 30m budget"));

        agentLoopEngine.startRun(run.getId());

        await().atMost(Duration.ofSeconds(20))
                .until(() -> runRepository.findById(run.getId())
                        .map(r -> r.getStatus() == RunStatus.FAILED).orElse(false));

        Run failed = runRepository.findById(run.getId()).orElseThrow();
        assertThat(failed.getStatus()).isEqualTo(RunStatus.FAILED);
        assertThat(failed.getErrorMessage())
                .startsWith("OpenSandbox sandbox creation failed for image "
                        + openCodeProperties.getImage() + ": Network connectivity error: Failed to connect to")
                .endsWith(":18080")
                .doesNotContain("task exceeded 30m budget");

        // The frozen binding captures exactly this run; the provider double is never asked to
        // execute, to run the turn-level call, or to abort (the coordinator owns termination).
        RunExecutionBinding binding = runExecutionBindingRepository.findById(run.getId()).orElseThrow();
        assertThat(binding.getRunId()).isEqualTo(run.getId());
        assertThat(binding.getAgentId()).isEqualTo(agent.getId());
        assertThat(binding.getCoreId()).isEqualTo("opencode");
        assertThat(binding.getExecutionMode()).isEqualTo(ExecutionMode.SANDBOX);
        verify(taskProvider, never()).executeTask(any(), any(), anyString(), any());
        verify(taskProvider, never()).call(any(), any(), any());
        verify(taskProvider, never()).call(any(), any(), any(), any());
        verify(taskProvider, never()).abortTask(run.getId());

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

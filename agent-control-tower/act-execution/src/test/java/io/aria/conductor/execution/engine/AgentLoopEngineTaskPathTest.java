package io.aria.conductor.execution.engine;

import io.aria.conductor.agent.repository.AgentRepository;
import io.aria.conductor.agent.repository.RunRepository;
import io.aria.conductor.agent.repository.WorkflowChainRepository;
import io.aria.conductor.agent.service.HarnessProfileService;
import io.aria.conductor.agent.service.WorkflowService;
import io.aria.conductor.common.model.Agent;
import io.aria.conductor.common.model.AgentSession;
import io.aria.conductor.common.model.AgentType;
import io.aria.conductor.common.model.HarnessProfile;
import io.aria.conductor.common.model.HealthStatus;
import io.aria.conductor.common.model.Run;
import io.aria.conductor.common.model.RunStatus;
import io.aria.conductor.common.service.KnowledgeContextProvider;
import io.aria.conductor.common.service.ToolRegistry;
import io.aria.conductor.execution.adk.AdkProvider;
import io.aria.conductor.execution.adk.AdkProviderRegistry;
import io.aria.conductor.execution.adk.TaskContext;
import io.aria.conductor.execution.adk.TaskResult;
import io.aria.conductor.execution.adk.opencode.OpenCodeProperties;
import io.aria.conductor.execution.approval.ApprovalGate;
import io.aria.conductor.execution.circuit.CircuitBreaker;
import io.aria.conductor.execution.harness.ToolSteeringGuard;
import io.aria.conductor.execution.pipeline.ActionExecutionPipeline;
import io.aria.conductor.execution.repository.ApprovalRepository;
import io.aria.conductor.execution.repository.PromptCallRepository;
import io.aria.conductor.execution.repository.SessionTrajectoryRepository;
import io.aria.conductor.execution.repository.ToolCallRepository;
import io.aria.conductor.execution.tool.AgentSkillResolver;
import io.aria.conductor.execution.tool.AgentToolResolver;
import io.aria.conductor.execution.tool.WorkspaceManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit coverage for the task-execution delegation branch of {@link AgentLoopEngine}:
 * a provider with {@code supportsTaskExecution() == true} must take over the whole
 * run via {@code executeTask} (never the turn-level {@code call}), and the
 * {@link TaskContext} must carry the agent-config round cap + OpenCode timeout.
 */
@ExtendWith(MockitoExtension.class)
class AgentLoopEngineTaskPathTest {

    @Mock RunRepository runRepository;
    @Mock AgentRepository agentRepository;
    @Mock AdkProviderRegistry adkProviderRegistry;
    @Mock SessionStateManager sessionStateManager;
    @Mock ActionExecutionPipeline actionPipeline;
    @Mock CircuitBreaker circuitBreaker;
    @Mock ApprovalGate approvalGate;
    @Mock PromptCallRepository promptCallRepository;
    @Mock SessionTrajectoryRepository trajectoryRepository;
    @Mock ToolCallRepository toolCallRepository;
    @Mock ApplicationEventPublisher eventPublisher;
    @Mock WorkflowService workflowService;
    @Mock WorkflowChainRepository workflowChainRepository;
    @Mock AgentToolResolver agentToolResolver;
    @Mock AgentSkillResolver agentSkillResolver;
    @Mock ToolRegistry toolRegistry;
    @Mock KnowledgeContextProvider knowledgeProvider;
    @Mock WorkspaceManager workspaceManager;
    @Mock HarnessProfileService harnessProfileService;
    @Mock ToolSteeringGuard toolSteeringGuard;
    @Mock ApprovalRepository approvalRepository;
    @Mock OpenCodeProperties openCodeProperties;

    @InjectMocks
    AgentLoopEngine engine;

    @Mock
    AdkProvider taskProvider;

    private UUID runId;
    private UUID agentId;
    private Agent agent;
    private Run run;

    @BeforeEach
    void setUp() {
        runId = UUID.randomUUID();
        agentId = UUID.randomUUID();

        agent = org.mockito.Mockito.mock(Agent.class);
        when(agent.getId()).thenReturn(agentId);
        when(agent.getConfig()).thenReturn(
                "{\"maxToolCallRounds\":7,\"systemPrompt\":\"You are a tester agent.\"}");
        // Conditional stubs (provider/model only used on the success path; providerId/maxTaskMinutes
        // only on the task path) — lenient so turn-loop-only and failure tests stay strict-clean.
        lenient().when(agent.getProvider()).thenReturn("openai");
        lenient().when(agent.getModel()).thenReturn("gpt-4o");

        run = Run.builder()
                .id(runId).agentId(agentId).status(RunStatus.PENDING)
                .promptSeed("do the work").maxIterations(0)
                .totalTokensUsed(0).iterationCount(0).createdAt(Instant.now())
                .build();

        when(runRepository.findById(runId)).thenReturn(Optional.of(run));
        when(agentRepository.findById(agentId)).thenReturn(Optional.of(agent));
        when(adkProviderRegistry.resolve(agent)).thenReturn(taskProvider);
        when(taskProvider.supportsTaskExecution()).thenReturn(true);
        lenient().when(taskProvider.providerId()).thenReturn("opencode");
        when(harnessProfileService.resolve(agent)).thenReturn(HarnessProfile.defaults());
        when(sessionStateManager.loadOrCreateSession(runId, agentId))
                .thenReturn(org.mockito.Mockito.mock(AgentSession.class));
        when(trajectoryRepository.findByRunIdOrderByTurnNumberAsc(runId)).thenReturn(List.of());
        when(workspaceManager.getOrProvision(runId)).thenReturn("/tmp/ws");
        lenient().when(openCodeProperties.getMaxTaskMinutes()).thenReturn(30);
        when(knowledgeProvider.buildKnowledgeContextPrompt(5)).thenReturn("");
    }

    @Test
    void taskProvider_delegatesWholeRun_andCompletesWithFinalOutput() {
        when(taskProvider.executeTask(any(), any(), anyString(), any())).thenReturn(
                new TaskResult(runId, "sess-1", "Task done output", 120, 30, false));

        engine.startRun(runId);

        await().atMost(Duration.ofSeconds(15))
                .until(() -> run.getStatus() == RunStatus.COMPLETED);

        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(taskProvider).executeTask(eq(agent), eq(runId), promptCaptor.capture(), any());

        // Delegation taken: task-level executeTask called, turn-level call never invoked
        verify(taskProvider, never()).call(any(), any(), any());

        // taskPrompt carries the same system-rule injection as the turn loop
        String taskPrompt = promptCaptor.getValue();
        assertThat(taskPrompt).as("system prompt from agent config must be injected")
                .contains("You are a tester agent.");
        assertThat(taskPrompt).as("user request must be merged into the task prompt")
                .contains("do the work");

        // run state + finalOutput persisted via the existing completion path
        assertThat(run.getStatus()).isEqualTo(RunStatus.COMPLETED);
        assertThat(run.getFinalOutput()).isEqualTo("Task done output");
        assertThat(run.getTotalTokensUsed()).isEqualTo(150);
        assertThat(run.getIterationCount()).isEqualTo(1);
    }

    @Test
    void taskContext_carriesConfigMaxRounds_andOpenCodeMaxDuration() {
        when(taskProvider.executeTask(any(), any(), anyString(), any())).thenReturn(
                new TaskResult(runId, "sess-1", "done", 10, 5, false));

        engine.startRun(runId);

        await().atMost(Duration.ofSeconds(15))
                .until(() -> run.getStatus() == RunStatus.COMPLETED);

        ArgumentCaptor<TaskContext> contextCaptor = ArgumentCaptor.forClass(TaskContext.class);
        verify(taskProvider).executeTask(eq(agent), eq(runId), anyString(), contextCaptor.capture());

        TaskContext ctx = contextCaptor.getValue();
        // maxRounds comes from agent.config.maxToolCallRounds (7), not the 50 default
        assertThat(ctx.maxRounds()).isEqualTo(7);
        // maxDuration comes from OpenCodeProperties.maxTaskMinutes (30)
        assertThat(ctx.maxDuration()).isEqualTo(Duration.ofMinutes(30));
    }

    @Test
    void timeoutException_marksRunAborted() {
        when(taskProvider.executeTask(any(), any(), anyString(), any())).thenThrow(
                new io.aria.conductor.execution.adk.TaskExecutionException(
                        io.aria.conductor.execution.adk.TaskExecutionException.Cause.TIMEOUT,
                        "task exceeded budget"));

        engine.startRun(runId);

        await().atMost(Duration.ofSeconds(15))
                .until(() -> run.getStatus() == RunStatus.ABORTED);

        verify(taskProvider).abortTask(runId);
        verify(taskProvider, never()).call(any(), any(), any());
        assertThat(run.getErrorMessage()).contains("budget");
    }

    @Test
    void cancelRun_abortsInFlightTask_andMarksRunTerminal() throws Exception {
        CountDownLatch taskStarted = new CountDownLatch(1);
        CountDownLatch releaseTask = new CountDownLatch(1);
        when(taskProvider.executeTask(any(), any(), anyString(), any())).thenAnswer(inv -> {
            taskStarted.countDown();
            try {
                releaseTask.await(60, TimeUnit.SECONDS); // long-running task
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw e;
            }
            return new TaskResult(runId, "sess-1", "done", 10, 5, false);
        });

        engine.startRun(runId);

        assertThat(taskStarted.await(10, TimeUnit.SECONDS)).as("executeTask must start").isTrue();
        engine.cancelRun(runId);

        try {
            // Cancel path: abortTask is invoked and the run reaches a terminal state.
            // cancelRun() persists CANCELLED first, and completeRun() refuses to
            // overwrite that externally-set terminal state, so the run ends
            // CANCELLED/ABORTED depending on the interleaving.
            // (CompletableFuture.cancel(true) does not interrupt the executing thread,
            // so we assert the provider-side abort signal instead of an interrupt.)
            await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
                verify(taskProvider).abortTask(runId);
                assertThat(run.getStatus()).isIn(RunStatus.CANCELLED, RunStatus.ABORTED);
            });
        } finally {
            // Unblock the still-executing executeTask so the virtual thread unwinds.
            releaseTask.countDown();
        }
    }

    @Test
    void nonTaskProvider_keepsTurnLoopUntouched() {
        // A turn-level provider must never enter the task path
        when(taskProvider.supportsTaskExecution()).thenReturn(false);
        when(taskProvider.call(any(), any(), any()))
                .thenReturn(new io.aria.conductor.execution.llm.LlmResponse("final answer", 10, 5, "stop", null));
        when(taskProvider.parseActionsFromResponse(any())).thenReturn(List.of());

        engine.startRun(runId);

        await().atMost(Duration.ofSeconds(15))
                .until(() -> run.getStatus() == RunStatus.COMPLETED);

        verify(taskProvider, never()).executeTask(any(), any(), anyString(), any());
        // turn loop calls the LLM provider
        verify(taskProvider).call(any(), any(), any());
    }

    // ---- helper to build a plain agent (avoid over-mocking in edge tests) ----

    @SuppressWarnings("unused")
    private static Agent plainAgent(UUID id) {
        return Agent.builder()
                .id(id).name("plain").role("plain role").agentType(AgentType.NATIVE)
                .provider("openai").model("gpt-4o").config("{}")
                .healthStatus(HealthStatus.HEALTHY).createdAt(Instant.now())
                .build();
    }
}

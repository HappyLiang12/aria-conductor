package io.aria.conductor.execution.engine;

import io.aria.conductor.agent.repository.AgentRepository;
import io.aria.conductor.agent.repository.LlmProviderRepository;
import io.aria.conductor.agent.repository.RunRepository;
import io.aria.conductor.agent.repository.WorkflowChainRepository;
import io.aria.conductor.agent.service.HarnessProfileService;
import io.aria.conductor.agent.service.WorkflowService;
import io.aria.conductor.common.model.Agent;
import io.aria.conductor.common.model.AgentSession;
import io.aria.conductor.common.model.HarnessProfile;
import io.aria.conductor.common.model.Run;
import io.aria.conductor.common.model.RunStatus;
import io.aria.conductor.common.service.KnowledgeContextProvider;
import io.aria.conductor.common.service.ToolRegistry;
import io.aria.conductor.execution.adk.AdkProvider;
import io.aria.conductor.execution.adk.AdkProviderRegistry;
import io.aria.conductor.execution.adk.TaskContext;
import io.aria.conductor.execution.adk.TaskExecutionConstraints;
import io.aria.conductor.execution.adk.TaskResult;
import io.aria.conductor.execution.adk.opencode.OpenCodeAdkProvider;
import io.aria.conductor.execution.adk.opencode.OpenCodeProperties;
import io.aria.conductor.execution.approval.ApprovalDecision;
import io.aria.conductor.execution.approval.ApprovalGate;
import io.aria.conductor.execution.circuit.CircuitBreaker;
import io.aria.conductor.execution.harness.ToolSteeringGuard;
import io.aria.conductor.execution.mcp.McpProperties;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * C0.6 (task B2) — provider-resolved task constraints.
 *
 * <p>The engine must take the task deadline from
 * {@link AdkProvider#taskConstraints()} instead of reading the opencode
 * properties directly; a provider that states no constraint keeps the
 * opencode {@code max-task-minutes} fallback. {@link OpenCodeAdkProvider}
 * preserves its previous behavior by returning that same property value.
 */
@ExtendWith(MockitoExtension.class)
class AgentLoopEngineConstraintsTest {

    /** Fallback used by the tests that exercise "provider states no deadline". */
    private static final int OPENCODE_FALLBACK_MINUTES = 90;

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

        run = Run.builder()
                .id(runId).agentId(agentId).status(RunStatus.PENDING)
                .promptSeed("do the work").maxIterations(0)
                .totalTokensUsed(0).iterationCount(0).createdAt(Instant.now())
                .build();
    }

    /**
     * Wires the task-execution path up to (not including) the provider call, so
     * {@code executeTask} receives the engine-built {@link TaskContext}. Only the
     * tests that actually run the engine need these stubs.
     */
    private void givenTaskPathHarness() {
        agent = mock(Agent.class);
        when(agent.getId()).thenReturn(agentId);
        when(agent.getConfig()).thenReturn(
                "{\"maxToolCallRounds\":7,\"systemPrompt\":\"You are a tester agent.\"}");
        // Conditional stubs (turn-loop-only reads) — lenient for strict-stub hygiene.
        lenient().when(agent.getProvider()).thenReturn("openai");
        lenient().when(agent.getModel()).thenReturn("gpt-4o");

        when(runRepository.findById(runId)).thenReturn(Optional.of(run));
        when(agentRepository.findById(agentId)).thenReturn(Optional.of(agent));
        when(adkProviderRegistry.resolve(agent)).thenReturn(taskProvider);
        when(taskProvider.supportsTaskExecution()).thenReturn(true);
        lenient().when(taskProvider.providerId()).thenReturn("opencode");
        when(harnessProfileService.resolve(agent)).thenReturn(HarnessProfile.defaults());
        when(sessionStateManager.loadOrCreateSession(runId, agentId))
                .thenReturn(mock(AgentSession.class));
        // Only read on the paths that reach buildMessages — lenient for strict-stub hygiene.
        lenient().when(trajectoryRepository.findByRunIdOrderByTurnNumberAsc(runId)).thenReturn(List.of());
        when(workspaceManager.getOrProvision(runId)).thenReturn("/tmp/ws");
        lenient().when(knowledgeProvider.buildKnowledgeContextPrompt(5)).thenReturn("");
        lenient().when(approvalGate.requestApproval(any(), any()))
                .thenReturn(ApprovalDecision.approve("test-approved"));
    }

    // ---- engine reads the deadline from the provider (C0.6) ----

    @Test
    void providerStatedDuration_becomesTheTaskContextDeadline() {
        givenTaskPathHarness();
        when(taskProvider.taskConstraints())
                .thenReturn(new TaskExecutionConstraints(Duration.ofMinutes(12)));
        when(taskProvider.executeTask(any(), any(), anyString(), any())).thenReturn(
                new TaskResult(runId, "sess-1", "done", 10, 5, false));

        engine.startRun(runId);

        await().atMost(Duration.ofSeconds(15))
                .until(() -> run.getStatus() == RunStatus.COMPLETED);

        TaskContext context = capturedTaskContext();
        assertThat(context.maxDuration()).isEqualTo(Duration.ofMinutes(12));
        // The agent-config round cap still comes from the config (7), unchanged
        assertThat(context.maxRounds()).isEqualTo(7);
    }

    @Test
    void nullConstraints_keepTheOpenCodeMaxTaskMinutesFallback() {
        givenTaskPathHarness();
        when(taskProvider.taskConstraints()).thenReturn(null);
        when(openCodeProperties.getMaxTaskMinutes()).thenReturn(OPENCODE_FALLBACK_MINUTES);
        when(taskProvider.executeTask(any(), any(), anyString(), any())).thenReturn(
                new TaskResult(runId, "sess-1", "done", 10, 5, false));

        engine.startRun(runId);

        await().atMost(Duration.ofSeconds(15))
                .until(() -> run.getStatus() == RunStatus.COMPLETED);

        TaskContext context = capturedTaskContext();
        assertThat(context.maxDuration()).isEqualTo(Duration.ofMinutes(OPENCODE_FALLBACK_MINUTES));
    }

    @Test
    void constraintsWithoutADuration_keepTheOpenCodeMaxTaskMinutesFallback() {
        givenTaskPathHarness();
        when(taskProvider.taskConstraints()).thenReturn(new TaskExecutionConstraints(null));
        when(openCodeProperties.getMaxTaskMinutes()).thenReturn(OPENCODE_FALLBACK_MINUTES);
        when(taskProvider.executeTask(any(), any(), anyString(), any())).thenReturn(
                new TaskResult(runId, "sess-1", "done", 10, 5, false));

        engine.startRun(runId);

        await().atMost(Duration.ofSeconds(15))
                .until(() -> run.getStatus() == RunStatus.COMPLETED);

        TaskContext context = capturedTaskContext();
        assertThat(context.maxDuration()).isEqualTo(Duration.ofMinutes(OPENCODE_FALLBACK_MINUTES));
    }

    // ---- the opencode provider keeps its previous deadline behavior ----

    @Test
    void openCodeProvider_statesItsConfiguredMaxTaskMinutes() {
        OpenCodeProperties properties = new OpenCodeProperties();
        properties.setMaxTaskMinutes(75);

        OpenCodeAdkProvider provider = newOpenCodeProvider(properties);

        TaskExecutionConstraints constraints = provider.taskConstraints();
        assertThat(constraints).isNotNull();
        assertThat(constraints.maxTaskDuration()).isEqualTo(Duration.ofMinutes(75));
    }

    @Test
    void openCodeProvider_defaultsToFortyFiveMinutes() {
        OpenCodeProperties properties = new OpenCodeProperties();

        OpenCodeAdkProvider provider = newOpenCodeProvider(properties);

        assertThat(provider.taskConstraints().maxTaskDuration()).isEqualTo(Duration.ofMinutes(45));
    }

    // ---- helpers ----

    private TaskContext capturedTaskContext() {
        ArgumentCaptor<TaskContext> captor = ArgumentCaptor.forClass(TaskContext.class);
        verify(taskProvider).executeTask(eq(agent), eq(runId), anyString(), captor.capture());
        return captor.getValue();
    }

    /**
     * The provider's sandbox-manager test constructor is package-private to
     * {@code adk.opencode}, so this test uses the public constructor — constructing
     * the manager performs no I/O.
     */
    private static OpenCodeAdkProvider newOpenCodeProvider(OpenCodeProperties properties) {
        return new OpenCodeAdkProvider(properties, mock(LlmProviderRepository.class),
                mock(ApplicationEventPublisher.class), new McpProperties());
    }
}

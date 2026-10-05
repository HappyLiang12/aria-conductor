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
import io.aria.conductor.common.model.SessionTrajectory;
import io.aria.conductor.common.runtime.ExecutionMode;
import io.aria.conductor.common.runtime.WorkspaceMode;
import io.aria.conductor.common.service.KnowledgeContextProvider;
import io.aria.conductor.common.service.ToolRegistry;
import io.aria.conductor.execution.adk.AdkProvider;
import io.aria.conductor.execution.adk.AdkProviderRegistry;
import io.aria.conductor.execution.adk.AdkSystemProperties;
import io.aria.conductor.execution.approval.ApprovalGate;
import io.aria.conductor.execution.approval.PermissionCoordinator;
import io.aria.conductor.execution.circuit.CircuitBreaker;
import io.aria.conductor.execution.harness.ToolSteeringGuard;
import io.aria.conductor.execution.llm.LlmMessage;
import io.aria.conductor.execution.pipeline.ActionExecutionPipeline;
import io.aria.conductor.execution.repository.ApprovalRepository;
import io.aria.conductor.execution.repository.PromptCallRepository;
import io.aria.conductor.execution.repository.RunExecutionBindingRepository;
import io.aria.conductor.execution.repository.SessionTrajectoryRepository;
import io.aria.conductor.execution.repository.ToolCallRepository;
import io.aria.conductor.execution.runtime.CoreAdapter;
import io.aria.conductor.execution.runtime.CoreAdapters;
import io.aria.conductor.execution.runtime.CoreCapabilities;
import io.aria.conductor.execution.runtime.CoreCatalog;
import io.aria.conductor.execution.runtime.CoreExecutionService;
import io.aria.conductor.execution.runtime.CoreResult;
import io.aria.conductor.execution.runtime.CoreRunLauncher;
import io.aria.conductor.execution.runtime.CoreSession;
import io.aria.conductor.execution.runtime.CoreTask;
import io.aria.conductor.execution.runtime.DefaultAgentExecutionPolicy;
import io.aria.conductor.execution.runtime.ExecutionSpec;
import io.aria.conductor.execution.runtime.LaunchProfile;
import io.aria.conductor.execution.runtime.PreparedEnvironment;
import io.aria.conductor.execution.runtime.RunAdmissionProperties;
import io.aria.conductor.execution.runtime.RunAdmissionQueue;
import io.aria.conductor.execution.runtime.RuntimeHandle;
import io.aria.conductor.execution.runtime.SecretBundle;
import io.aria.conductor.execution.runtime.TaskDeadlineProperties;
import io.aria.conductor.execution.tool.AgentSkillResolver;
import io.aria.conductor.execution.tool.AgentToolResolver;
import io.aria.conductor.execution.tool.WorkspaceManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Production-cutover dispatch coverage (Task 18 fix round 1): a run whose selected
 * core is in the production catalog must never execute through the legacy provider
 * path -- neither when the rest of the selection is invalid (the run fails with the
 * admission message) nor when the core is outside the catalog (the run fails with
 * the provider registry's explicit no-fallback refusal). The opencode provider
 * double is a real registry bean in both cases, so a green test proves the engine
 * never consults it while the run reaches the exact FAILED status and message.
 */
@ExtendWith(MockitoExtension.class)
class AgentLoopEngineCoreDispatchTest {

    @Mock RunRepository runRepository;
    @Mock AgentRepository agentRepository;
    @Mock SessionStateManager sessionStateManager;
    @Mock ActionExecutionPipeline actionPipeline;
    @Mock CircuitBreaker circuitBreaker;
    @Mock ApprovalGate approvalGate;
    @Mock PermissionCoordinator permissionCoordinator;
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
    @Mock TaskDeadlineProperties taskDeadlineProperties;
    /**
     * No coordinator is deployed in this unit wiring: the run must fail before any
     * coordinated execution is attempted (the engine's {@code runCoordinator()} then
     * answers null, exactly like a legacy wiring without the cutover).
     */
    @Mock(name = "coreExecutionServiceProvider")
    org.springframework.beans.factory.ObjectProvider<CoreExecutionService> coreExecutionServiceProvider;
    @Mock(name = "coreRunLauncherProvider")
    org.springframework.beans.factory.ObjectProvider<CoreRunLauncher> coreRunLauncherProvider;

    @Mock CoreExecutionService coordinator;
    @Mock RunExecutionBindingRepository bindings;
    /** The registered opencode provider bean double: must receive zero run interactions. */
    @Mock AdkProvider opencodeProvider;

    private Agent agent;
    private Run run;
    private UUID runId;
    private UUID agentId;

    @BeforeEach
    void setUp() {
        runId = UUID.randomUUID();
        agentId = UUID.randomUUID();

        agent = Agent.builder()
                .id(agentId)
                .name("worker")
                .role("dev")
                .agentType(AgentType.NATIVE)
                .provider("openai")
                .model("gpt-4o")
                .config("{\"systemPrompt\":\"You are a tester agent.\"}")
                .healthStatus(HealthStatus.HEALTHY)
                .build();

        run = Run.builder()
                .id(runId).agentId(agentId).status(RunStatus.PENDING)
                .promptSeed("do the work").maxIterations(0)
                .totalTokensUsed(0).iterationCount(0).createdAt(Instant.now())
                .build();

        when(runRepository.findById(runId)).thenReturn(Optional.of(run));
        when(runRepository.save(any(Run.class))).thenAnswer(inv -> inv.getArgument(0));
        when(agentRepository.findById(agentId)).thenReturn(Optional.of(agent));
        when(harnessProfileService.resolve(agent)).thenReturn(HarnessProfile.defaults());
        when(sessionStateManager.loadOrCreateSession(runId, agentId))
                .thenReturn(org.mockito.Mockito.mock(AgentSession.class));
        when(workspaceManager.getOrProvision(runId)).thenReturn("/tmp/ws");
        // buildMessages()/coreTask() only run when the dispatch reaches the core path
        // (the invalid-selection case); the unknown-core case refuses before it.
        lenient().when(trajectoryRepository.findByRunIdOrderByTurnNumberAsc(runId)).thenReturn(List.of());
        lenient().when(knowledgeProvider.buildKnowledgeContextPrompt(5)).thenReturn("");
        when(coreRunLauncherProvider.getIfAvailable()).thenReturn(coreRunLauncher());
    }

    /** The production launcher over the production catalog, with the coordinator/bindings doubles. */
    private CoreRunLauncher coreRunLauncher() {
        Map<String, Set<ExecutionMode>> modes = new LinkedHashMap<>();
        modes.put("opencode", EnumSet.of(ExecutionMode.HOST, ExecutionMode.SANDBOX));
        modes.put("qoder", EnumSet.of(ExecutionMode.HOST, ExecutionMode.SANDBOX));
        DefaultAgentExecutionPolicy policy = new DefaultAgentExecutionPolicy(new CoreCatalog(modes));
        CoreAdapters adapters = new CoreAdapters(List.of(adapter("opencode"), adapter("qoder")));
        return new CoreRunLauncher(policy, adapters, coordinator, bindings, taskDeadlineProperties,
                java.time.Clock.systemUTC());
    }

    private static CoreAdapter adapter(String coreId) {
        return new CoreAdapter() {
            @Override
            public String coreId() {
                return coreId;
            }

            @Override
            public CoreCapabilities capabilities(ExecutionMode mode) {
                throw new UnsupportedOperationException();
            }

            @Override
            public LaunchProfile launchProfile(ExecutionSpec spec, PreparedEnvironment environment,
                    SecretBundle credentials) {
                throw new UnsupportedOperationException();
            }

            @Override
            public CoreSession open(RuntimeHandle handle, ExecutionSpec spec, SecretBundle credentials) {
                throw new UnsupportedOperationException();
            }
        };
    }

    /** The real registry of the cutover: exactly the opencode provider bean (the documented default). */
    private AdkProviderRegistry providerRegistry() {
        when(opencodeProvider.providerId()).thenReturn("opencode");
        return new AdkProviderRegistry(List.of(opencodeProvider), new AdkSystemProperties());
    }

    private AgentLoopEngine engine() {
        return new AgentLoopEngine(
                runRepository, agentRepository, providerRegistry(), sessionStateManager,
                actionPipeline, circuitBreaker, approvalGate, permissionCoordinator, promptCallRepository,
                trajectoryRepository, toolCallRepository, eventPublisher, workflowService,
                workflowChainRepository, agentToolResolver, agentSkillResolver, toolRegistry,
                knowledgeProvider, workspaceManager, harnessProfileService, toolSteeringGuard,
                approvalRepository, taskDeadlineProperties, coreExecutionServiceProvider,
                null /* DoDService */, null /* KanbanService */, coreRunLauncherProvider,
                unlimitedAdmission());
    }

    /**
     * Admission disabled (both pools 0 = unlimited) so this suite's behavior is
     * unchanged by the engine's admission gate.
     */
    private static RunAdmissionQueue unlimitedAdmission() {
        RunAdmissionProperties properties = new RunAdmissionProperties();
        properties.setMaxActive(0);
        properties.setAriaReserved(0);
        return new RunAdmissionQueue(properties);
    }

    /** No provider-level execution was started: every entry point of the bean double stays untouched. */
    private void assertProviderPathNotTaken() {
        verify(opencodeProvider, never()).prepareAgent(any(), any());
        verify(opencodeProvider, never()).call(any(), any(), any());
        verify(opencodeProvider, never()).call(any(), any(), any(), any());
        verify(opencodeProvider, never()).executeTask(any(), any(), anyString(), any());
        verify(opencodeProvider, never()).abortTask(any());
        verify(opencodeProvider, never()).resetRuntime(any());
        verify(opencodeProvider, never()).shutdownAgent(any());
        verifyNoInteractions(coordinator);
        verify(bindings, never()).save(any());
    }

    @Test
    void catalogCoreWithAnInvalidSelection_failsWithTheAdmissionMessage_andNeverUsesTheProvider() {
        // A production core with a structurally invalid workspace selection: the run is
        // owned by the core path, so it must fail with the admission refusal instead of
        // falling through to the legacy provider (even though opencode resolves here).
        agent.setAdkProvider("opencode");
        agent.setExecutionMode(ExecutionMode.SANDBOX);
        agent.setWorkspaceMode(WorkspaceMode.DIRECT);
        agent.setWorkspacePath(null);

        engine().startRun(runId);

        await().atMost(Duration.ofSeconds(15)).until(() -> run.getStatus() == RunStatus.FAILED);
        assertThat(run.getErrorMessage())
                .isEqualTo("Direct workspace requires an explicitly selected directory");
        assertProviderPathNotTaken();
    }

    @Test
    void coreOutsideTheCatalog_failsWithTheRegistryRefusal_andNeverUsesTheProvider() {
        // A removed core: the launcher does not own the run and the registry refuses it
        // explicitly ("there is no fallback"); the provider path is never entered.
        agent.setAdkProvider("langchain");

        engine().startRun(runId);

        await().atMost(Duration.ofSeconds(15)).until(() -> run.getStatus() == RunStatus.FAILED);
        assertThat(run.getErrorMessage()).isEqualTo("Unsupported ADK provider 'langchain' for agent "
                + agentId + "; registered providers: [opencode] (there is no fallback)");
        assertProviderPathNotTaken();
    }

    /**
     * Prior conversation turns are context, not timeline: they reach the model (the
     * core task carries them as history, ahead of the run's own request), while the
     * run's persisted rows are exactly its request and its assistant output. The
     * conversation timeline aggregates rows across runs, so persisting the history
     * as this run's rows would duplicate every earlier turn.
     */
    @Test
    void priorTurnsReachTheCoreTaskAsHistory_withoutBecomingThisRunsRows() {
        agent.setAdkProvider("opencode");
        when(coordinator.execute(any(), any(), any()))
                .thenReturn(new CoreResult("session-1", "the answer", null, false));

        engine().startRun(runId, List.of(
                LlmMessage.user("earlier question"),
                LlmMessage.assistant("earlier answer")));

        await().atMost(Duration.ofSeconds(15)).until(() -> run.getStatus() == RunStatus.COMPLETED);

        ArgumentCaptor<CoreTask> task = ArgumentCaptor.forClass(CoreTask.class);
        verify(coordinator).execute(any(), any(), task.capture());
        assertThat(task.getValue().systemPrompt()).contains("You are a tester agent.");
        assertThat(task.getValue().userPrompt()).isEqualTo("do the work");
        assertThat(task.getValue().history())
                .extracting(LlmMessage::role, LlmMessage::content)
                .containsExactly(
                        tuple("user", "earlier question"),
                        tuple("assistant", "earlier answer"));

        ArgumentCaptor<SessionTrajectory> rows = ArgumentCaptor.forClass(SessionTrajectory.class);
        verify(trajectoryRepository, times(2)).save(rows.capture());
        assertThat(rows.getAllValues())
                .extracting(SessionTrajectory::getRole, SessionTrajectory::getContent)
                .containsExactly(
                        tuple("user", "do the work"),
                        tuple("assistant", "the answer"));
    }
}

package io.aria.conductor.execution.engine;

import io.aria.conductor.agent.repository.AgentRepository;
import io.aria.conductor.agent.repository.RunRepository;
import io.aria.conductor.agent.repository.WorkflowChainRepository;
import io.aria.conductor.agent.service.HarnessProfileService;
import io.aria.conductor.agent.service.WorkflowService;
import io.aria.conductor.common.event.RunIterationEvent;
import io.aria.conductor.common.model.Agent;
import io.aria.conductor.common.model.AgentSession;
import io.aria.conductor.common.model.HarnessProfile;
import io.aria.conductor.common.model.Run;
import io.aria.conductor.common.model.RunStatus;
import io.aria.conductor.common.service.KnowledgeContextProvider;
import io.aria.conductor.common.service.ToolRegistry;
import io.aria.conductor.execution.adk.AdkProvider;
import io.aria.conductor.execution.adk.AdkProviderRegistry;
import io.aria.conductor.execution.adk.TaskExecutionException;
import io.aria.conductor.execution.approval.ApprovalGate;
import io.aria.conductor.execution.approval.PermissionCoordinator;
import io.aria.conductor.execution.circuit.CircuitBreaker;
import io.aria.conductor.execution.harness.ToolSteeringGuard;
import io.aria.conductor.execution.pipeline.ActionExecutionPipeline;
import io.aria.conductor.execution.repository.ApprovalRepository;
import io.aria.conductor.execution.repository.PromptCallRepository;
import io.aria.conductor.execution.repository.SessionTrajectoryRepository;
import io.aria.conductor.execution.repository.ToolCallRepository;
import io.aria.conductor.execution.runtime.CoreExecutionService;
import io.aria.conductor.execution.runtime.CoreResult;
import io.aria.conductor.execution.runtime.CoreRunLauncher;
import io.aria.conductor.execution.runtime.RunAdmissionProperties;
import io.aria.conductor.execution.runtime.RunAdmissionQueue;
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
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Wiring-level checks for the admission gate: the queue is consulted for
 * launcher-owned runs and the start signal publication is delegated to the
 * existing event path (the behavioural park/release cycle is covered by the
 * queue suite and the cap integration test).
 *
 * <p>The construction follows {@link AgentLoopEngineTaskPathTest} (explicit
 * engine construction with the two same-erased-type {@code ObjectProvider}
 * mocks); the queue double grants instantly and records what the engine asked
 * of it, so each test observes exactly the gate's decisions.
 */
@ExtendWith(MockitoExtension.class)
class AgentLoopEngineAdmissionTest {

    @Mock RunRepository runRepository;
    @Mock AgentRepository agentRepository;
    @Mock AdkProviderRegistry adkProviderRegistry;
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
    @Mock(name = "coreExecutionServiceProvider")
    org.springframework.beans.factory.ObjectProvider<CoreExecutionService> coreExecutionServiceProvider;
    /**
     * The cutover launcher seam. The engine's second constructor parameter of
     * this erased type, hence the explicit engine construction below (same
     * reasoning as {@link AgentLoopEngineTaskPathTest}).
     */
    @Mock(name = "coreRunLauncherProvider")
    org.springframework.beans.factory.ObjectProvider<CoreRunLauncher> coreRunLauncherProvider;

    /** The stub production launcher: owns what the test says it owns. */
    @Mock CoreRunLauncher coreLauncher;

    /** The turn-level provider double for the not-owned (legacy) path. */
    @Mock AdkProvider turnProvider;

    /**
     * A queue double: records the acquire/settle calls and grants instantly.
     * The real park/release cycle is covered by the queue suites; here only the
     * engine's decisions are under test.
     */
    static class RecordingQueue extends RunAdmissionQueue {
        record AcquireCall(UUID runId, UUID agentId, Instant createdAt) {}

        final List<UUID> acquired = new ArrayList<>();
        final List<AcquireCall> acquireCalls = new ArrayList<>();
        final List<UUID> settled = new ArrayList<>();

        RecordingQueue() {
            super(new RunAdmissionProperties());
        }

        @Override
        public void acquire(UUID runId, UUID agentId, Instant createdAt) {
            acquired.add(runId);
            acquireCalls.add(new AcquireCall(runId, agentId, createdAt));
        }

        @Override
        public void settle(UUID runId) {
            settled.add(runId);
        }
    }

    AgentLoopEngine engine;
    RecordingQueue recordingQueue;

    private UUID runId;
    private UUID agentId;
    private Agent agent;
    private Run run;

    @BeforeEach
    void setUp() {
        runId = UUID.randomUUID();
        agentId = UUID.randomUUID();
        recordingQueue = new RecordingQueue();

        agent = org.mockito.Mockito.mock(Agent.class);
        // Conditional: the cancel-while-queued abort happens before the session
        // load that reads the id.
        lenient().when(agent.getId()).thenReturn(agentId);
        when(agent.getConfig()).thenReturn(
                "{\"maxToolCallRounds\":7,\"systemPrompt\":\"You are a tester agent.\"}");
        // Conditional stubs (provider/model only used on the turn-loop success path).
        lenient().when(agent.getProvider()).thenReturn("openai");
        lenient().when(agent.getModel()).thenReturn("gpt-4o");

        run = Run.builder()
                .id(runId).agentId(agentId).status(RunStatus.PENDING)
                .promptSeed("do the work").maxIterations(0)
                .totalTokensUsed(0).iterationCount(0).createdAt(Instant.now())
                .build();

        when(runRepository.findById(runId)).thenReturn(Optional.of(run));
        when(agentRepository.findById(agentId)).thenReturn(Optional.of(agent));
        when(harnessProfileService.resolve(agent)).thenReturn(HarnessProfile.defaults());

        engine = new AgentLoopEngine(
                runRepository, agentRepository, adkProviderRegistry, sessionStateManager,
                actionPipeline, circuitBreaker, approvalGate, permissionCoordinator, promptCallRepository,
                trajectoryRepository, toolCallRepository, eventPublisher, workflowService,
                workflowChainRepository, agentToolResolver, agentSkillResolver, toolRegistry,
                knowledgeProvider, workspaceManager, harnessProfileService, toolSteeringGuard,
                approvalRepository, taskDeadlineProperties, coreExecutionServiceProvider,
                null /* DoDService */, null /* KanbanService */, coreRunLauncherProvider,
                recordingQueue);
    }

    /** Stubs the post-admission run-loop plumbing both owned and not-owned runs walk through. */
    private void stubRunLoopPlumbing() {
        when(sessionStateManager.loadOrCreateSession(runId, agentId))
                .thenReturn(org.mockito.Mockito.mock(AgentSession.class));
        when(trajectoryRepository.findByRunIdOrderByTurnNumberAsc(runId)).thenReturn(List.of());
        when(workspaceManager.getOrProvision(runId)).thenReturn("/tmp/ws");
        when(knowledgeProvider.buildKnowledgeContextPrompt(5)).thenReturn("");
    }

    // ---- (a) owned: acquire at admission + start signal, no settle ----

    @Test
    void ownedRunAcquiresBeforeInitializing_withTheRunsExactIdentity_andPublishesTheStartSignal() {
        when(coreRunLauncherProvider.getIfAvailable()).thenReturn(coreLauncher);
        when(coreLauncher.owns(agent)).thenReturn(true);
        when(coreLauncher.execute(any(), any(), any())).thenReturn(
                new CoreResult("sess-1", "core accepted the run", null, false));
        stubRunLoopPlumbing();

        engine.startRun(runId);

        // The gate runs synchronously while the run is still PENDING — before the
        // INITIALIZING write that leaves the queued state; exactly one acquire,
        // carrying the run's exact identity.
        assertThat(recordingQueue.acquired).containsExactly(runId);
        assertThat(recordingQueue.acquireCalls)
                .containsExactly(new RecordingQueue.AcquireCall(runId, agentId, run.getCreatedAt()));

        await().atMost(Duration.ofSeconds(15)).until(() -> run.getStatus() == RunStatus.COMPLETED);

        // The start signal (the kanban TODO -> IN_PROGRESS move) fires from the
        // admission gate, not from the first loop iteration.
        ArgumentCaptor<ApplicationEvent> events = ArgumentCaptor.forClass(ApplicationEvent.class);
        verify(eventPublisher, atLeastOnce()).publishEvent(events.capture());
        RunIterationEvent startSignal = events.getAllValues().stream()
                .filter(RunIterationEvent.class::isInstance)
                .map(RunIterationEvent.class::cast)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no RunIterationEvent start signal was published"));
        assertThat(startSignal.getRunId()).isEqualTo(runId);
        assertThat(startSignal.getAgentId()).isEqualTo(agentId);
        assertThat(startSignal.getIteration()).isEqualTo(1);
        assertThat(startSignal.getMaxIterations()).isEqualTo(7);

        // An admitted run keeps its slot for the whole run; nothing settles it here.
        assertThat(recordingQueue.settled).as("settle is only for aborted/terminal runs").isEmpty();
    }

    // ---- (b) not owned: the legacy path never consults admission ----

    @Test
    void runNotOwnedByTheLauncher_neverConsultsAdmission_andTheLegacyPathIsUnchanged() {
        when(coreRunLauncherProvider.getIfAvailable()).thenReturn(coreLauncher);
        when(coreLauncher.owns(agent)).thenReturn(false);
        stubRunLoopPlumbing();
        when(adkProviderRegistry.resolve(agent)).thenReturn(turnProvider);
        when(turnProvider.supportsTaskExecution()).thenReturn(false);
        when(turnProvider.call(any(), any(), any(), any()))
                .thenReturn(new io.aria.conductor.execution.llm.LlmResponse("final answer", 10, 5, "stop", null));
        when(turnProvider.parseActionsFromResponse(any())).thenReturn(List.of());

        engine.startRun(runId);

        // No admission for a run the launcher does not own.
        assertThat(recordingQueue.acquired).isEmpty();

        // The legacy behavior is unchanged: the run executes and completes.
        await().atMost(Duration.ofSeconds(15)).until(() -> run.getStatus() == RunStatus.COMPLETED);
        assertThat(recordingQueue.acquired).isEmpty();
        assertThat(recordingQueue.settled).isEmpty();
    }

    // ---- (c) cancelled while queued: settle + ABORTED, never starts ----

    @Test
    void cancelledWhileQueued_settlesTheSlot_andAbortsWithoutStarting() {
        when(coreRunLauncherProvider.getIfAvailable()).thenReturn(coreLauncher);
        when(coreLauncher.owns(agent)).thenReturn(true);

        // Entry load: still PENDING (the waiter parked). Post-admission re-read:
        // the cancel landed while queued, so the run is CANCELLED.
        Run cancelled = Run.builder()
                .id(runId).agentId(agentId).status(RunStatus.CANCELLED)
                .promptSeed("do the work").maxIterations(0)
                .createdAt(run.getCreatedAt())
                .build();
        when(runRepository.findById(runId))
                .thenReturn(Optional.of(run), Optional.of(cancelled));

        TaskExecutionException thrown = catchThrowableOfType(
                () -> engine.startRun(runId), TaskExecutionException.class);

        assertThat(thrown).as("a run that left PENDING while queued must not start").isNotNull();
        assertThat(thrown.cause()).isEqualTo(TaskExecutionException.Cause.ABORTED);
        assertThat(thrown.getMessage()).contains("left PENDING while queued").contains("CANCELLED");

        // The granted slot goes back, and nothing downstream of admission happened:
        // no INITIALIZING write, no session, no start signal.
        assertThat(recordingQueue.acquired).containsExactly(runId);
        assertThat(recordingQueue.settled).containsExactly(runId);
        verify(runRepository, never()).save(any());
        verify(sessionStateManager, never()).loadOrCreateSession(any(), any());
        verify(eventPublisher, never()).publishEvent(any(RunIterationEvent.class));
        assertThat(run.getStatus()).isEqualTo(RunStatus.PENDING);
    }
}

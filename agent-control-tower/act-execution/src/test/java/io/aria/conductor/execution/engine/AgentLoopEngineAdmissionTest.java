package io.aria.conductor.execution.engine;

import io.aria.conductor.agent.repository.AgentRepository;
import io.aria.conductor.agent.repository.RunRepository;
import io.aria.conductor.agent.repository.WorkflowChainRepository;
import io.aria.conductor.agent.service.WorkflowService;
import io.aria.conductor.common.event.RunIterationEvent;
import io.aria.conductor.common.model.Agent;
import io.aria.conductor.common.model.AgentSession;
import io.aria.conductor.common.model.Run;
import io.aria.conductor.common.model.RunStatus;
import io.aria.conductor.common.model.Sensitivity;
import io.aria.conductor.execution.adk.TaskExecutionException;
import io.aria.conductor.execution.approval.ApprovalGate;
import io.aria.conductor.execution.approval.PermissionCoordinator;
import io.aria.conductor.execution.context.ContextAssembler;
import io.aria.conductor.execution.context.ContextManifestService;
import io.aria.conductor.execution.context.ContextProperties;
import io.aria.conductor.execution.context.ConversationHistoryLoader;
import io.aria.conductor.execution.context.ProviderCeilingResolver;
import io.aria.conductor.execution.context.contributor.AgentPolicyContextContributor;
import io.aria.conductor.execution.repository.ApprovalRepository;
import io.aria.conductor.execution.repository.SessionTrajectoryRepository;
import io.aria.conductor.execution.runtime.CoreExecutionService;
import io.aria.conductor.execution.runtime.CoreResult;
import io.aria.conductor.execution.runtime.CoreRunLauncher;
import io.aria.conductor.execution.runtime.RunAdmissionProperties;
import io.aria.conductor.execution.runtime.RunAdmissionQueue;
import io.aria.conductor.execution.runtime.RunInputCoordinator;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
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
 * <p>The engine is constructed explicitly with the two same-erased-type
 * {@code ObjectProvider} mocks; the queue double grants instantly and records
 * what the engine asked of it, so each test observes exactly the gate's
 * decisions.
 */
@ExtendWith(MockitoExtension.class)
class AgentLoopEngineAdmissionTest {

    @Mock RunRepository runRepository;
    @Mock AgentRepository agentRepository;
    @Mock SessionStateManager sessionStateManager;
    @Mock ApprovalGate approvalGate;
    @Mock PermissionCoordinator permissionCoordinator;
    @Mock SessionTrajectoryRepository trajectoryRepository;
    @Mock ApplicationEventPublisher eventPublisher;
    @Mock WorkflowService workflowService;
    @Mock WorkflowChainRepository workflowChainRepository;
    @Mock ProviderCeilingResolver ceilings;
    @Mock ContextManifestService manifestService;
    @Mock WorkspaceManager workspaceManager;
    @Mock ApprovalRepository approvalRepository;
    @Mock RunInputCoordinator inputCoordinator;
    @Mock(name = "coreExecutionServiceProvider")
    org.springframework.beans.factory.ObjectProvider<CoreExecutionService> coreExecutionServiceProvider;
    @Mock(name = "coreRunLauncherProvider")
    org.springframework.beans.factory.ObjectProvider<CoreRunLauncher> coreRunLauncherProvider;

    /** The stub production launcher: owns what the test says it owns. */
    @Mock CoreRunLauncher coreLauncher;

    /**
     * A queue double: records the acquire/settle calls and grants instantly.
     * The real park/release cycle is covered by the queue suites; here only the
     * engine's decisions are under test.
     */
    static class RecordingQueue extends RunAdmissionQueue {
        record AcquireCall(UUID runId, UUID agentId, Instant createdAt, String conversationId) {}

        final List<UUID> acquired = new ArrayList<>();
        final List<AcquireCall> acquireCalls = new ArrayList<>();
        final List<UUID> settled = new ArrayList<>();

        RecordingQueue() {
            super(new RunAdmissionProperties());
        }

        @Override
        public void acquire(UUID runId, UUID agentId, Instant createdAt, String conversationId) {
            acquired.add(runId);
            acquireCalls.add(new AcquireCall(runId, agentId, createdAt, conversationId));
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
        // load that reads the id, and the per-conversation tests run their own
        // Aria agent and runs instead of this fixture.
        lenient().when(agent.getId()).thenReturn(agentId);
        lenient().when(agent.getConfig()).thenReturn(
                "{\"maxToolCallRounds\":7,\"systemPrompt\":\"You are a tester agent.\"}");

        run = Run.builder()
                .id(runId).agentId(agentId).status(RunStatus.PENDING)
                .promptSeed("do the work").maxIterations(0)
                .totalTokensUsed(0).iterationCount(0).createdAt(Instant.now())
                .conversationId("conv-admission")
                .build();

        lenient().when(runRepository.findById(runId)).thenReturn(Optional.of(run));
        lenient().when(agentRepository.findById(agentId)).thenReturn(Optional.of(agent));

        engine = new AgentLoopEngine(
                runRepository, agentRepository, sessionStateManager, approvalGate,
                permissionCoordinator, trajectoryRepository, eventPublisher, workflowService,
                workflowChainRepository,
                new ContextAssembler(List.of(new AgentPolicyContextContributor(agentRepository)),
                        ceilings, new ContextProperties()),
                new ConversationHistoryLoader(runRepository, trajectoryRepository),
                manifestService, new ContextProperties(), workspaceManager,
                approvalRepository, coreExecutionServiceProvider, null /* DoDService */,
                coreRunLauncherProvider, recordingQueue, null /* KanbanRepository */,
                inputCoordinator, new RunStreamRegistry());
        lenient().when(ceilings.ceilingFor(null)).thenReturn(Sensitivity.INTERNAL);
        stubStatusWrites();
        stubCompletionWrites();
    }

    /** Mirrors the repository's conditional update onto the shared in-memory run double. */
    private void stubStatusWrites() {
        lenient().when(runRepository.updateStatusUnlessTerminal(any(), any(), any(), anyList()))
                .thenAnswer(invocation -> {
                    UUID id = invocation.getArgument(0);
                    RunStatus status = invocation.getArgument(1);
                    List<RunStatus> terminal = invocation.getArgument(3);
                    return runRepository.findById(id)
                            .filter(current -> !terminal.contains(current.getStatus()))
                            .map(current -> {
                                current.setStatus(status);
                                return 1;
                            })
                            .orElse(0);
                });
    }

    /** Mirrors the repository's conditional completion update onto the shared in-memory run double. */
    private void stubCompletionWrites() {
        lenient().when(runRepository.completeIfNotTerminal(any(), any(), anyInt(), anyLong(),
                        any(), any(), any(), any(), any(), any(), anyList()))
                .thenAnswer(invocation -> {
                    UUID id = invocation.getArgument(0);
                    RunStatus status = invocation.getArgument(1);
                    Run current = runRepository.findById(id).orElse(null);
                    List<RunStatus> terminal = List.of(RunStatus.COMPLETED, RunStatus.FAILED,
                            RunStatus.CANCELLED, RunStatus.ABORTED);
                    if (current == null || terminal.contains(current.getStatus())) {
                        return 0;
                    }
                    current.setStatus(status);
                    current.setIterationCount(invocation.getArgument(2));
                    current.setTotalTokensUsed(invocation.getArgument(3));
                    current.setCompletedAt(invocation.getArgument(4));
                    String finalOutput = invocation.getArgument(5);
                    if (finalOutput != null) {
                        current.setFinalOutput(finalOutput);
                    }
                    String errorMessage = invocation.getArgument(6);
                    if (errorMessage != null) {
                        current.setErrorMessage(errorMessage);
                    }
                    String errorCode = invocation.getArgument(7);
                    if (errorCode != null) {
                        current.setErrorCode(errorCode);
                    }
                    String errorSurface = invocation.getArgument(8);
                    if (errorSurface != null) {
                        current.setErrorSurface(errorSurface);
                    }
                    current.setUpdatedAt(invocation.getArgument(9));
                    return 1;
                });
    }

    /** Stubs the post-admission run-loop plumbing both owned and not-owned runs walk through. */
    private void stubRunLoopPlumbing() {
        when(sessionStateManager.loadOrCreateSession(runId, agentId))
                .thenReturn(org.mockito.Mockito.mock(AgentSession.class));
        when(trajectoryRepository.findByRunIdOrderByTurnNumberAsc(runId)).thenReturn(List.of());
        when(workspaceManager.getOrProvision(runId)).thenReturn("/tmp/ws");
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
        // carrying the run's exact identity AND its conversation, which is what
        // keeps two turns of one Aria conversation from running at once.
        assertThat(recordingQueue.acquired).containsExactly(runId);
        assertThat(recordingQueue.acquireCalls)
                .containsExactly(new RecordingQueue.AcquireCall(runId, agentId, run.getCreatedAt(),
                        "conv-admission"));

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

    // ---- (b) not owned: no admission, explicit refusal ----

    @Test
    void runNotOwnedByTheLauncher_neverConsultsAdmission_andFailsExplicitly() {
        when(coreRunLauncherProvider.getIfAvailable()).thenReturn(coreLauncher);
        when(coreLauncher.owns(agent)).thenReturn(false);
        lenient().when(agent.getAdkProvider()).thenReturn("legacy-core");
        when(sessionStateManager.loadOrCreateSession(runId, agentId))
                .thenReturn(org.mockito.Mockito.mock(AgentSession.class));

        engine.startRun(runId);

        // No admission for a run the launcher does not own.
        assertThat(recordingQueue.acquired).isEmpty();

        await().atMost(Duration.ofSeconds(15)).until(() -> run.getStatus() == RunStatus.FAILED);
        assertThat(run.getErrorMessage())
                .contains("Core 'legacy-core'")
                .contains("not available in this wiring")
                .contains("the run cannot execute");
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

    // ---- (d) per-conversation serialization: the engine passes the conversation ----

    /**
     * The per-conversation guarantee (spec §5.2, decision A1) only holds if the
     * engine hands the run's conversation to admission: the Aria pool admits three
     * turns at once, so a dropped conversation id lets two turns of one
     * conversation run concurrently while the queue believes they are unrelated.
     * This test drives the real queue with a launcher that blocks, so the second
     * turn must park until the first releases the conversation.
     */
    @Test
    void twoAriaTurnsOfOneConversationNeverRunAtTheSameTime() throws Exception {
        RunAdmissionQueue queue = new RunAdmissionQueue(new RunAdmissionProperties());
        engine = engineWith(queue, queueDispatcher(queue));
        Agent aria = ariaAgent();
        Run first = ariaRun("conv-shared", Instant.now());
        Run second = ariaRun("conv-shared", Instant.now().plusSeconds(1));
        stubAriaRuns(aria, first, second);
        stubRunLoopPlumbingForAnyRun();

        CountDownLatch firstRunning = new CountDownLatch(1);
        CountDownLatch secondRunning = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        when(coreRunLauncherProvider.getIfAvailable()).thenReturn(coreLauncher);
        when(coreLauncher.owns(aria)).thenReturn(true);
        when(coreLauncher.execute(any(), any(), any())).thenAnswer(invocation -> {
            Run started = invocation.getArgument(0);
            if (started.getId().equals(first.getId())) {
                firstRunning.countDown();
                releaseFirst.await(15, TimeUnit.SECONDS);
            } else {
                secondRunning.countDown();
            }
            return new CoreResult("session-1", "done", null, false);
        });

        engine.startRun(first.getId());
        assertThat(firstRunning.await(15, TimeUnit.SECONDS)).as("the first turn started").isTrue();

        AtomicReference<Throwable> secondFailure = new AtomicReference<>();
        Thread secondThread = new Thread(() -> {
            try {
                engine.startRun(second.getId());
            } catch (Throwable e) {
                secondFailure.set(e);
            }
        }, "aria-second-turn");
        secondThread.start();
        try {
            // Deterministic, not a wall-clock window: wait (bounded, generously) until the
            // second thread is provably parked INSIDE RunAdmissionQueue.acquire — in the
            // monitor wait that only the not-granted path reaches — and only then assert,
            // synchronously, that it holds no slot and was never launched. A fixed window
            // cannot prove this: 1 s against a 5 s control budget is a race the mutant can
            // win on a contended box. A state observation cannot.
            await().atMost(Duration.ofSeconds(15))
                    .untilAsserted(() -> assertThat(parkedInAdmission(secondThread))
                            .as("the second turn of the SAME conversation waits inside acquire")
                            .isTrue());
            assertThat(secondRunning.getCount())
                    .as("a second turn of the SAME conversation must not run beside the first")
                    .isEqualTo(1);
            assertThat(second.getStatus())
                    .as("and the queue never admitted it: the run is still PENDING")
                    .isEqualTo(RunStatus.PENDING);
            releaseFirst.countDown();
            assertThat(secondRunning.await(15, TimeUnit.SECONDS))
                    .as("and it runs as soon as the first turn releases the conversation")
                    .isTrue();
        } finally {
            releaseFirst.countDown();
            secondThread.join(15_000);
        }
        assertThat(secondFailure.get()).isNull();
        assertThat(secondThread.isAlive()).isFalse();
    }

    /**
     * Positive control for (d): the serialization must come from the shared
     * conversation, not from a global cap — another conversation is admitted while
     * the first turn is still running.
     */
    @Test
    void aSecondConversationIsNotBlockedByTheFirstTurnsConversation() throws Exception {
        RunAdmissionQueue queue = new RunAdmissionQueue(new RunAdmissionProperties());
        engine = engineWith(queue, queueDispatcher(queue));
        Agent aria = ariaAgent();
        Run first = ariaRun("conv-one", Instant.now());
        Run other = ariaRun("conv-two", Instant.now().plusSeconds(1));
        stubAriaRuns(aria, first, other);
        stubRunLoopPlumbingForAnyRun();

        CountDownLatch firstRunning = new CountDownLatch(1);
        CountDownLatch otherRunning = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        when(coreRunLauncherProvider.getIfAvailable()).thenReturn(coreLauncher);
        when(coreLauncher.owns(aria)).thenReturn(true);
        when(coreLauncher.execute(any(), any(), any())).thenAnswer(invocation -> {
            Run started = invocation.getArgument(0);
            if (started.getId().equals(first.getId())) {
                firstRunning.countDown();
                releaseFirst.await(15, TimeUnit.SECONDS);
            } else {
                otherRunning.countDown();
            }
            return new CoreResult("session-2", "done", null, false);
        });

        engine.startRun(first.getId());
        assertThat(firstRunning.await(15, TimeUnit.SECONDS)).isTrue();
        Thread otherThread = new Thread(() -> engine.startRun(other.getId()), "aria-other-conversation");
        otherThread.start();
        try {
            assertThat(otherRunning.await(5, TimeUnit.SECONDS))
                    .as("a different conversation is admitted while the first turn runs")
                    .isTrue();
        } finally {
            releaseFirst.countDown();
            otherThread.join(15_000);
        }
        assertThat(otherThread.isAlive()).isFalse();
    }

    /**
     * True only while the thread is blocked in {@code RunAdmissionQueue.acquire}'s
     * {@code monitor.wait} — the state the parked branch reaches and a granted one
     * never does (a grant leaves the {@code while} loop without waiting).
     */
    private static boolean parkedInAdmission(Thread thread) {
        if (thread.getState() != Thread.State.TIMED_WAITING) {
            return false;
        }
        for (StackTraceElement frame : thread.getStackTrace()) {
            if (frame.getClassName().equals(RunAdmissionQueue.class.getName())
                    && frame.getMethodName().equals("acquire")) {
                return true;
            }
        }
        return false;
    }

    private AgentLoopEngine engineWith(RunAdmissionQueue queue, ApplicationEventPublisher publisher) {
        return new AgentLoopEngine(
                runRepository, agentRepository, sessionStateManager, approvalGate,
                permissionCoordinator, trajectoryRepository, publisher, workflowService,
                workflowChainRepository,
                new ContextAssembler(List.of(new AgentPolicyContextContributor(agentRepository)),
                        ceilings, new ContextProperties()),
                new ConversationHistoryLoader(runRepository, trajectoryRepository),
                manifestService, new ContextProperties(), workspaceManager,
                approvalRepository, coreExecutionServiceProvider, null /* DoDService */,
                coreRunLauncherProvider, queue, null /* KanbanRepository */,
                inputCoordinator, new RunStreamRegistry());
    }

    /** The production wiring of the queue's terminal listener, which the suite must not mock away. */
    private static ApplicationEventPublisher queueDispatcher(RunAdmissionQueue queue) {
        return event -> {
            if (event instanceof io.aria.conductor.common.event.RunCompletedEvent completed) {
                queue.settle(completed.getRunId());
            }
        };
    }

    private Agent ariaAgent() {
        Agent aria = org.mockito.Mockito.mock(Agent.class);
        lenient().when(aria.getId()).thenReturn(io.aria.conductor.common.AriaConstants.ARIA_AGENT_ID);
        when(aria.getConfig()).thenReturn("{\"maxToolCallRounds\":7}");
        return aria;
    }

    private static Run ariaRun(String conversationId, Instant createdAt) {
        return Run.builder()
                .id(UUID.randomUUID()).agentId(io.aria.conductor.common.AriaConstants.ARIA_AGENT_ID)
                .status(RunStatus.PENDING).promptSeed("plan my day").maxIterations(0)
                .totalTokensUsed(0).iterationCount(0).createdAt(createdAt)
                .conversationId(conversationId)
                .build();
    }

    private void stubAriaRuns(Agent aria, Run... runs) {
        when(agentRepository.findById(io.aria.conductor.common.AriaConstants.ARIA_AGENT_ID))
                .thenReturn(Optional.of(aria));
        when(runRepository.findById(any())).thenAnswer(invocation -> {
            UUID id = invocation.getArgument(0);
            return java.util.Arrays.stream(runs).filter(run -> run.getId().equals(id)).findFirst();
        });
    }

    private void stubRunLoopPlumbingForAnyRun() {
        lenient().when(sessionStateManager.loadOrCreateSession(any(), any()))
                .thenReturn(org.mockito.Mockito.mock(AgentSession.class));
        lenient().when(trajectoryRepository.findByRunIdOrderByTurnNumberAsc(any())).thenReturn(List.of());
        lenient().when(workspaceManager.getOrProvision(any())).thenReturn("/tmp/ws");
    }
}

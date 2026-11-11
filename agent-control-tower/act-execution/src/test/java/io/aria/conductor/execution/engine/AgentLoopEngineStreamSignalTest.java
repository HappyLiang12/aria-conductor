package io.aria.conductor.execution.engine;

import io.aria.conductor.agent.repository.AgentRepository;
import io.aria.conductor.agent.repository.RunRepository;
import io.aria.conductor.agent.repository.WorkflowChainRepository;
import io.aria.conductor.agent.service.WorkflowService;
import io.aria.conductor.common.model.Agent;
import io.aria.conductor.common.model.AgentSession;
import io.aria.conductor.common.model.Run;
import io.aria.conductor.common.model.RunStatus;
import io.aria.conductor.common.model.Sensitivity;
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
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The engine's side of the ordered signal contract (spec §5.1 / decision A2):
 * a streaming turn binds its emitter to the run for the whole time the turn can
 * be asked something, an ask can therefore reach that stream while the run is
 * parked, and every terminal path unbinds it so a finished turn can never be
 * handed another event.
 */
@ExtendWith(MockitoExtension.class)
class AgentLoopEngineStreamSignalTest {

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
    @Mock CoreRunLauncher coreLauncher;

    private RunStreamRegistry registry;
    private AgentLoopEngine engine;
    private Run run;
    private Agent agent;
    private UUID runId;

    @BeforeEach
    void setUp() {
        runId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        registry = new RunStreamRegistry();

        agent = mock(Agent.class);
        lenient().when(agent.getId()).thenReturn(agentId);
        lenient().when(agent.getConfig()).thenReturn("{\"maxToolCallRounds\":7}");

        run = Run.builder()
                .id(runId).agentId(agentId).status(RunStatus.PENDING)
                .promptSeed("plan my day").maxIterations(0)
                .totalTokensUsed(0).iterationCount(0).createdAt(Instant.now())
                .conversationId("conv-stream")
                .build();

        lenient().when(runRepository.findById(runId)).thenReturn(Optional.of(run));
        lenient().when(agentRepository.findById(agentId)).thenReturn(Optional.of(agent));
        lenient().when(sessionStateManager.loadOrCreateSession(any(), any()))
                .thenReturn(mock(AgentSession.class));
        lenient().when(trajectoryRepository.findByRunIdOrderByTurnNumberAsc(any())).thenReturn(List.of());
        lenient().when(workspaceManager.getOrProvision(any())).thenReturn("/tmp/ws");
        lenient().when(ceilings.ceilingFor(null)).thenReturn(Sensitivity.INTERNAL);
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
        lenient().when(runRepository.completeIfNotTerminal(any(), any(), anyInt(), anyLong(),
                        any(), any(), any(), any(), any(), any(), anyList()))
                .thenAnswer(invocation -> {
                    Run current = runRepository.findById(invocation.getArgument(0)).orElse(null);
                    RunStatus status = invocation.getArgument(1);
                    if (current == null || List.of(RunStatus.COMPLETED, RunStatus.FAILED,
                            RunStatus.CANCELLED, RunStatus.ABORTED).contains(current.getStatus())) {
                        return 0;
                    }
                    current.setStatus(status);
                    return 1;
                });

        engine = new AgentLoopEngine(
                runRepository, agentRepository, sessionStateManager, approvalGate,
                permissionCoordinator, trajectoryRepository, eventPublisher, workflowService,
                workflowChainRepository,
                new ContextAssembler(List.of(new AgentPolicyContextContributor(agentRepository)),
                        ceilings, new ContextProperties()),
                new ConversationHistoryLoader(runRepository, trajectoryRepository),
                manifestService, new ContextProperties(), workspaceManager,
                approvalRepository, coreExecutionServiceProvider, null /* DoDService */,
                coreRunLauncherProvider, new RunAdmissionQueue(new RunAdmissionProperties()),
                null /* KanbanRepository */, inputCoordinator, registry);
    }

    @Test
    void aStreamingTurnBindsItsStreamAndUnbindsItWhenTheRunCompletes() throws Exception {
        CountDownLatch running = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        stubLauncherOwns();
        when(coreLauncher.execute(any(), any(), any())).thenAnswer(invocation -> {
            running.countDown();
            release.await(15, TimeUnit.SECONDS);
            return new CoreResult("session-1", "done", null, false);
        });
        SseEmitter emitter = mock(SseEmitter.class);

        engine.startRunStream(runId, emitter, List.of(), "chat");

        // Binding happens on the caller's thread, before the turn can be asked anything.
        assertThat(registry.hasStream(runId)).as("the turn's stream is bound at start").isTrue();
        assertThat(running.await(15, TimeUnit.SECONDS)).isTrue();

        release.countDown();

        await().atMost(Duration.ofSeconds(15)).until(() -> run.getStatus() == RunStatus.COMPLETED);
        await().atMost(Duration.ofSeconds(15)).until(() -> !registry.hasStream(runId));
        assertThat(registry.emit(runId, "approval_required", Map.of())).isFalse();
    }

    @Test
    void anAskReachesTheTurnsOwnStreamWhileTheRunIsStillRunning() throws Exception {
        CountDownLatch running = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        stubLauncherOwns();
        when(coreLauncher.execute(any(), any(), any())).thenAnswer(invocation -> {
            running.countDown();
            release.await(15, TimeUnit.SECONDS);
            return new CoreResult("session-1", "done", null, false);
        });
        SseEmitter emitter = mock(SseEmitter.class);

        engine.startRunStream(runId, emitter, List.of(), "chat");
        assertThat(running.await(15, TimeUnit.SECONDS)).isTrue();

        // The exact hand-off the signal listener performs from the bridge reader thread.
        assertThat(registry.emit(runId, "approval_required", Map.of("approvalId", "approval-1")))
                .as("a running turn's stream accepts an ask").isTrue();
        // The same emitter also carries the engine's own frames (thinking, done): the ask
        // arrives on that stream, under its own event name, in addition to them.
        ArgumentCaptor<SseEmitter.SseEventBuilder> frames =
                ArgumentCaptor.forClass(SseEmitter.SseEventBuilder.class);
        verify(emitter, atLeastOnce()).send(frames.capture());
        assertThat(frames.getAllValues())
                .anySatisfy(frame -> assertThat(rendered(frame)).startsWith("event:approval_required\n"));

        release.countDown();
        await().atMost(Duration.ofSeconds(15)).until(() -> run.getStatus() == RunStatus.COMPLETED);
    }

    @Test
    void aRunAbandonedAsAlreadyTerminalLeavesNoStreamBehind() {
        RunRepository.RunStateView cancelled = mock(RunRepository.RunStateView.class);
        when(cancelled.getStatus()).thenReturn(RunStatus.CANCELLED);
        when(runRepository.findStateById(runId)).thenReturn(Optional.of(cancelled));

        engine.startRunStream(runId, mock(SseEmitter.class), List.of(), "chat");

        await().atMost(Duration.ofSeconds(15)).until(() -> !registry.hasStream(runId));
        verify(runRepository, never()).completeIfNotTerminal(any(), any(), anyInt(), anyLong(),
                any(), any(), any(), any(), any(), any(), anyList());
        assertThat(registry.emit(runId, "waiting_input", Map.of())).isFalse();
    }

    @Test
    void aStartThatThrowsUnbindsTheStreamItRegisteredBeforeTheThrow() {
        // A start can refuse only after the emitter is bound: a non-PENDING run, a
        // run that left PENDING while queued, a missing run/agent. AriaStreamService
        // catches the throw and calls emitter.complete(), but Spring MVC installs the
        // emitter's handler only once the handler method returns — and a handler-less
        // ResponseBodyEmitter.complete() fires no completion callback. Without an
        // explicit unbind the registry keeps a dead entry forever and hasStream keeps
        // answering true for a stream that can never receive a frame.
        run.setStatus(RunStatus.COMPLETED);
        SseEmitter emitter = mock(SseEmitter.class);

        assertThatThrownBy(() -> engine.startRunStream(runId, emitter, List.of(), "chat"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Cannot start run in status");

        assertThat(registry.hasStream(runId))
                .as("a refused start leaves no stream behind").isFalse();
        assertThat(registry.emit(runId, "approval_required", Map.of()))
                .as("and no later signal is retried against the dead stream").isFalse();
    }

    private void stubLauncherOwns() {
        when(coreRunLauncherProvider.getIfAvailable()).thenReturn(coreLauncher);
        when(coreLauncher.owns(agent)).thenReturn(true);
    }

    /** The text parts of a frame, joined: SSE writes the event name as an {@code event:} line. */
    private static String rendered(SseEmitter.SseEventBuilder frame) {
        StringBuilder text = new StringBuilder();
        for (ResponseBodyEmitter.DataWithMediaType part : frame.build()) {
            if (part.getMediaType() == null || MediaType.TEXT_PLAIN.includes(part.getMediaType())) {
                text.append(part.getData());
            }
        }
        return text.toString();
    }
}

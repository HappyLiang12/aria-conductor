package io.aria.conductor.execution.engine;

import io.aria.conductor.agent.repository.AgentRepository;
import io.aria.conductor.agent.repository.RunRepository;
import io.aria.conductor.agent.repository.WorkflowChainRepository;
import io.aria.conductor.agent.service.WorkflowService;
import io.aria.conductor.common.event.TurnCompletedEvent;
import io.aria.conductor.common.model.Agent;
import io.aria.conductor.common.model.AgentSession;
import io.aria.conductor.common.model.AgentType;
import io.aria.conductor.common.model.HealthStatus;
import io.aria.conductor.common.model.Run;
import io.aria.conductor.common.model.RunStatus;
import io.aria.conductor.common.model.SessionTrajectory;
import io.aria.conductor.common.runtime.ExecutionMode;
import io.aria.conductor.common.runtime.WorkspaceMode;
import io.aria.conductor.common.service.KnowledgeContextProvider;
import io.aria.conductor.execution.approval.ApprovalGate;
import io.aria.conductor.execution.approval.PermissionCoordinator;
import io.aria.conductor.execution.llm.LlmMessage;
import io.aria.conductor.execution.repository.ApprovalRepository;
import io.aria.conductor.execution.repository.RunExecutionBindingRepository;
import io.aria.conductor.execution.repository.SessionTrajectoryRepository;
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
import io.aria.conductor.execution.runtime.RunInputCoordinator;
import io.aria.conductor.execution.runtime.RuntimeHandle;
import io.aria.conductor.execution.runtime.SecretBundle;
import io.aria.conductor.execution.runtime.TaskDeadlineProperties;
import io.aria.conductor.execution.runtime.UsageSnapshot;
import io.aria.conductor.execution.tool.AgentSkillResolver;
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
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Production-cutover dispatch coverage: a run whose selected core is in the
 * production catalog must never execute through a legacy provider path -- not
 * when the rest of the selection is invalid (the run fails with the admission
 * message) and not when the core is outside the catalog (the run fails with the
 * explicit no-runtime refusal). The launcher/coordinator doubles prove no
 * provider-level execution is started in either case.
 */
@ExtendWith(MockitoExtension.class)
class AgentLoopEngineCoreDispatchTest {

    @Mock RunRepository runRepository;
    @Mock AgentRepository agentRepository;
    @Mock SessionStateManager sessionStateManager;
    @Mock ApprovalGate approvalGate;
    @Mock PermissionCoordinator permissionCoordinator;
    @Mock SessionTrajectoryRepository trajectoryRepository;
    @Mock ApplicationEventPublisher eventPublisher;
    @Mock WorkflowService workflowService;
    @Mock WorkflowChainRepository workflowChainRepository;
    @Mock AgentSkillResolver agentSkillResolver;
    @Mock KnowledgeContextProvider knowledgeProvider;
    @Mock WorkspaceManager workspaceManager;
    @Mock ApprovalRepository approvalRepository;
    @Mock(name = "coreExecutionServiceProvider")
    org.springframework.beans.factory.ObjectProvider<CoreExecutionService> coreExecutionServiceProvider;
    @Mock(name = "coreRunLauncherProvider")
    org.springframework.beans.factory.ObjectProvider<CoreRunLauncher> coreRunLauncherProvider;

    @Mock CoreExecutionService coordinator;
    @Mock RunExecutionBindingRepository bindings;
    @Mock TaskDeadlineProperties taskDeadlineProperties;
    @Mock RunInputCoordinator inputCoordinator;

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
                .agentType(AgentType.ADK)
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
        lenient().when(runRepository.save(any(Run.class))).thenAnswer(inv -> inv.getArgument(0));
        when(agentRepository.findById(agentId)).thenReturn(Optional.of(agent));
        when(sessionStateManager.loadOrCreateSession(runId, agentId))
                .thenReturn(org.mockito.Mockito.mock(AgentSession.class));
        lenient().when(workspaceManager.getOrProvision(runId)).thenReturn("/tmp/ws");
        lenient().when(trajectoryRepository.findByRunIdOrderByTurnNumberAsc(runId)).thenReturn(List.of());
        lenient().when(knowledgeProvider.buildKnowledgeContextPrompt(5)).thenReturn("");
        when(coreRunLauncherProvider.getIfAvailable()).thenReturn(coreRunLauncher());
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

    private AgentLoopEngine engine() {
        return new AgentLoopEngine(
                runRepository, agentRepository, sessionStateManager, approvalGate,
                permissionCoordinator, trajectoryRepository, eventPublisher, workflowService,
                workflowChainRepository, agentSkillResolver, knowledgeProvider, workspaceManager,
                approvalRepository, coreExecutionServiceProvider, null /* DoDService */,
                coreRunLauncherProvider, unlimitedAdmission(), null /* KanbanRepository */,
                inputCoordinator);
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

    @Test
    void catalogCoreWithAnInvalidSelection_failsWithTheAdmissionMessage_andNeverUsesTheCoordinator() {
        // A production core with a structurally invalid workspace selection: the run is
        // owned by the core path, so it must fail with the admission refusal instead of
        // executing through any other path.
        agent.setAdkProvider("opencode");
        agent.setExecutionMode(ExecutionMode.SANDBOX);
        agent.setWorkspaceMode(WorkspaceMode.DIRECT);
        agent.setWorkspacePath(null);

        engine().startRun(runId);

        await().atMost(Duration.ofSeconds(15)).until(() -> run.getStatus() == RunStatus.FAILED);
        assertThat(run.getErrorMessage())
                .isEqualTo("Direct workspace requires an explicitly selected directory."
                        + " Edit the agent's workspace settings in Crew.");
        verifyNoInteractions(coordinator);
        verify(bindings, never()).save(any());
    }

    @Test
    void coreOutsideTheCatalog_failsWithTheNoRuntimeRefusal() {
        // A removed core: the launcher does not own the run and there is no fallback;
        // the run fails explicitly instead of being executed by a provider.
        agent.setAdkProvider("langchain");

        engine().startRun(runId);

        await().atMost(Duration.ofSeconds(15)).until(() -> run.getStatus() == RunStatus.FAILED);
        assertThat(run.getErrorMessage())
                .isEqualTo("Core 'langchain' is served by its run-owned core runtime"
                        + " (the core launcher/coordinator), which is not available in this wiring;"
                        + " the run cannot execute");
        verifyNoInteractions(coordinator);
        verify(bindings, never()).save(any());
    }

    @Test
    void cancelledBeforeDispatch_isNotResurrected_andNoCoreIsLaunched() {
        // A cancel landing after admission writes CANCELLED while the runtime is not
        // registered yet (owns() == false). The dispatch must not overwrite it, and
        // no core may be launched for the cancelled run.
        agent.setAdkProvider("opencode");
        when(runRepository.findStateById(runId)).thenReturn(Optional.of(view(RunStatus.CANCELLED)));

        engine().startRun(runId);

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                verify(runRepository, org.mockito.Mockito.atLeastOnce()).findStateById(runId));
        verifyNoInteractions(coordinator);
        verify(bindings, never()).save(any());
    }

    @Test
    void cancelDuringProvisioning_preventsTheCoreLaunch() {
        // PENDING at dispatch time, CANCELLED by the time the launch gate re-reads:
        // the second committed-status check must stop the launch.
        agent.setAdkProvider("opencode");
        when(runRepository.findStateById(runId)).thenReturn(
                Optional.of(view(RunStatus.PENDING)),
                Optional.of(view(RunStatus.CANCELLED)));

        engine().startRun(runId);

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                verify(runRepository, times(2)).findStateById(runId));
        verifyNoInteractions(coordinator);
        verify(bindings, never()).save(any());
    }

    private static RunRepository.RunStateView view(RunStatus status) {
        return new RunRepository.RunStateView() {
            @Override
            public RunStatus getStatus() {
                return status;
            }

            @Override
            public String getFinalOutput() {
                return null;
            }
        };
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

    /**
     * Pin for the finalize path of the waiting-input loop (2026-10-05 review,
     * finding 1): the coordinator published the question turn's TurnCompletedEvent
     * and the engine's listener already accounted it (usage, iteration,
     * trajectory); when the park ends in finalize the loop returns THAT SAME turn
     * as the run's CoreResult. Marked with {@code turnAlreadyAccounted}, the
     * engine's final recording must skip it — otherwise every finalized (or
     * cancelled-while-waiting) run double-counts the turn: inflated tokens, a
     * duplicated trajectory row and iteration +2.
     */
    @Test
    void aFinalizedQuestionTurnMarkedAlreadyAccountedIsRecordedExactlyOnce() {
        agent.setAdkProvider("opencode");
        String questionTurn = "Partial progress\n[NEED-INPUT] Which database?";
        AgentLoopEngine engine = engine();
        when(coordinator.execute(any(), any(), any())).thenAnswer(inv -> {
            // The real coordinator publishes the question turn through the event
            // bus; Spring routes it synchronously to the engine's accounting
            // listener — simulated here by invoking it inside the stub.
            engine.onTurnCompleted(new TurnCompletedEvent(this, runId, questionTurn,
                    10L, 5L, "qoder"));
            return new CoreResult("session-1", questionTurn,
                    new UsageSnapshot(10L, 5L, null, "qoder"), false, true);
        });

        engine.startRun(runId);

        await().atMost(Duration.ofSeconds(15)).until(() -> run.getStatus() == RunStatus.COMPLETED);
        // The run's totals carry the question turn exactly once (the listener),
        // never twice (listener + final recording).
        assertThat(run.getTotalTokensUsed()).isEqualTo(15L);
        assertThat(run.getIterationCount()).isEqualTo(1);
        // The trajectory holds the run's request and the question turn's answer —
        // no duplicated assistant row for the same turn.
        ArgumentCaptor<SessionTrajectory> rows = ArgumentCaptor.forClass(SessionTrajectory.class);
        verify(trajectoryRepository, times(2)).save(rows.capture());
        assertThat(rows.getAllValues())
                .extracting(SessionTrajectory::getRole, SessionTrajectory::getContent)
                .containsExactly(
                        tuple("user", "do the work"),
                        tuple("assistant", questionTurn));
    }
}

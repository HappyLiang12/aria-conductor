package io.aria.conductor.execution.engine;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
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
import io.aria.conductor.common.exception.BudgetExceededException;
import io.aria.conductor.common.service.KnowledgeContextProvider;
import io.aria.conductor.common.service.ToolRegistry;
import io.aria.conductor.execution.adk.AdkProvider;
import io.aria.conductor.execution.adk.AdkProviderRegistry;
import io.aria.conductor.execution.adk.TaskContext;
import io.aria.conductor.execution.adk.TaskResult;
import io.aria.conductor.execution.adk.opencode.OpenCodeProperties;
import io.aria.conductor.execution.approval.ApprovalDecision;
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
import org.slf4j.LoggerFactory;
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
import static org.mockito.ArgumentMatchers.argThat;
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
        // Only used on paths that reach buildMessages/buildTaskPrompt (approval-denied and
        // circuit-breaker-tripped tests never get there) — lenient for strict-stub hygiene.
        lenient().when(trajectoryRepository.findByRunIdOrderByTurnNumberAsc(runId)).thenReturn(List.of());
        when(workspaceManager.getOrProvision(runId)).thenReturn("/tmp/ws");
        lenient().when(openCodeProperties.getMaxTaskMinutes()).thenReturn(30);
        lenient().when(knowledgeProvider.buildKnowledgeContextPrompt(5)).thenReturn("");
        // The task-level approval gate now REQUIRES approval by default (governance parity
        // with the turn path). Most success-path tests just need the gate to approve, so
        // stub it leniently here; tests that exercise deny/opt-out override this per-test.
        lenient().when(approvalGate.requestApproval(any(), any()))
                .thenReturn(ApprovalDecision.approve("test-approved"));
    }

    @Test
    void taskProvider_delegatesWholeRun_andCompletesWithFinalOutput() {
        when(taskProvider.executeTask(any(), any(), anyString(), any())).thenReturn(
                new TaskResult(runId, "sess-1", "Task done output", 120, 30, false, true));

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

    // ---- H1: prior conversation turns must reach task-execution providers ----

    @Test
    void taskPrompt_multiTurnHistory_includesPriorTurnsAsTranscript() {
        // P0 (PR #91 user-POV walkthrough): within one Aria conversation the model answered
        // "this is the first message in our conversation" because buildTaskPrompt kept only
        // the LAST user message. The history below mirrors that failing walkthrough shape.
        when(trajectoryRepository.findByRunIdOrderByTurnNumberAsc(runId)).thenReturn(List.of(
                trajectory(1, "user", "remember PINEAPPLE-42"),
                trajectory(2, "assistant", "Acknowledged."),
                trajectory(3, "user", "what did I ask you to remember?")));
        when(taskProvider.executeTask(any(), any(), anyString(), any())).thenReturn(
                new TaskResult(runId, "sess-1", "done", 10, 5, false, true));

        engine.startRun(runId);

        await().atMost(Duration.ofSeconds(15))
                .until(() -> run.getStatus() == RunStatus.COMPLETED);

        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(taskProvider).executeTask(eq(agent), eq(runId), promptCaptor.capture(), any());
        String taskPrompt = promptCaptor.getValue();

        // The earlier user message AND the assistant ack must both survive into the prompt
        assertThat(taskPrompt).contains("PINEAPPLE-42");
        assertThat(taskPrompt).contains("Acknowledged.");
        // ... as a readable transcript section, in turn order
        assertThat(taskPrompt).contains("## Conversation so far");
        assertThat(taskPrompt).contains("user: remember PINEAPPLE-42");
        assertThat(taskPrompt).contains("assistant: Acknowledged.");
        assertThat(taskPrompt.indexOf("user: remember PINEAPPLE-42"))
                .as("transcript preserves conversation order")
                .isLessThan(taskPrompt.indexOf("assistant: Acknowledged."));
        // The transcript sits BEFORE the existing suffix; the last user message stays the request
        int suffixIndex = taskPrompt.indexOf("---\nUser request: what did I ask you to remember?");
        assertThat(suffixIndex).isPositive();
        assertThat(taskPrompt.indexOf("## Conversation so far")).isLessThan(suffixIndex);
        assertThat(taskPrompt).endsWith("---\nUser request: what did I ask you to remember?");
    }

    @Test
    void taskPrompt_singleTurnFreshRun_promptStaysByteIdentical() {
        // Blast-radius guard: a fresh agent run (no prior turns — every S-scenario run)
        // must keep the exact prompt bytes the E-series evidence was produced with:
        // system content (config prompt plus the blank line appended by buildMessages)
        // + "\n\n---\nUser request: " + the single user message from the promptSeed.
        when(taskProvider.executeTask(any(), any(), anyString(), any())).thenReturn(
                new TaskResult(runId, "sess-1", "done", 10, 5, false, true));

        engine.startRun(runId);

        await().atMost(Duration.ofSeconds(15))
                .until(() -> run.getStatus() == RunStatus.COMPLETED);

        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(taskProvider).executeTask(eq(agent), eq(runId), promptCaptor.capture(), any());
        assertThat(promptCaptor.getValue())
                .isEqualTo("You are a tester agent.\n\n\n\n---\nUser request: do the work");
    }

    @Test
    void taskPrompt_historyWithOnlyTheFinalUserMessage_staysByteIdentical() {
        // Streaming-path single-turn shape: initialContext persisted exactly one user
        // trajectory, which IS the final request — no prior turns, no transcript section,
        // byte-identical to the previous shape.
        when(trajectoryRepository.findByRunIdOrderByTurnNumberAsc(runId)).thenReturn(List.of(
                trajectory(1, "user", "hello there")));
        when(taskProvider.executeTask(any(), any(), anyString(), any())).thenReturn(
                new TaskResult(runId, "sess-1", "done", 10, 5, false, true));

        engine.startRun(runId);

        await().atMost(Duration.ofSeconds(15))
                .until(() -> run.getStatus() == RunStatus.COMPLETED);

        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(taskProvider).executeTask(eq(agent), eq(runId), promptCaptor.capture(), any());
        assertThat(promptCaptor.getValue())
                .isEqualTo("You are a tester agent.\n\n\n\n---\nUser request: hello there");
    }

    @Test
    void taskContext_carriesConfigMaxRounds_andOpenCodeMaxDuration() {
        when(taskProvider.executeTask(any(), any(), anyString(), any())).thenReturn(
                new TaskResult(runId, "sess-1", "done", 10, 5, false, true));

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
            return new TaskResult(runId, "sess-1", "done", 10, 5, false, true);
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
        when(taskProvider.call(any(), any(), any(), any()))
                .thenReturn(new io.aria.conductor.execution.llm.LlmResponse("final answer", 10, 5, "stop", null));
        when(taskProvider.parseActionsFromResponse(any())).thenReturn(List.of());

        engine.startRun(runId);

        await().atMost(Duration.ofSeconds(15))
                .until(() -> run.getStatus() == RunStatus.COMPLETED);

        verify(taskProvider, never()).executeTask(any(), any(), anyString(), any());
        // turn loop calls the LLM provider
        verify(taskProvider).call(any(), any(), any(), any());
    }

    // ---- #8 task-level approval gate (default REQUIRES approval) ----

    @Test
    void taskApprovalGate_defaultConfig_requestsApprovalBeforeExecution() {
        // Agent config has NO taskApprovalRequired key — the gate must engage by default
        when(taskProvider.executeTask(any(), any(), anyString(), any())).thenReturn(
                new TaskResult(runId, "sess-1", "approved task output", 20, 10, false, true));

        engine.startRun(runId);

        await().atMost(Duration.ofSeconds(15))
                .until(() -> run.getStatus() == RunStatus.COMPLETED);

        // Default (no opt-out) must route through the human gate, never a silent pass-through
        verify(approvalGate).requestApproval(any(), any());
        verify(taskProvider).executeTask(eq(agent), eq(runId), anyString(), any());
    }

    @Test
    void taskApprovalGate_explicitlyDisabled_skipsApprovalGate() {
        when(agent.getConfig()).thenReturn(
                "{\"taskApprovalRequired\":false,\"maxToolCallRounds\":7,\"systemPrompt\":\"You are a tester agent.\"}");
        when(taskProvider.executeTask(any(), any(), anyString(), any())).thenReturn(
                new TaskResult(runId, "sess-1", "done", 10, 5, false, true));

        engine.startRun(runId);

        await().atMost(Duration.ofSeconds(15))
                .until(() -> run.getStatus() == RunStatus.COMPLETED);

        // Explicit "taskApprovalRequired": false is the only opt-out
        verify(approvalGate, never()).requestApproval(any(), any());
        verify(taskProvider).executeTask(eq(agent), eq(runId), anyString(), any());
    }

    @Test
    void taskApprovalGate_denied_cancelsRunWithoutExecutingTask() {
        when(approvalGate.requestApproval(any(), any()))
                .thenReturn(ApprovalDecision.deny("operator said no"));

        engine.startRun(runId);

        await().atMost(Duration.ofSeconds(15))
                .until(() -> run.getStatus() == RunStatus.CANCELLED);

        // A denied task must never reach the provider
        verify(taskProvider, never()).executeTask(any(), any(), anyString(), any());
        assertThat(run.getErrorMessage()).contains("Task approval denied");
    }

    // ---- #17 task path persists final output to trajectory ----

    @Test
    void taskSuccess_writesAssistantTrajectoryWithFinalOutput() {
        when(trajectoryRepository.findMaxTurnNumberByRunId(runId)).thenReturn(1);
        when(taskProvider.executeTask(any(), any(), anyString(), any())).thenReturn(
                new TaskResult(runId, "sess-1", "Task done output", 120, 30, false, true));

        engine.startRun(runId);

        await().atMost(Duration.ofSeconds(15))
                .until(() -> run.getStatus() == RunStatus.COMPLETED);

        ArgumentCaptor<SessionTrajectory> captor = ArgumentCaptor.forClass(SessionTrajectory.class);
        verify(trajectoryRepository, org.mockito.Mockito.atLeastOnce()).save(captor.capture());
        // The promptSeed seeding also saves a user trajectory — find the assistant entry
        SessionTrajectory assistant = captor.getAllValues().stream()
                .filter(t -> "assistant".equals(t.getRole()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no assistant trajectory was written"));
        assertThat(assistant.getRunId()).isEqualTo(runId);
        assertThat(assistant.getTurnNumber()).isEqualTo(2); // max turn 1 + 1
        assertThat(assistant.getRole()).isEqualTo("assistant");
        assertThat(assistant.getContent()).isEqualTo("Task done output");
        assertThat(assistant.getOutputTokens()).isEqualTo(30);
    }

    @Test
    void taskFailure_doesNotWriteAssistantTrajectory() {
        when(taskProvider.executeTask(any(), any(), anyString(), any())).thenThrow(
                new io.aria.conductor.execution.adk.TaskExecutionException(
                        io.aria.conductor.execution.adk.TaskExecutionException.Cause.PROVIDER_ERROR,
                        "provider exploded"));

        engine.startRun(runId);

        await().atMost(Duration.ofSeconds(15))
                .until(() -> run.getStatus() == RunStatus.FAILED);

        // Failure path mirrors the turn loop: no assistant trajectory, no PromptCall audit
        verify(trajectoryRepository, never())
                .save(argThat(t -> "assistant".equals(t.getRole())));
        verify(promptCallRepository, never()).save(any());
    }

    // ---- fix-round 1, item 2: unknown usage is never folded into accounting ----

    @Test
    void taskProviderUnknownUsage_skipsTokenAccounting_andLogsTheMarker() {
        Logger logger = (Logger) LoggerFactory.getLogger(AgentLoopEngine.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            // A provider that cannot measure usage reports 0 placeholders with usageReported=false.
            when(taskProvider.executeTask(any(), any(), anyString(), any())).thenReturn(
                    new TaskResult(runId, "sess-1", "Task done output", 0, 0, false, false));

            engine.startRun(runId);

            await().atMost(Duration.ofSeconds(15))
                    .until(() -> run.getStatus() == RunStatus.COMPLETED);

            // Unknown usage stays unknown: no fabricated 0-fold, an explicit marker instead.
            assertThat(run.getTotalTokensUsed()).isZero();
            assertThat(run.getIterationCount()).isEqualTo(1);
            assertThat(appender.list).extracting(ILoggingEvent::getFormattedMessage)
                    .anyMatch(message -> message.contains("Token usage not reported by the provider")
                            && message.contains(runId.toString()));
        } finally {
            logger.detachAppender(appender);
        }
    }

    // ---- #19 task path is covered by the circuit breaker ----

    @Test
    void taskPath_checksCircuitBreakerBeforeExecution() {
        when(taskProvider.executeTask(any(), any(), anyString(), any())).thenReturn(
                new TaskResult(runId, "sess-1", "done", 10, 5, false, true));

        engine.startRun(runId);

        await().atMost(Duration.ofSeconds(15))
                .until(() -> run.getStatus() == RunStatus.COMPLETED);

        // Same governance as the turn loop: budget/iteration/latency caps checked on the task path
        verify(circuitBreaker).check(any());
    }

    @Test
    void circuitBreakerTrip_failsTaskRunWithoutExecutingTask() {
        org.mockito.Mockito.doThrow(new BudgetExceededException("token budget blown"))
                .when(circuitBreaker).check(any());

        engine.startRun(runId);

        await().atMost(Duration.ofSeconds(15))
                .until(() -> run.getStatus() == RunStatus.FAILED);

        // Tripped before approval/execution — the provider must never be invoked
        verify(taskProvider, never()).executeTask(any(), any(), anyString(), any());
        verify(approvalGate, never()).requestApproval(any(), any());
        assertThat(run.getErrorMessage()).contains("budget");
    }

    // ---- helpers to build plain fixtures (avoid over-mocking in edge tests) ----

    /** Persisted-trajectory fixture as buildMessages() reads it (role + content). */
    private static SessionTrajectory trajectory(int turn, String role, String content) {
        return SessionTrajectory.builder()
                .turnNumber(turn)
                .role(role)
                .content(content)
                .build();
    }

    @SuppressWarnings("unused")
    private static Agent plainAgent(UUID id) {
        return Agent.builder()
                .id(id).name("plain").role("plain role").agentType(AgentType.NATIVE)
                .provider("openai").model("gpt-4o").config("{}")
                .healthStatus(HealthStatus.HEALTHY).createdAt(Instant.now())
                .build();
    }
}

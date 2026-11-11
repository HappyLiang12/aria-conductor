package io.aria.conductor.execution.engine;

import io.aria.conductor.agent.repository.AgentRepository;
import io.aria.conductor.agent.repository.RunRepository;
import io.aria.conductor.agent.repository.WorkflowChainRepository;
import io.aria.conductor.agent.service.WorkflowService;
import io.aria.conductor.common.context.ContextAdapter;
import io.aria.conductor.common.context.ContextMessage;
import io.aria.conductor.common.context.ContextRequest;
import io.aria.conductor.common.context.ContextSection;
import io.aria.conductor.common.model.Agent;
import io.aria.conductor.common.model.AgentSession;
import io.aria.conductor.common.model.AgentType;
import io.aria.conductor.common.model.HealthStatus;
import io.aria.conductor.common.model.Run;
import io.aria.conductor.common.model.RunStatus;
import io.aria.conductor.common.model.Sensitivity;
import io.aria.conductor.execution.approval.ApprovalGate;
import io.aria.conductor.execution.approval.PermissionCoordinator;
import io.aria.conductor.execution.context.AssembledContext;
import io.aria.conductor.execution.context.ContextAssembler;
import io.aria.conductor.execution.context.ContextManifest;
import io.aria.conductor.execution.context.ContextManifestRepository;
import io.aria.conductor.execution.context.ContextManifestService;
import io.aria.conductor.execution.context.ContextProperties;
import io.aria.conductor.execution.context.ConversationHistoryLoader;
import io.aria.conductor.execution.context.ProviderCeilingResolver;
import io.aria.conductor.execution.context.contributor.ClientPolicyContextContributor;
import io.aria.conductor.execution.context.contributor.GoalContextContributor;
import io.aria.conductor.execution.llm.LlmMessage;
import io.aria.conductor.execution.repository.ApprovalRepository;
import io.aria.conductor.execution.repository.SessionTrajectoryRepository;
import io.aria.conductor.execution.runtime.CoreExecutionService;
import io.aria.conductor.execution.runtime.CoreResult;
import io.aria.conductor.execution.runtime.CoreRunLauncher;
import io.aria.conductor.execution.runtime.CoreTask;
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
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The engine consumes the assembler instead of building the prompt itself
 * (spec §8.5, P10/P2): one assembly per run/turn, the assembled system text as
 * the only system block, the assembled history in place of the caller's turns,
 * and a manifest row — while sync and streaming assemble the same envelope for
 * the same state.
 */
@ExtendWith(MockitoExtension.class)
class AgentLoopEngineContextTest {

    @Mock RunRepository runRepository;
    @Mock AgentRepository agentRepository;
    @Mock SessionStateManager sessionStateManager;
    @Mock ApprovalGate approvalGate;
    @Mock PermissionCoordinator permissionCoordinator;
    @Mock SessionTrajectoryRepository trajectoryRepository;
    @Mock ApplicationEventPublisher eventPublisher;
    @Mock WorkflowService workflowService;
    @Mock WorkflowChainRepository workflowChainRepository;
    @Mock WorkspaceManager workspaceManager;
    @Mock ApprovalRepository approvalRepository;
    @Mock(name = "coreExecutionServiceProvider")
    org.springframework.beans.factory.ObjectProvider<CoreExecutionService> coreExecutionServiceProvider;
    @Mock(name = "coreRunLauncherProvider")
    org.springframework.beans.factory.ObjectProvider<CoreRunLauncher> coreRunLauncherProvider;
    @Mock CoreRunLauncher coreLauncher;
    @Mock RunInputCoordinator inputCoordinator;
    @Mock ProviderCeilingResolver ceilings;
    @Mock ContextManifestService manifestService;
    @Mock SseEmitter emitter;

    private Agent agent;
    private final Map<UUID, Run> runsById = new java.util.HashMap<>();
    private final Map<UUID, List<io.aria.conductor.common.model.SessionTrajectory>> trajectoryRows =
            new java.util.HashMap<>();
    private final List<CoreTask> dispatched = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() {
        agent = Agent.builder()
                .id(UUID.randomUUID())
                .name("worker")
                .role("dev")
                .agentType(AgentType.ADK)
                .config("{\"systemPrompt\":\"You are a tester agent.\"}")
                .healthStatus(HealthStatus.HEALTHY)
                .build();
        when(agentRepository.findById(agent.getId())).thenReturn(Optional.of(agent));
        when(sessionStateManager.loadOrCreateSession(any(), any()))
                .thenReturn(mock(AgentSession.class));
        when(workspaceManager.getOrProvision(any())).thenReturn("/tmp/ws");
        when(runRepository.findTop20ByConversationIdOrderByCreatedAtDesc(any())).thenReturn(List.of());
        when(runRepository.findById(any())).thenAnswer(inv ->
                Optional.ofNullable(runsById.get(inv.getArgument(0))));
        when(runRepository.updateStatusUnlessTerminal(any(), any(), any(), anyList())).thenAnswer(inv -> {
            Run current = runsById.get(inv.getArgument(0));
            if (current != null) {
                current.setStatus(inv.getArgument(1));
            }
            return 1;
        });
        when(trajectoryRepository.findByRunIdOrderByTurnNumberAsc(any())).thenAnswer(invocation ->
                trajectoryRows.getOrDefault(invocation.getArgument(0), List.of()));
        when(trajectoryRepository.findMaxTurnNumberByRunId(any())).thenReturn(0);
        when(approvalRepository.findByRunIdAndStatusAndSource(any(), any(), any())).thenReturn(List.of());
        when(coreRunLauncherProvider.getIfAvailable()).thenReturn(coreLauncher);
        when(coreLauncher.owns(agent)).thenReturn(true);
        when(coreLauncher.execute(any(), any(), any())).thenAnswer(inv -> {
            dispatched.add(inv.getArgument(2));
            return new CoreResult("core-session", "done", null, false);
        });
    }

    @Test
    void systemMessageComesFromTheAssemblerAndHistoryIsNotDuplicated() {
        ContextAssembler assembler = mock(ContextAssembler.class);
        when(assembler.assemble(any(), anyList())).thenAnswer(invocation -> {
            List<ContextMessage> history = invocation.getArgument(1);
            ContextSection policy = new ContextSection("policy.aria", "Policy", "be safe",
                    Sensitivity.INTERNAL, List.of(), false, null);
            return new AssembledContext("## Policy\nbe safe\n\n", history, 3,
                    List.of(policy), List.of(), List.of());
        });
        ConversationHistoryLoader loader = loader();
        Run run = run("c", "do the work");
        start(engine(assembler, loader, manifestService), run.getId(),
                List.of(LlmMessage.system("aria policy"), LlmMessage.user("hello")));

        ContextTaskView task = awaitTask();
        assertThat(task.task.systemPrompt()).isEqualTo("## Policy\nbe safe\n\n");
        assertThat(task.task.systemPrompt()).doesNotContain("You are a tester agent.");
        assertThat(task.task.history()).extracting(LlmMessage::content).containsExactly("hello");
        assertThat(task.task.userPrompt()).isEqualTo("do the work");
        assertThat(task.task.history()).noneMatch(message -> "do the work".equals(message.content()));

        ArgumentCaptor<ContextRequest> request = ArgumentCaptor.forClass(ContextRequest.class);
        verify(assembler).assemble(request.capture(), anyList());
        assertThat(request.getValue().clientPolicy()).isEqualTo("aria policy");
        assertThat(request.getValue().priorHistory())
                .extracting(ContextMessage::content).containsExactly("hello");
        assertThat(request.getValue().adapter()).isEqualTo(ContextAdapter.SYNC);
        assertThat(request.getValue().maxSensitivity()).isEqualTo(Sensitivity.RESTRICTED);
        assertThat(request.getValue().turnNumber()).isEqualTo(1);
    }

    @Test
    void syncAndStreamingAssembleTheSameEnvelopeForTheSameState() {
        when(ceilings.ceilingFor(null)).thenReturn(Sensitivity.INTERNAL);
        ContextAssembler assembler = new ContextAssembler(
                List.of(new GoalContextContributor(), new ClientPolicyContextContributor()),
                ceilings, new ContextProperties());
        ConversationHistoryLoader loader = loader();
        AgentLoopEngine engine = engine(assembler, loader, manifestService);
        Run sync = run("shared", "do the work");
        Run stream = run("shared", "do the work");

        start(engine, sync.getId(), List.of(LlmMessage.user("hello")));
        ContextTaskView syncTask = awaitTask();
        engine.startRunStream(stream.getId(), emitter, List.of(LlmMessage.user("hello")), "chat");
        ContextTaskView streamTask = awaitTask(2);

        assertThat(syncTask.task.systemPrompt()).isEqualTo(streamTask.task.systemPrompt());
        assertThat(syncTask.task.history()).isEqualTo(streamTask.task.history());
        assertThat(syncTask.task.userPrompt()).isEqualTo(streamTask.task.userPrompt());
        assertThat(syncTask.task.systemPrompt()).contains("## Goal\ndo the work");
        assertThat(syncTask.task.history()).extracting(LlmMessage::content).containsExactly("hello");
    }

    @Test
    void theManifestRecordsTheEnvelopeAndTheHistoryDegradation() {
        ContextManifestRepository manifests = mock(ContextManifestRepository.class);
        when(manifests.findByRunIdOrderByTurnNumberAsc(any())).thenReturn(List.of());
        when(manifests.save(any(ContextManifest.class))).thenAnswer(inv -> inv.getArgument(0));
        ContextManifestService service = new ContextManifestService(
                manifests, new com.fasterxml.jackson.databind.ObjectMapper());
        when(ceilings.ceilingFor(null)).thenReturn(Sensitivity.INTERNAL);
        ContextAssembler assembler = new ContextAssembler(
                List.of(new GoalContextContributor()), ceilings, new ContextProperties());
        Run run = run("c", "do the work");

        start(engine(assembler, loader(), service), run.getId(), List.of(LlmMessage.user("hello")));
        awaitTask();

        ArgumentCaptor<ContextManifest> saved = ArgumentCaptor.forClass(ContextManifest.class);
        verify(manifests).save(saved.capture());
        ContextManifest row = saved.getValue();
        assertThat(row.getRunId()).isEqualTo(run.getId());
        assertThat(row.getAdapter()).isEqualTo("SYNC");
        assertThat(row.getTurnNumber()).isEqualTo(1);
        assertThat(row.getSectionsJson()).contains("goal");
        assertThat(row.getDegradedJson()).contains("history:client-history");
    }

    @Test
    void aManifestWriteFailureNeverFailsTheRun() {
        when(manifestService.record(any(), any()))
                .thenThrow(new IllegalStateException("manifest store unavailable"));
        ContextAssembler assembler = mock(ContextAssembler.class);
        when(assembler.assemble(any(), anyList()))
                .thenReturn(new AssembledContext("## Policy\nbe safe\n\n", List.of(), 3,
                        List.of(), List.of(), List.of()));
        Run run = run("c", "do the work");

        start(engine(assembler, loader(), manifestService), run.getId(), List.of());

        awaitTask();
        verify(coreLauncher, times(1)).execute(any(), any(), any());
    }

    @Test
    void theCurrentRequestEntersOnceThroughTheGoalSectionAndTheSeedChannel() {
        when(ceilings.ceilingFor(null)).thenReturn(Sensitivity.INTERNAL);
        ContextAssembler assembler = new ContextAssembler(
                List.of(new GoalContextContributor()), ceilings, new ContextProperties());
        Run run = run("c", "do the work");

        start(engine(assembler, loader(), manifestService), run.getId(), List.of(LlmMessage.user("hello")));

        ContextTaskView task = awaitTask();
        assertThat(task.task.systemPrompt()).contains("## Goal\ndo the work");
        assertThat(task.task.userPrompt()).isEqualTo("do the work");
        assertThat(task.task.history()).extracting(LlmMessage::content)
                .containsExactly("hello");
        int inSystem = occurrences(task.task.systemPrompt(), "do the work");
        int inHistory = task.task.history().stream()
                .mapToInt(message -> occurrences(message.content(), "do the work")).sum();
        assertThat(inSystem).isEqualTo(1);
        assertThat(inSystem + inHistory).isEqualTo(1);
    }

    @Test
    void aStaleUserTurnCanNeverBeMistakenForTheCurrentRequest() {
        when(ceilings.ceilingFor(null)).thenReturn(Sensitivity.INTERNAL);
        ContextAssembler assembler = new ContextAssembler(
                List.of(new GoalContextContributor()), ceilings, new ContextProperties());
        Run run = run("c", "do the work");
        // A retried/resumed run's own timeline: its seed row, a partial answer and
        // a later user row (an approval answer). The last user row is NOT the request.
        trajectoryRows.put(run.getId(), List.of(
                trajectory(1, "user", "do the work"),
                trajectory(2, "assistant", "partial answer"),
                trajectory(3, "user", "approve the tool call")));

        start(engine(assembler, loader(), manifestService), run.getId(), List.of(LlmMessage.user("hello")));

        ContextTaskView task = awaitTask();
        assertThat(task.task.userPrompt()).isEqualTo("do the work");
        assertThat(task.task.history()).extracting(LlmMessage::content)
                .containsExactly("hello", "partial answer", "approve the tool call");
        assertThat(task.task.history()).extracting(LlmMessage::content).doesNotContain("do the work");
        assertThat(occurrences(task.task.systemPrompt(), "do the work")).isEqualTo(1);
    }

    @Test
    void aClientTranscriptThatEchoesTheCurrentRequestAtItsTailIsDropped() {
        when(ceilings.ceilingFor(null)).thenReturn(Sensitivity.INTERNAL);
        ContextAssembler assembler = new ContextAssembler(
                List.of(new GoalContextContributor()), ceilings, new ContextProperties());
        Run run = run("c", "do the work");

        start(engine(assembler, loader(), manifestService), run.getId(),
                List.of(LlmMessage.user("hello"), LlmMessage.user("do the work")));

        ContextTaskView task = awaitTask();
        assertThat(task.task.history()).extracting(LlmMessage::content).containsExactly("hello");
        assertThat(task.task.userPrompt()).isEqualTo("do the work");
        assertThat(occurrences(task.task.systemPrompt(), "do the work")).isEqualTo(1);
    }

    @Test
    void aRunWithoutASeedHasAnEmptyUserPrompt() {
        when(ceilings.ceilingFor(null)).thenReturn(Sensitivity.INTERNAL);
        ContextAssembler assembler = new ContextAssembler(
                List.of(new GoalContextContributor()), ceilings, new ContextProperties());
        Run run = run("c", null);

        start(engine(assembler, loader(), manifestService), run.getId(), List.of());

        ContextTaskView task = awaitTask();
        assertThat(task.task.userPrompt()).isEmpty();
        assertThat(task.task.history()).isEmpty();
    }

    // ---- scaffolding ----

    private static int occurrences(String text, String needle) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (int i = text.indexOf(needle); i >= 0; i = text.indexOf(needle, i + needle.length())) {
            count++;
        }
        return count;
    }

    private static io.aria.conductor.common.model.SessionTrajectory trajectory(
            int turn, String role, String content) {
        return io.aria.conductor.common.model.SessionTrajectory.builder()
                .runId(UUID.randomUUID())
                .turnNumber(turn)
                .role(role)
                .content(content)
                .build();
    }

    private AgentLoopEngine engine(ContextAssembler assembler, ConversationHistoryLoader loader,
                                   ContextManifestService manifests) {
        return new AgentLoopEngine(runRepository, agentRepository, sessionStateManager, approvalGate,
                permissionCoordinator, trajectoryRepository, eventPublisher, workflowService,
                workflowChainRepository, assembler, loader, manifests, new ContextProperties(),
                workspaceManager, approvalRepository, coreExecutionServiceProvider,
                null /* DoDService */, coreRunLauncherProvider, unlimitedAdmission(),
                null /* KanbanRepository */, inputCoordinator);
    }

    private ConversationHistoryLoader loader() {
        return new ConversationHistoryLoader(runRepository, trajectoryRepository);
    }

    private Run run(String conversationId, String promptSeed) {
        Run run = Run.builder()
                .id(UUID.randomUUID())
                .agentId(agent.getId())
                .status(RunStatus.PENDING)
                .conversationId(conversationId)
                .promptSeed(promptSeed)
                .maxIterations(0)
                .totalTokensUsed(0)
                .iterationCount(0)
                .createdAt(Instant.now())
                .build();
        runsById.put(run.getId(), run);
        return run;
    }

    private static void start(AgentLoopEngine engine, UUID runId, List<LlmMessage> prior) {
        engine.startRun(runId, prior);
    }

    private record ContextTaskView(CoreTask task) {
    }

    private ContextTaskView awaitTask() {
        return awaitTask(1);
    }

    private ContextTaskView awaitTask(int count) {
        await().atMost(java.time.Duration.ofSeconds(10)).until(() -> dispatched.size() >= count);
        return new ContextTaskView(dispatched.get(count - 1));
    }

    private static RunAdmissionQueue unlimitedAdmission() {
        RunAdmissionProperties properties = new RunAdmissionProperties();
        properties.setMaxActive(0);
        properties.setAriaReserved(0);
        return new RunAdmissionQueue(properties);
    }
}

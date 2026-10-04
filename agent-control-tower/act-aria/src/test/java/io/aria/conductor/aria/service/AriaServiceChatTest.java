package io.aria.conductor.aria.service;

import io.aria.conductor.agent.repository.AgentRepository;
import io.aria.conductor.agent.repository.RunRepository;
import io.aria.conductor.common.AriaConstants;
import io.aria.conductor.aria.dto.AriaChatRequest;
import io.aria.conductor.aria.dto.AriaChatResponse;
import io.aria.conductor.aria.intent.IntentClassifier;
import io.aria.conductor.common.model.Agent;
import io.aria.conductor.common.model.Run;
import io.aria.conductor.common.model.RunStatus;
import io.aria.conductor.common.model.SessionTrajectory;
import io.aria.conductor.common.model.ToolCall;
import io.aria.conductor.common.model.ToolCallStatus;
import io.aria.conductor.common.service.ToolRegistry;
import io.aria.conductor.execution.engine.AgentLoopEngine;
import io.aria.conductor.execution.llm.LlmClient;
import io.aria.conductor.execution.llm.LlmMessage;
import io.aria.conductor.execution.llm.LlmProperties;
import io.aria.conductor.execution.repository.SessionTrajectoryRepository;
import io.aria.conductor.execution.repository.ToolCallRepository;
import io.aria.conductor.execution.tool.ToolExecutionEngine;
import io.aria.conductor.knowledge.service.KnowledgeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Main chat orchestration paths of {@link AriaService#chat(AriaChatRequest)}:
 * run persistence, conversation-id derivation, prior-history assembly,
 * final-output mapping and the actionsTaken synthesis fallbacks.
 */
@ExtendWith(MockitoExtension.class)
class AriaServiceChatTest {

    private static final UUID RUN_ID = UUID.randomUUID();

    @Mock AgentLoopEngine agentLoopEngine;
    @Mock AgentRepository agentRepository;
    @Mock RunRepository runRepository;
    @Mock LlmClient llmClient;
    @Mock IntentClassifier intentClassifier;
    @Mock ToolRegistry toolRegistry;
    @Mock ToolExecutionEngine toolExecutionEngine;
    @Mock KnowledgeService knowledgeService;
    @Mock SessionTrajectoryRepository trajectoryRepository;
    @Mock ToolCallRepository toolCallRepository;

    private AriaService ariaService;
    private Agent ariaAgent;
    private Run completedRun;

    @BeforeEach
    void setUp() {
        LlmProperties llmProperties = new LlmProperties();
        llmProperties.setModel("gpt-4o-mini");
        ariaService = new AriaService(agentLoopEngine, agentRepository, runRepository,
                llmClient, llmProperties, intentClassifier, toolRegistry, toolExecutionEngine,
                knowledgeService, trajectoryRepository, toolCallRepository);

        ariaAgent = Agent.builder()
                .id(AriaConstants.ARIA_AGENT_ID)
                .name("Aria")
                .config("{\"maxToolCallRounds\":15}")
                .build();

        completedRun = Run.builder()
                .id(RUN_ID)
                .agentId(AriaConstants.ARIA_AGENT_ID)
                .status(RunStatus.COMPLETED)
                .finalOutput("All done")
                .createdAt(Instant.now())
                .build();

        lenient().when(intentClassifier.classify(anyString())).thenReturn("general");
        lenient().when(agentRepository.findById(AriaConstants.ARIA_AGENT_ID))
                .thenReturn(Optional.of(ariaAgent));
        lenient().when(runRepository.findByConversationIdOrderByCreatedAtAsc(anyString()))
                .thenReturn(List.of());
        lenient().when(runRepository.save(any(Run.class))).thenAnswer(inv -> {
            Run r = inv.getArgument(0);
            r.setId(RUN_ID);
            return r;
        });
        lenient().when(runRepository.findStateById(RUN_ID))
                .thenAnswer(inv -> Optional.of(committedState()));
    }

    /** The committed row the projection reads; tests mutate {@code completedRun}. */
    private RunRepository.RunStateView committedState() {
        return new RunRepository.RunStateView() {
            @Override
            public RunStatus getStatus() {
                return completedRun.getStatus();
            }

            @Override
            public String getFinalOutput() {
                return completedRun.getFinalOutput();
            }
        };
    }

    private static RunRepository.RunStateView state(RunStatus status, String finalOutput) {
        return new RunRepository.RunStateView() {
            @Override
            public RunStatus getStatus() {
                return status;
            }

            @Override
            public String getFinalOutput() {
                return finalOutput;
            }
        };
    }

    private AriaChatRequest request(String message) {
        return AriaChatRequest.builder().conversationId("conv-1").message(message).build();
    }

    @Test
    void chat_returnsFinalOutputOfCompletedRun() {
        when(intentClassifier.classify("hello")).thenReturn("general");

        AriaChatResponse response = ariaService.chat(request("hello"));

        assertThat(response.getMessage()).isEqualTo("All done");
        assertThat(response.getRunId()).isEqualTo(RUN_ID.toString());
        assertThat(response.getConversationId()).isEqualTo("conv-1");
        assertThat(response.getIntent()).isEqualTo("general");
        assertThat(response.getActionsTaken()).isEmpty();
        assertThat(response.getTimestamp()).isNotNull();
    }

    /**
     * Pins the mechanism the sync poll depends on: the request-scoped persistence
     * context (Open Session In View) serves the instance this request saved
     * (PENDING) for the whole window, so {@code findById} would never observe the
     * engine's committed terminal state, and refreshing that instance outside a
     * transaction is what the E2E caught as a 500. Every poll therefore reads
     * through the committed-state projection, in order, so the loop sees the
     * terminal state.
     */
    @Test
    void chat_pollReadsTheCommittedStateProjectionForEveryRead() {
        when(runRepository.findStateById(RUN_ID))
                .thenReturn(Optional.of(state(RunStatus.PENDING, null)),
                        Optional.of(state(RunStatus.COMPLETED, "All done")));

        AriaChatResponse response = ariaService.chat(request("hello"));

        assertThat(response.getMessage()).isEqualTo("All done");
        verify(runRepository, times(2)).findStateById(RUN_ID);
        verify(runRepository, never()).findById(RUN_ID);
    }

    @Test
    void chat_savesPendingRunWithPromptAndConversationId() {
        ariaService.chat(request("list my agents"));

        ArgumentCaptor<Run> captor = ArgumentCaptor.forClass(Run.class);
        verify(runRepository).save(captor.capture());
        Run saved = captor.getValue();
        assertThat(saved.getAgentId()).isEqualTo(AriaConstants.ARIA_AGENT_ID);
        assertThat(saved.getPromptSeed()).isEqualTo("list my agents");
        assertThat(saved.getStatus()).isEqualTo(RunStatus.PENDING);
        assertThat(saved.getConversationId()).isEqualTo("conv-1");
        // maxToolCallRounds from Aria's agent config wins over the 0 run-level default
        assertThat(saved.getMaxIterations()).isEqualTo(15);
    }

    @Test
    void chat_delegatesExecutionToAgentLoopEngine() {
        ariaService.chat(request("hello"));

        verify(agentLoopEngine).startRun(eq(RUN_ID), anyList());
    }

    @Test
    void chat_fallsBackToSessionIdWhenConversationIdMissing() {
        AriaChatRequest req = AriaChatRequest.builder()
                .sessionId("legacy-session").message("hi").build();

        AriaChatResponse response = ariaService.chat(req);

        assertThat(response.getConversationId()).isEqualTo("legacy-session");
    }

    @Test
    void chat_generatesConversationIdWhenBothIdsMissing() {
        AriaChatRequest req = AriaChatRequest.builder().message("hi").build();

        AriaChatResponse response = ariaService.chat(req);

        assertThat(response.getConversationId()).isNotBlank();
        // must be a well-formed UUID so the frontend can persist it
        assertThat(UUID.fromString(response.getConversationId())).isNotNull();
    }

    @Test
    void chat_throwsWhenAriaAgentNotInitialized() {
        when(agentRepository.findById(AriaConstants.ARIA_AGENT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> ariaService.chat(request("hi")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Aria agent not found");
    }

    @Test
    void chat_returnsFailedRunOutputVerbatim() {
        completedRun.setStatus(RunStatus.FAILED);
        completedRun.setFinalOutput("Budget exhausted summary");

        AriaChatResponse response = ariaService.chat(request("hi"));

        assertThat(response.getMessage()).isEqualTo("Budget exhausted summary");
    }

    @Test
    void chat_synthesizesMessageFromCompletedToolCallsWhenOutputBlank() {
        completedRun.setFinalOutput("  ");
        ToolCall done = ToolCall.builder().id(UUID.randomUUID()).runId(RUN_ID)
                .toolName("list_agents").result("3 agents").status(ToolCallStatus.COMPLETED)
                .createdAt(Instant.now()).build();
        ToolCall failed = ToolCall.builder().id(UUID.randomUUID()).runId(RUN_ID)
                .toolName("bad_tool").status(ToolCallStatus.FAILED)
                .createdAt(Instant.now()).build();
        when(toolCallRepository.findByRunId(RUN_ID)).thenReturn(List.of(done, failed));

        AriaChatResponse response = ariaService.chat(request("hi"));

        assertThat(response.getMessage()).isEqualTo("Executed: list_agents");
        assertThat(response.getActionsTaken()).hasSize(1);
        assertThat(response.getActionsTaken().get(0).type()).isEqualTo("list_agents");
        assertThat(response.getActionsTaken().get(0).result()).isEqualTo("3 agents");
    }

    @Test
    void chat_usesGenericFallbackWhenOutputBlankAndNoToolCalls() {
        completedRun.setFinalOutput(null);

        AriaChatResponse response = ariaService.chat(request("hi"));

        assertThat(response.getMessage()).contains("completed your request");
        assertThat(response.getActionsTaken()).isEmpty();
    }

    @Test
    void chat_mapsNullToolResultToEmptyString() {
        ToolCall done = ToolCall.builder().id(UUID.randomUUID()).runId(RUN_ID)
                .toolName("get_run").result(null).status(ToolCallStatus.COMPLETED)
                .createdAt(Instant.now()).build();
        when(toolCallRepository.findByRunId(RUN_ID)).thenReturn(List.of(done));

        AriaChatResponse response = ariaService.chat(request("hi"));

        assertThat(response.getActionsTaken()).hasSize(1);
        assertThat(response.getActionsTaken().get(0).result()).isEmpty();
    }

    @Test
    void chat_toleratesToolCallLookupFailure() {
        completedRun.setFinalOutput(null);
        when(toolCallRepository.findByRunId(RUN_ID)).thenThrow(new RuntimeException("db down"));

        AriaChatResponse response = ariaService.chat(request("hi"));

        assertThat(response.getMessage()).contains("completed your request");
        assertThat(response.getActionsTaken()).isEmpty();
    }

    @Test
    void chat_passesPriorUserAndAssistantTurnsToEngineInOrder() {
        UUID priorRunId = UUID.randomUUID();
        Run priorRun = Run.builder().id(priorRunId).agentId(AriaConstants.ARIA_AGENT_ID)
                .status(RunStatus.COMPLETED).createdAt(Instant.now()).build();
        when(runRepository.findByConversationIdOrderByCreatedAtAsc("conv-1"))
                .thenReturn(List.of(priorRun));
        when(trajectoryRepository.findByRunIdInOrderByTurnNumberAsc(List.of(priorRunId)))
                .thenReturn(List.of(
                        trajectory(priorRunId, 1, "user", "first question"),
                        trajectory(priorRunId, 2, "assistant", "first answer"),
                        trajectory(priorRunId, 3, "tool", "tool output"),
                        trajectory(priorRunId, 4, "assistant", "   ")));

        ariaService.chat(request("follow-up"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<LlmMessage>> captor = ArgumentCaptor.forClass(List.class);
        verify(agentLoopEngine).startRun(eq(RUN_ID), captor.capture());
        List<LlmMessage> history = captor.getValue();
        assertThat(history).hasSize(2);
        assertThat(history.get(0).role()).isEqualTo("user");
        assertThat(history.get(0).content()).isEqualTo("first question");
        assertThat(history.get(1).role()).isEqualTo("assistant");
        assertThat(history.get(1).content()).isEqualTo("first answer");
    }

    @Test
    void chat_keepsFailedRunTrajectoriesAndAppendsTheFailureNote() {
        UUID completedRunId = UUID.randomUUID();
        Run completedPriorRun = Run.builder().id(completedRunId).agentId(AriaConstants.ARIA_AGENT_ID)
                .status(RunStatus.COMPLETED).createdAt(Instant.now()).build();
        UUID failedRunId = UUID.randomUUID();
        Run failedRun = Run.builder().id(failedRunId).agentId(AriaConstants.ARIA_AGENT_ID)
                .status(RunStatus.FAILED).errorMessage("relay dead").createdAt(Instant.now()).build();
        when(runRepository.findByConversationIdOrderByCreatedAtAsc("conv-1"))
                .thenReturn(List.of(completedPriorRun, failedRun));
        when(trajectoryRepository.findByRunIdInOrderByTurnNumberAsc(List.of(completedRunId, failedRunId)))
                .thenReturn(List.of(
                        trajectory(completedRunId, 1, "user", "first question"),
                        trajectory(completedRunId, 2, "assistant", "first answer"),
                        trajectory(failedRunId, 1, "user", "seed of the failed turn")));

        ariaService.chat(request("follow-up"));

        List<LlmMessage> history = capturedHistory();
        assertThat(history).hasSize(4);
        assertThat(history.get(0).role()).isEqualTo("user");
        assertThat(history.get(0).content()).isEqualTo("first question");
        assertThat(history.get(1).role()).isEqualTo("assistant");
        assertThat(history.get(1).content()).isEqualTo("first answer");
        assertThat(history.get(2).role()).isEqualTo("user");
        assertThat(history.get(2).content()).isEqualTo("seed of the failed turn");
        assertThat(history.get(3).role()).isEqualTo("assistant");
        assertThat(history.get(3).content())
                .isEqualTo("（系統註記：上一回合因「relay dead」失敗，未產生回覆。）");
    }

    @Test
    void chat_appendsGenericNoteWhenFailedRunHasNoErrorMessage() {
        UUID failedRunId = UUID.randomUUID();
        Run failedRun = Run.builder().id(failedRunId).agentId(AriaConstants.ARIA_AGENT_ID)
                .status(RunStatus.FAILED).createdAt(Instant.now()).build();
        when(runRepository.findByConversationIdOrderByCreatedAtAsc("conv-1"))
                .thenReturn(List.of(failedRun));
        when(trajectoryRepository.findByRunIdInOrderByTurnNumberAsc(List.of(failedRunId)))
                .thenReturn(List.of());

        ariaService.chat(request("retry"));

        List<LlmMessage> history = capturedHistory();
        assertThat(history).hasSize(1);
        assertThat(history.get(0).role()).isEqualTo("assistant");
        assertThat(history.get(0).content())
                .isEqualTo("（系統註記：上一回合失敗，未產生回覆。）");
    }

    @Test
    void chat_placesTheFailureNoteRightAfterItsOwnRunNeverAfterALaterRun() {
        UUID failedRunId = UUID.randomUUID();
        Run failedRun = Run.builder().id(failedRunId).agentId(AriaConstants.ARIA_AGENT_ID)
                .status(RunStatus.FAILED).errorMessage("sandbox died").createdAt(Instant.now()).build();
        UUID completedRunId = UUID.randomUUID();
        Run completedPriorRun = Run.builder().id(completedRunId).agentId(AriaConstants.ARIA_AGENT_ID)
                .status(RunStatus.COMPLETED).createdAt(Instant.now()).build();
        when(runRepository.findByConversationIdOrderByCreatedAtAsc("conv-1"))
                .thenReturn(List.of(failedRun, completedPriorRun));
        // Global turnNumber order interleaves the runs; grouping must still land the
        // note directly after the failed run's own trajectory.
        when(trajectoryRepository.findByRunIdInOrderByTurnNumberAsc(List.of(failedRunId, completedRunId)))
                .thenReturn(List.of(
                        trajectory(completedRunId, 1, "user", "second question"),
                        trajectory(failedRunId, 1, "user", "seed of the failed turn"),
                        trajectory(completedRunId, 2, "assistant", "second answer")));

        ariaService.chat(request("follow-up"));

        List<LlmMessage> history = capturedHistory();
        assertThat(history).extracting(LlmMessage::content).containsExactly(
                "seed of the failed turn",
                "（系統註記：上一回合因「sandbox died」失敗，未產生回覆。）",
                "second question",
                "second answer");
    }

    @Test
    void chat_clipsFailedRunErrorMessageTo200CharsInTheNote() {
        UUID failedRunId = UUID.randomUUID();
        Run failedRun = Run.builder().id(failedRunId).agentId(AriaConstants.ARIA_AGENT_ID)
                .status(RunStatus.FAILED).errorMessage("x".repeat(350)).createdAt(Instant.now()).build();
        when(runRepository.findByConversationIdOrderByCreatedAtAsc("conv-1"))
                .thenReturn(List.of(failedRun));
        when(trajectoryRepository.findByRunIdInOrderByTurnNumberAsc(List.of(failedRunId)))
                .thenReturn(List.of());

        ariaService.chat(request("retry"));

        List<LlmMessage> history = capturedHistory();
        assertThat(history).hasSize(1);
        assertThat(history.get(0).content())
                .isEqualTo("（系統註記：上一回合因「" + "x".repeat(200) + "」失敗，未產生回覆。）");
    }

    @Test
    void chat_usesEmptyHistoryWhenHistoryLoadFails() {
        when(runRepository.findByConversationIdOrderByCreatedAtAsc("conv-1"))
                .thenThrow(new RuntimeException("query failed"));

        AriaChatResponse response = ariaService.chat(request("hi"));

        assertThat(response.getMessage()).isEqualTo("All done");
        verify(agentLoopEngine).startRun(RUN_ID, List.of());
    }

    @Test
    void chat_returnsCancelledRunWithFallbackMessage() {
        completedRun.setStatus(RunStatus.CANCELLED);
        completedRun.setFinalOutput(null);

        AriaChatResponse response = ariaService.chat(request("hi"));

        assertThat(response.getRunId()).isEqualTo(RUN_ID.toString());
        assertThat(response.getMessage()).contains("completed your request");
    }

    /** The conversation history the chat handed to the engine, in order. */
    private List<LlmMessage> capturedHistory() {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<LlmMessage>> captor = ArgumentCaptor.forClass(List.class);
        verify(agentLoopEngine).startRun(eq(RUN_ID), captor.capture());
        return captor.getValue();
    }

    private SessionTrajectory trajectory(UUID runId, int turn, String role, String content) {
        return SessionTrajectory.builder()
                .id(UUID.randomUUID()).runId(runId).turnNumber(turn)
                .role(role).content(content).createdAt(Instant.now())
                .build();
    }
}

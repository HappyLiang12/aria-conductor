package io.aria.conductor.aria.service;

import io.aria.conductor.agent.repository.AgentRepository;
import io.aria.conductor.agent.repository.RunRepository;
import io.aria.conductor.aria.repository.AriaRunStateRepository;
import io.aria.conductor.common.AriaConstants;
import io.aria.conductor.aria.dto.AriaChatRequest;
import io.aria.conductor.aria.dto.AriaChatResponse;
import io.aria.conductor.aria.intent.IntentClassifier;
import io.aria.conductor.common.model.Agent;
import io.aria.conductor.common.model.Run;
import io.aria.conductor.common.model.RunStatus;
import io.aria.conductor.common.model.ToolCall;
import io.aria.conductor.common.model.ToolCallStatus;
import io.aria.conductor.execution.engine.AgentLoopEngine;
import io.aria.conductor.execution.llm.LlmMessage;
import io.aria.conductor.execution.repository.SessionTrajectoryRepository;
import io.aria.conductor.execution.repository.ToolCallRepository;
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
    @Mock IntentClassifier intentClassifier;
    @Mock SessionTrajectoryRepository trajectoryRepository;
    @Mock ToolCallRepository toolCallRepository;
    @Mock AriaRunStateRepository stateRepository;

    private AriaService ariaService;
    private Agent ariaAgent;
    private Run completedRun;

    @BeforeEach
    void setUp() {
        AriaSyncRunWaiter waiter = new AriaSyncRunWaiter(stateRepository,
                AriaSyncRunWaiterTestSupport.template(), millis -> { }, 0L, 1, 0L);
        ariaService = new AriaService(agentLoopEngine, agentRepository, runRepository,
                intentClassifier, toolCallRepository, waiter);

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
        lenient().when(stateRepository.findSnapshotById(RUN_ID))
                .thenAnswer(inv -> Optional.of(committedState()));
    }

    /** The committed row the projection reads; tests mutate {@code completedRun}. */
    private AriaRunStateRepository.RunSnapshot committedState() {
        return snapshot(completedRun.getStatus(), completedRun.getFinalOutput(),
                completedRun.getErrorCode(), completedRun.getErrorMessage());
    }

    private static AriaRunStateRepository.RunSnapshot state(RunStatus status, String finalOutput) {
        return snapshot(status, finalOutput, null, null);
    }

    private static AriaRunStateRepository.RunSnapshot snapshot(RunStatus status, String finalOutput,
                                                               String errorCode, String errorMessage) {
        return new AriaRunStateRepository.RunSnapshot() {
            @Override
            public RunStatus getStatus() {
                return status;
            }

            @Override
            public String getFinalOutput() {
                return finalOutput;
            }

            @Override
            public String getErrorCode() {
                return errorCode;
            }

            @Override
            public String getErrorMessage() {
                return errorMessage;
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
        when(stateRepository.findSnapshotById(RUN_ID))
                .thenReturn(Optional.of(state(RunStatus.PENDING, null)),
                        Optional.of(state(RunStatus.COMPLETED, "All done")));

        AriaChatResponse response = ariaService.chat(request("hello"));

        assertThat(response.getMessage()).isEqualTo("All done");
        verify(stateRepository, times(2)).findSnapshotById(RUN_ID);
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

    /**
     * P5: server-side history has one owner (the engine's history loader, keyed by
     * conversation id). The sync adapter must not load a second copy and must not
     * hand one to the engine.
     */
    @Test
    void chat_handsNoHistoryToTheEngineAndNeverLoadsItsOwn() {
        ariaService.chat(request("hello"));

        verify(agentLoopEngine).startRun(RUN_ID, List.of());
        verify(runRepository, never()).findByConversationIdOrderByCreatedAtAsc(anyString());
        verify(trajectoryRepository, never()).findByRunIdInOrderByTurnNumberAsc(any());
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
        assertThat(response.getStatus()).isEqualTo("FAILED");
    }

    /**
     * F2: a failed run with no narrative output must surface its stable error
     * identity and reason, and must never borrow the completed-run fallback.
     */
    @Test
    void chat_surfacesFailedRunErrorCodeAndMessageWhenNoOutput() {
        completedRun.setStatus(RunStatus.FAILED);
        completedRun.setFinalOutput(null);
        completedRun.setErrorCode("RUNTIME_CREDENTIAL_NOT_CONFIGURED");
        completedRun.setErrorMessage("No qoder credential is configured");

        AriaChatResponse response = ariaService.chat(request("hi"));

        assertThat(response.getStatus()).isEqualTo("FAILED");
        assertThat(response.getErrorCode()).isEqualTo("RUNTIME_CREDENTIAL_NOT_CONFIGURED");
        assertThat(response.getErrorMessage()).isEqualTo("No qoder credential is configured");
        assertThat(response.getMessage())
                .contains("No qoder credential is configured")
                .contains("RUNTIME_CREDENTIAL_NOT_CONFIGURED")
                .doesNotContain("completed your request");
    }

    @Test
    void chat_surfacesAbortedRunErrorCodeAndMessage() {
        completedRun.setStatus(RunStatus.ABORTED);
        completedRun.setFinalOutput(null);
        completedRun.setErrorCode("RUN_DEADLINE_EXCEEDED");
        completedRun.setErrorMessage("Run deadline exceeded");

        AriaChatResponse response = ariaService.chat(request("hi"));

        assertThat(response.getStatus()).isEqualTo("ABORTED");
        assertThat(response.getErrorCode()).isEqualTo("RUN_DEADLINE_EXCEEDED");
        assertThat(response.getErrorMessage()).isEqualTo("Run deadline exceeded");
        assertThat(response.getMessage()).contains("Run deadline exceeded");
        assertThat(response.getMessage()).doesNotContain("completed your request");
    }

    /**
     * F2: while the run is still live the sync window can expire. The reply must
     * report the observed status and run id -- never a completion it did not see.
     */
    @Test
    void chat_runningRunAfterTheSyncWindowNeverClaimsCompletion() {
        when(stateRepository.findSnapshotById(RUN_ID))
                .thenReturn(Optional.of(state(RunStatus.RUNNING, null)));

        AriaChatResponse response = ariaService.chat(request("hi"));

        assertThat(response.getStatus()).isEqualTo("RUNNING");
        assertThat(response.getRunId()).isEqualTo(RUN_ID.toString());
        assertThat(response.getMessage())
                .contains("RUNNING")
                .contains(RUN_ID.toString())
                .doesNotContain("completed your request");
        assertThat(response.getErrorCode()).isNull();
    }

    @Test
    void chat_completedRunReportsCompletedStatusAndNoErrorIdentity() {
        AriaChatResponse response = ariaService.chat(request("hi"));

        assertThat(response.getStatus()).isEqualTo("COMPLETED");
        assertThat(response.getErrorCode()).isNull();
        assertThat(response.getErrorMessage()).isNull();
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
    void chat_returnsCancelledRunWithHonestMessage() {
        completedRun.setStatus(RunStatus.CANCELLED);
        completedRun.setFinalOutput(null);

        AriaChatResponse response = ariaService.chat(request("hi"));

        assertThat(response.getRunId()).isEqualTo(RUN_ID.toString());
        assertThat(response.getStatus()).isEqualTo("CANCELLED");
        assertThat(response.getMessage())
                .contains("cancelled")
                .doesNotContain("completed your request");
    }
}

package io.aria.conductor.aria.service;

import io.aria.conductor.agent.repository.AgentRepository;
import io.aria.conductor.agent.repository.RunRepository;
import io.aria.conductor.aria.AriaConstants;
import io.aria.conductor.aria.dto.AriaChatRequest;
import io.aria.conductor.aria.intent.IntentClassifier;
import io.aria.conductor.common.model.Agent;
import io.aria.conductor.common.model.Run;
import io.aria.conductor.common.model.RunStatus;
import io.aria.conductor.execution.engine.AgentLoopEngine;
import io.aria.conductor.execution.llm.LlmMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Streaming bridge behaviour of {@link AriaStreamService#streamChat}:
 * run persistence, initial LLM context assembly from client history and
 * failure handling before/after the Run is persisted.
 */
@ExtendWith(MockitoExtension.class)
class AriaStreamServiceTest {

    private static final UUID RUN_ID = UUID.randomUUID();

    @Mock AgentLoopEngine agentLoopEngine;
    @Mock AgentRepository agentRepository;
    @Mock RunRepository runRepository;
    @Mock IntentClassifier intentClassifier;
    @Mock AriaService ariaService;

    @InjectMocks
    private AriaStreamService streamService;

    private SseEmitter emitter;

    @BeforeEach
    void setUp() {
        emitter = mock(SseEmitter.class);

        Agent ariaAgent = Agent.builder()
                .id(AriaConstants.ARIA_AGENT_ID)
                .name("Aria")
                .config("{\"maxToolCallRounds\":7}")
                .build();

        lenient().when(intentClassifier.classify(anyString())).thenReturn("general");
        lenient().when(agentRepository.findById(AriaConstants.ARIA_AGENT_ID))
                .thenReturn(Optional.of(ariaAgent));
        lenient().when(ariaService.buildSystemPrompt()).thenReturn("You are Aria.");
        lenient().when(runRepository.save(any(Run.class))).thenAnswer(inv -> {
            Run r = inv.getArgument(0);
            r.setId(RUN_ID);
            return r;
        });
    }

    private AriaChatRequest request(String message) {
        return AriaChatRequest.builder().conversationId("conv-9").message(message).build();
    }

    @Test
    void streamChat_savesPendingRunAndDelegatesToEngine() {
        streamService.streamChat(request("deploy the agents"), emitter);

        ArgumentCaptor<Run> runCaptor = ArgumentCaptor.forClass(Run.class);
        verify(runRepository).save(runCaptor.capture());
        Run saved = runCaptor.getValue();
        assertThat(saved.getAgentId()).isEqualTo(AriaConstants.ARIA_AGENT_ID);
        assertThat(saved.getPromptSeed()).isEqualTo("deploy the agents");
        assertThat(saved.getStatus()).isEqualTo(RunStatus.PENDING);
        assertThat(saved.getConversationId()).isEqualTo("conv-9");
        // maxToolCallRounds comes from the agent config JSON
        assertThat(saved.getMaxIterations()).isEqualTo(7);

        verify(agentLoopEngine).startRunStream(eq(RUN_ID), eq(emitter), anyList(), eq("general"));
    }

    @Test
    void streamChat_contextContainsSystemPromptThenUserMessage() {
        streamService.streamChat(request("hello there"), emitter);

        List<LlmMessage> context = capturedContext();
        assertThat(context).hasSize(2);
        assertThat(context.get(0).role()).isEqualTo("system");
        assertThat(context.get(0).content()).isEqualTo("You are Aria.");
        assertThat(context.get(1).role()).isEqualTo("user");
        assertThat(context.get(1).content()).isEqualTo("hello there");
    }

    @Test
    void streamChat_omitsSystemMessageWhenPromptBlank() {
        when(ariaService.buildSystemPrompt()).thenReturn("  ");

        streamService.streamChat(request("hi"), emitter);

        List<LlmMessage> context = capturedContext();
        assertThat(context).hasSize(1);
        assertThat(context.get(0).role()).isEqualTo("user");
    }

    @Test
    void streamChat_mapsClientHistoryRolesCaseInsensitivelyAndSkipsJunk() {
        AriaChatRequest req = AriaChatRequest.builder()
                .conversationId("conv-9")
                .message("next question")
                .history(List.of(
                        new AriaChatRequest.ChatMessage("USER", "earlier question"),
                        new AriaChatRequest.ChatMessage("Assistant", "earlier answer"),
                        new AriaChatRequest.ChatMessage("tool", "tool noise"),
                        new AriaChatRequest.ChatMessage("user", "   "),
                        new AriaChatRequest.ChatMessage(null, "no role")))
                .build();

        streamService.streamChat(req, emitter);

        List<LlmMessage> context = capturedContext();
        // system + 2 valid history turns + current message
        assertThat(context).hasSize(4);
        assertThat(context.get(1).role()).isEqualTo("user");
        assertThat(context.get(1).content()).isEqualTo("earlier question");
        assertThat(context.get(2).role()).isEqualTo("assistant");
        assertThat(context.get(2).content()).isEqualTo("earlier answer");
        assertThat(context.get(3).content()).isEqualTo("next question");
    }

    @Test
    void streamChat_fallsBackToSessionIdThenGeneratedUuidForConversationId() {
        AriaChatRequest legacy = AriaChatRequest.builder()
                .sessionId("legacy-1").message("hi").build();
        streamService.streamChat(legacy, emitter);

        ArgumentCaptor<Run> captor = ArgumentCaptor.forClass(Run.class);
        verify(runRepository).save(captor.capture());
        assertThat(captor.getValue().getConversationId()).isEqualTo("legacy-1");
    }

    @Test
    void streamChat_generatesUuidConversationIdWhenBothIdsMissing() {
        streamService.streamChat(AriaChatRequest.builder().message("hi").build(), emitter);

        ArgumentCaptor<Run> captor = ArgumentCaptor.forClass(Run.class);
        verify(runRepository).save(captor.capture());
        String conversationId = captor.getValue().getConversationId();
        assertThat(UUID.fromString(conversationId)).isNotNull();
    }

    @Test
    void streamChat_missingAriaAgentEmitsErrorAndCompletesWithoutEngineCall() throws Exception {
        when(agentRepository.findById(AriaConstants.ARIA_AGENT_ID)).thenReturn(Optional.empty());

        streamService.streamChat(request("hi"), emitter);

        verify(agentLoopEngine, never()).startRunStream(any(), any(), anyList(), anyString());
        verify(runRepository, never()).save(any(Run.class));
        verify(emitter).send(any(SseEmitter.SseEventBuilder.class));
        verify(emitter).complete();
    }

    @Test
    void streamChat_engineFailureMarksRunFailedAndEmitsError() throws Exception {
        org.mockito.Mockito.doThrow(new RuntimeException("boom"))
                .when(agentLoopEngine).startRunStream(any(), any(), anyList(), anyString());

        streamService.streamChat(request("hi"), emitter);

        ArgumentCaptor<Run> captor = ArgumentCaptor.forClass(Run.class);
        verify(runRepository, org.mockito.Mockito.times(2)).save(captor.capture());
        Run failed = captor.getAllValues().get(1);
        assertThat(failed.getStatus()).isEqualTo(RunStatus.FAILED);
        assertThat(failed.getErrorMessage()).contains("Stream startup failed").contains("boom");
        verify(emitter).send(any(SseEmitter.SseEventBuilder.class));
        verify(emitter).complete();
    }

    @Test
    void streamChat_registersTimeoutAndErrorCallbacks() {
        streamService.streamChat(request("hi"), emitter);

        verify(emitter).onTimeout(any(Runnable.class));
        verify(emitter).onError(any());
    }

    private List<LlmMessage> capturedContext() {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<LlmMessage>> captor = ArgumentCaptor.forClass(List.class);
        verify(agentLoopEngine).startRunStream(eq(RUN_ID), eq(emitter), captor.capture(), anyString());
        return captor.getValue();
    }
}

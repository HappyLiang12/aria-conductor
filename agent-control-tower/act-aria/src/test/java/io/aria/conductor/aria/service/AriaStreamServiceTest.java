package io.aria.conductor.aria.service;

import io.aria.conductor.agent.repository.AgentRepository;
import io.aria.conductor.agent.repository.RunRepository;
import io.aria.conductor.common.AriaConstants;
import io.aria.conductor.aria.dto.AriaChatRequest;
import io.aria.conductor.aria.intent.IntentClassifier;
import io.aria.conductor.common.model.Agent;
import io.aria.conductor.common.model.Run;
import io.aria.conductor.common.model.RunStatus;
import io.aria.conductor.common.model.SkillContext;
import io.aria.conductor.common.service.SkillContextProvider;
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
    @Mock SkillContextProvider skillContextProvider;

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
    void streamChat_contextCarriesTheClientHistoryOnly() {
        AriaChatRequest req = AriaChatRequest.builder()
                .conversationId("conv-9")
                .message("hello there")
                .history(List.of(
                        new AriaChatRequest.ChatMessage("user", "earlier question"),
                        new AriaChatRequest.ChatMessage("assistant", "earlier answer")))
                .build();

        streamService.streamChat(req, emitter);

        List<LlmMessage> context = capturedContext();
        assertThat(context).hasSize(2);
        assertThat(context.get(0).role()).isEqualTo("user");
        assertThat(context.get(0).content()).isEqualTo("earlier question");
        assertThat(context.get(1).role()).isEqualTo("assistant");
        assertThat(context.get(1).content()).isEqualTo("earlier answer");
        // The current request is the run's prompt seed: the engine persists it and
        // passes it to the model, so it must not also ride in the prior context.
        assertThat(context).noneMatch(m -> "hello there".equals(m.content()));
    }

    @Test
    void streamChat_sendsNothingWhenThereIsNoSkillAndNoHistory() {
        streamService.streamChat(request("hi"), emitter);

        assertThat(capturedContext()).isEmpty();
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
        // the 2 valid history turns (the current message is the run's prompt seed)
        assertThat(context).hasSize(2);
        assertThat(context.get(0).role()).isEqualTo("user");
        assertThat(context.get(0).content()).isEqualTo("earlier question");
        assertThat(context.get(1).role()).isEqualTo("assistant");
        assertThat(context.get(1).content()).isEqualTo("earlier answer");
        assertThat(context).noneMatch(m -> "next question".equals(m.content()));
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

    // ==================== Skill injection tests ====================

    /**
     * P1/P2: the stream adapter owns no prompt. An active slash-command skill travels
     * as a client-policy system message that the engine lifts into
     * {@code ContextRequest.clientPolicy}; the managed Aria prompt comes from the
     * context contributor, never from here.
     */
    @Test
    void streamChat_sendsTheActiveSkillAsAClientPolicySystemMessage() {
        when(skillContextProvider.getEnabledSkillsByIds(List.of("skill-123")))
                .thenReturn(List.of(new SkillContext("skill-123", "workflow", "desc",
                        "Use instantiate_template.", "SKILL")));

        AriaChatRequest req = AriaChatRequest.builder()
                .conversationId("conv-9").message("start SDD on #42").skillId("skill-123").build();
        streamService.streamChat(req, emitter);

        List<LlmMessage> context = capturedContext();
        assertThat(context).hasSize(1);
        assertThat(context.get(0).role()).isEqualTo("system");
        assertThat(context.get(0).content()).startsWith("## Active Skill: workflow");
        assertThat(context.get(0).content()).contains("Use instantiate_template.");
    }

    @Test
    void streamChat_carriesOnlyTheClientHistoryWhenNoSkillIsActive() {
        AriaChatRequest req = AriaChatRequest.builder()
                .conversationId("conv-9")
                .message("hello there")
                .history(List.of(
                        new AriaChatRequest.ChatMessage("user", "earlier question"),
                        new AriaChatRequest.ChatMessage("assistant", "earlier answer")))
                .build();

        streamService.streamChat(req, emitter);

        List<LlmMessage> context = capturedContext();
        assertThat(context).hasSize(2);
        assertThat(context).noneMatch(message -> "system".equals(message.role()));
    }

    @Test
    void streamChat_withoutSkillId_sendsNoSystemMessage() {
        streamService.streamChat(request("hello"), emitter);

        assertThat(capturedContext()).isEmpty();
    }

    @Test
    void streamChat_withSkillId_sendsOnlyTheSkillDirective() {
        when(skillContextProvider.getEnabledSkillsByIds(List.of("skill-123")))
                .thenReturn(List.of(new SkillContext("skill-123", "workflow", "desc", "Use instantiate_template.", "SKILL")));

        AriaChatRequest req = AriaChatRequest.builder()
                .conversationId("conv-9").message("start SDD on #42").skillId("skill-123").build();
        streamService.streamChat(req, emitter);

        List<LlmMessage> context = capturedContext();
        assertThat(context).hasSize(1);
        assertThat(context.get(0).role()).isEqualTo("system");
        assertThat(context.get(0).content()).startsWith("## Active Skill: workflow");
        assertThat(context.get(0).content()).contains("Use instantiate_template.");
    }

    @Test
    void streamChat_withUnknownOrDisabledSkillId_injectsNothing() {
        when(skillContextProvider.getEnabledSkillsByIds(List.of("nonexistent")))
                .thenReturn(List.of()); // filtered out by governance

        AriaChatRequest req = AriaChatRequest.builder()
                .conversationId("conv-9").message("hi").skillId("nonexistent").build();
        streamService.streamChat(req, emitter);

        assertThat(capturedContext()).isEmpty();
    }

    @Test
    void streamChat_whenSkillLookupThrows_degradesGracefully() {
        when(skillContextProvider.getEnabledSkillsByIds(any()))
                .thenThrow(new RuntimeException("DB connection lost"));

        AriaChatRequest req = AriaChatRequest.builder()
                .conversationId("conv-9").message("hi").skillId("skill-bad").build();
        streamService.streamChat(req, emitter);

        // Should still work — run saved, engine called, no directive injected
        assertThat(capturedContext()).isEmpty();
        verify(runRepository).save(any(Run.class));
    }
}

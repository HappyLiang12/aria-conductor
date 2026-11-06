package io.aria.conductor.aria.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aria.conductor.agent.repository.AgentRepository;
import io.aria.conductor.agent.repository.RunRepository;
import io.aria.conductor.aria.dto.AriaChatRequest;
import io.aria.conductor.aria.intent.IntentClassifier;
import io.aria.conductor.common.AriaConstants;
import io.aria.conductor.common.model.Agent;
import io.aria.conductor.common.model.Run;
import io.aria.conductor.common.model.RunStatus;
import io.aria.conductor.common.service.SkillContextProvider;
import io.aria.conductor.execution.engine.AgentLoopEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Failure-path payload contract of {@link AriaStreamService#streamChat}: a failed turn
 * (the Run was persisted and marked FAILED) enriches the terminal SSE {@code error}
 * payload with {@code turnFailed}/{@code runId}/{@code reason}, while non-turn errors
 * keep the legacy {@code {"message": ...}} shape.
 *
 * <p>The emitter is mocked, so the JSON is recovered from the captured
 * {@link SseEmitter.SseEventBuilder} and the assertions pin what is actually emitted.
 */
@ExtendWith(MockitoExtension.class)
class AriaStreamServiceFailureTest {

    private static final UUID RUN_ID = UUID.randomUUID();
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Mock AgentLoopEngine agentLoopEngine;
    @Mock AgentRepository agentRepository;
    @Mock RunRepository runRepository;
    @Mock IntentClassifier intentClassifier;
    @Mock AriaService ariaService;
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

    private void failEngineWith(String message) throws Exception {
        doThrow(new RuntimeException(message))
                .when(agentLoopEngine).startRunStream(any(), any(), anyList(), anyString());
    }

    // ==================== behavioural: the failure path through streamChat ====================

    @Test
    void turnFailure_enrichesTheErrorPayloadWithTurnFailedRunIdAndReason() throws Exception {
        failEngineWith("boom");

        streamService.streamChat(request("hi"), emitter);

        // The catch under test really marked the persisted run FAILED
        ArgumentCaptor<Run> runCaptor = ArgumentCaptor.forClass(Run.class);
        verify(runRepository, times(2)).save(runCaptor.capture());
        assertThat(runCaptor.getAllValues().get(1).getStatus()).isEqualTo(RunStatus.FAILED);

        Set<ResponseBodyEmitter.DataWithMediaType> fragments = capturedErrorFragments();
        assertThat(framingOf(fragments)).contains("event:error");
        JsonNode payload = payloadOf(fragments);
        assertThat(payload.size()).isEqualTo(4);
        assertThat(payload.get("message").asText()).isEqualTo("Aria streaming failed: boom");
        assertThat(payload.get("turnFailed").asBoolean()).isTrue();
        assertThat(payload.get("runId").asText()).isEqualTo(RUN_ID.toString());
        assertThat(payload.get("reason").asText()).isEqualTo("Stream startup failed: boom");
        verify(emitter).complete();
    }

    @Test
    void turnFailure_clipsTheReasonAt300Chars() throws Exception {
        String longCause = "x".repeat(400);
        failEngineWith(longCause);

        streamService.streamChat(request("hi"), emitter);

        JsonNode payload = payloadOf(capturedErrorFragments());
        String expected = ("Stream startup failed: " + longCause).substring(0, 300);
        assertThat(payload.get("reason").asText()).isEqualTo(expected).hasSize(300);
    }

    @Test
    void timeoutError_keepsTheLegacyPayloadShape() throws Exception {
        streamService.streamChat(request("hi"), emitter);

        ArgumentCaptor<Runnable> timeoutCaptor = ArgumentCaptor.forClass(Runnable.class);
        verify(emitter).onTimeout(timeoutCaptor.capture());
        timeoutCaptor.getValue().run();

        JsonNode payload = payloadOf(capturedErrorFragments());
        assertThat(payload.size()).isEqualTo(1);
        assertThat(payload.get("message").asText()).isEqualTo("stream timed out");
        assertThat(payload.has("turnFailed")).isFalse();
        assertThat(payload.has("runId")).isFalse();
        assertThat(payload.has("reason")).isFalse();
        verify(emitter).complete();
    }

    @Test
    void startupFailureBeforeTheRunIsPersisted_keepsTheLegacyPayloadShape() throws Exception {
        when(agentRepository.findById(AriaConstants.ARIA_AGENT_ID)).thenReturn(Optional.empty());

        streamService.streamChat(request("hi"), emitter);

        JsonNode payload = payloadOf(capturedErrorFragments());
        assertThat(payload.size()).isEqualTo(1);
        assertThat(payload.get("message").asText())
                .isEqualTo("Aria streaming failed: Aria agent not found");
        assertThat(payload.has("turnFailed")).isFalse();
        assertThat(payload.has("runId")).isFalse();
        verify(emitter).complete();
    }

    // ==================== unit: the exact JSON mapping of the payload builder ====================

    @Test
    void errorPayloadJson_pinsTheEnrichedTurnFailureShape() {
        Run failedRun = Run.builder().id(RUN_ID).errorMessage("boom happened").build();

        String json = AriaStreamService.errorPayloadJson("Aria streaming failed: boom", failedRun, "boom");

        assertThat(json).isEqualTo("{\"message\":\"Aria streaming failed: boom\",\"turnFailed\":true,"
                + "\"runId\":\"" + RUN_ID + "\",\"reason\":\"boom happened\"}");
    }

    @Test
    void errorPayloadJson_nonTurnErrorKeepsOnlyTheMessageKey() {
        assertThat(AriaStreamService.errorPayloadJson("stream timed out", null, null))
                .isEqualTo("{\"message\":\"stream timed out\"}");
    }

    @Test
    void errorPayloadJson_nullMessageFallsBackToUnknownError() {
        assertThat(AriaStreamService.errorPayloadJson(null, null, null))
                .isEqualTo("{\"message\":\"unknown error\"}");
    }

    @Test
    void errorPayloadJson_blankRunErrorFallsBackToTheCauseMessage() {
        Run blankError = Run.builder().id(RUN_ID).errorMessage("  ").build();

        assertThat(AriaStreamService.errorPayloadJson("m", blankError, "the cause"))
                .contains("\"reason\":\"the cause\"");
        assertThat(AriaStreamService.errorPayloadJson("m", Run.builder().id(RUN_ID).build(), "the cause"))
                .contains("\"reason\":\"the cause\"");
    }

    @Test
    void errorPayloadJson_clipsReasonAt300CharsWithoutKeepingTheTail() {
        Run failedRun = Run.builder().id(RUN_ID).errorMessage("y".repeat(400)).build();

        String json = AriaStreamService.errorPayloadJson("m", failedRun, null);

        assertThat(json).contains("\"reason\":\"" + "y".repeat(300) + "\"");
        assertThat(json).doesNotContain("y".repeat(301));
    }

    @Test
    void errorPayloadJson_missingReasonFallsBackToUnknownError() {
        Run failedRun = Run.builder().id(RUN_ID).build();

        assertThat(AriaStreamService.errorPayloadJson("m", failedRun, null))
                .contains("\"reason\":\"unknown error\"");
    }

    @Test
    void errorPayloadJson_runWithoutIdIsNotATurnFailure() {
        Run unsaved = Run.builder().errorMessage("boom").build();

        assertThat(AriaStreamService.errorPayloadJson("m", unsaved, "boom"))
                .isEqualTo("{\"message\":\"m\"}");
    }

    // ==================== helpers ====================

    private Set<ResponseBodyEmitter.DataWithMediaType> capturedErrorFragments() throws Exception {
        ArgumentCaptor<SseEmitter.SseEventBuilder> captor =
                ArgumentCaptor.forClass(SseEmitter.SseEventBuilder.class);
        verify(emitter).send(captor.capture());
        // SseEventBuilder.build() is the public unpacking Spring itself uses when the
        // event is written out: [name frame (text/plain), payload, terminator].
        return captor.getValue().build();
    }

    private static String framingOf(Set<ResponseBodyEmitter.DataWithMediaType> fragments) {
        return fragmentMatching(fragments, "event:");
    }

    private static JsonNode payloadOf(Set<ResponseBodyEmitter.DataWithMediaType> fragments) throws Exception {
        return MAPPER.readTree(fragmentMatching(fragments, "{"));
    }

    private static String fragmentMatching(Set<ResponseBodyEmitter.DataWithMediaType> fragments, String prefix) {
        return fragments.stream()
                .map(ResponseBodyEmitter.DataWithMediaType::getData)
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .filter(fragment -> fragment.startsWith(prefix))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "no SSE fragment starting with '" + prefix + "' in " + fragments));
    }
}
